/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import java.nio.ByteBuffer
import java.util.Optional
import java.util.concurrent.locks.ReentrantLock

import kafka.cluster.BrokerEndPoint
import kafka.utils.{DelayedItem, Pool, ShutdownableThread}
import org.apache.kafka.common.errors._
import org.apache.kafka.common.requests.EpochEndOffset._
import kafka.common.ClientIdAndBroker
import kafka.metrics.KafkaMetricsGroup
import kafka.utils.CoreUtils.inLock
import org.apache.kafka.common.protocol.Errors
import AbstractFetcherThread._

import scala.collection.{Map, Set, mutable}
import scala.collection.JavaConverters._
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

import com.yammer.metrics.core.Gauge
import kafka.log.LogAppendInfo
import org.apache.kafka.common.{KafkaException, TopicPartition}
import org.apache.kafka.common.internals.PartitionStates
import org.apache.kafka.common.record.{FileRecords, MemoryRecords, Records}
import org.apache.kafka.common.requests._

import scala.math._

/**
 *  Abstract class for fetching data from multiple partitions from the same broker.
 */
abstract class AbstractFetcherThread(name: String,
                                     clientId: String,
                                     val sourceBroker: BrokerEndPoint,
                                     fetchBackOffMs: Int = 0,
                                     isInterruptible: Boolean = true)
  extends ShutdownableThread(name, isInterruptible) {

  type FetchData = FetchResponse.PartitionData[Records]
  type EpochData = OffsetsForLeaderEpochRequest.PartitionData

  private val partitionStates = new PartitionStates[PartitionFetchState]
  private val partitionMapLock = new ReentrantLock
  private val partitionMapCond = partitionMapLock.newCondition()

  private val metricId = ClientIdAndBroker(clientId, sourceBroker.host, sourceBroker.port)
  val fetcherStats = new FetcherStats(metricId)
  val fetcherLagStats = new FetcherLagStats(metricId)

  /* callbacks to be defined in subclass */

  // process fetched data
  //Ctrl+H，找到继承层次结构
  protected def processPartitionData(topicPartition: TopicPartition,
                                     fetchOffset: Long,
                                     partitionData: FetchData): Option[LogAppendInfo]

  protected def truncate(topicPartition: TopicPartition, truncationState: OffsetTruncationState): Unit

  protected def truncateFullyAndStartAt(topicPartition: TopicPartition, offset: Long): Unit

  protected def buildFetch(partitionMap: Map[TopicPartition, PartitionFetchState]): ResultWithPartitions[Option[FetchRequest.Builder]]

  protected def latestEpoch(topicPartition: TopicPartition): Option[Int]

  protected def logEndOffset(topicPartition: TopicPartition): Long

  protected def endOffsetForEpoch(topicPartition: TopicPartition, epoch: Int): Option[OffsetAndEpoch]

  protected def fetchEpochEndOffsets(partitions: Map[TopicPartition, EpochData]): Map[TopicPartition, EpochEndOffset]

  protected def fetchFromLeader(fetchRequest: FetchRequest.Builder): Seq[(TopicPartition, FetchData)]

  protected def fetchEarliestOffsetFromLeader(topicPartition: TopicPartition, currentLeaderEpoch: Int): Long

  protected def fetchLatestOffsetFromLeader(topicPartition: TopicPartition, currentLeaderEpoch: Int): Long

  protected def isOffsetForLeaderEpochSupported: Boolean

  override def shutdown() {
    initiateShutdown()
    inLock(partitionMapLock) {
      partitionMapCond.signalAll()
    }
    awaitShutdown()

    // we don't need the lock since the thread has finished shutdown and metric removal is safe
    fetcherStats.unregister()
    fetcherLagStats.unregister()
  }

  override def doWork() {
    maybeTruncate()
    //调用了fetch方法，里面肯定发送了网络请求给leader partition所在的服务器
    maybeFetch()
  }

  private def maybeFetch(): Unit = {
    val (fetchStates, fetchRequestOpt) = inLock(partitionMapLock) {
      val fetchStates = partitionStates.partitionStateMap.asScala
      val ResultWithPartitions(fetchRequestOpt, partitionsWithError) = buildFetch(fetchStates)

      handlePartitionsWithErrors(partitionsWithError)

      if (fetchRequestOpt.isEmpty) {
        trace(s"There are no active partitions. Back off for $fetchBackOffMs ms before sending a fetch request")
        partitionMapCond.await(fetchBackOffMs, TimeUnit.MILLISECONDS)
      }

      (fetchStates, fetchRequestOpt)
    }

    fetchRequestOpt.foreach { fetchRequest =>
      processFetchRequest(fetchStates, fetchRequest)
    }
  }

  // deal with partitions with errors, potentially due to leadership changes
  private def handlePartitionsWithErrors(partitions: Iterable[TopicPartition]) {
    if (partitions.nonEmpty)
      delayPartitions(partitions, fetchBackOffMs)
  }

  /**
   * Builds offset for leader epoch requests for partitions that are in the truncating phase based
   * on latest epochs of the future replicas (the one that is fetching)
   */
  private def fetchTruncatingPartitions(): (Map[TopicPartition, EpochData], Set[TopicPartition]) = inLock(partitionMapLock) {
    val partitionsWithEpochs = mutable.Map.empty[TopicPartition, EpochData]
    val partitionsWithoutEpochs = mutable.Set.empty[TopicPartition]

    partitionStates.stream().forEach(new Consumer[PartitionStates.PartitionState[PartitionFetchState]] {
      override def accept(state: PartitionStates.PartitionState[PartitionFetchState]): Unit = {
        if (state.value.isTruncating) {
          val tp = state.topicPartition
          latestEpoch(tp) match {
            case Some(epoch) if isOffsetForLeaderEpochSupported =>
              partitionsWithEpochs += tp -> new EpochData(Optional.of(state.value.currentLeaderEpoch), epoch)
            case _ =>
              partitionsWithoutEpochs += tp
          }
        }
      }
    })

    (partitionsWithEpochs, partitionsWithoutEpochs)
  }

  private def maybeTruncate(): Unit = {
    val (partitionsWithEpochs, partitionsWithoutEpochs) = fetchTruncatingPartitions()
    if (partitionsWithEpochs.nonEmpty) {
      truncateToEpochEndOffsets(partitionsWithEpochs)
    }
    if (partitionsWithoutEpochs.nonEmpty) {
      truncateToHighWatermark(partitionsWithoutEpochs)
    }
  }

  /**
    * - Build a leader epoch fetch based on partitions that are in the Truncating phase
    * - Send OffsetsForLeaderEpochRequest, retrieving the latest offset for each partition's
    *   leader epoch. This is the offset the follower should truncate to ensure
    *   accurate log replication.
    * - Finally truncate the logs for partitions in the truncating phase and mark them
    *   truncation complete. Do this within a lock to ensure no leadership changes can
    *   occur during truncation.
    */
  private def truncateToEpochEndOffsets(latestEpochsForPartitions: Map[TopicPartition, EpochData]): Unit = {
    val endOffsets = fetchEpochEndOffsets(latestEpochsForPartitions)
    //Ensure we hold a lock during truncation.
    inLock(partitionMapLock) {
      //Check no leadership and no leader epoch changes happened whilst we were unlocked, fetching epochs
      val epochEndOffsets = endOffsets.filter { case (tp, _) =>
        val curPartitionState = partitionStates.stateValue(tp)
        val partitionEpochRequest = latestEpochsForPartitions.get(tp).getOrElse {
          throw new IllegalStateException(
            s"Leader replied with partition $tp not requested in OffsetsForLeaderEpoch request")
        }
        val leaderEpochInRequest = partitionEpochRequest.currentLeaderEpoch.get
        curPartitionState != null && leaderEpochInRequest == curPartitionState.currentLeaderEpoch
      }

      val ResultWithPartitions(fetchOffsets, partitionsWithError) = maybeTruncateToEpochEndOffsets(epochEndOffsets)
      handlePartitionsWithErrors(partitionsWithError)
      updateFetchOffsetAndMaybeMarkTruncationComplete(fetchOffsets)
    }
  }

  // Visible for testing
  private[server] def truncateToHighWatermark(partitions: Set[TopicPartition]): Unit = inLock(partitionMapLock) {
    val fetchOffsets = mutable.HashMap.empty[TopicPartition, OffsetTruncationState]
    val partitionsWithError = mutable.HashSet.empty[TopicPartition]

    for (tp <- partitions) {
      val partitionState = partitionStates.stateValue(tp)
      if (partitionState != null) {
        try {
          val highWatermark = partitionState.fetchOffset
          val truncationState = OffsetTruncationState(highWatermark, truncationCompleted = true)

          info(s"Truncating partition $tp to local high watermark $highWatermark")
          truncate(tp, truncationState)

          fetchOffsets.put(tp, truncationState)
        } catch {
          case e: KafkaStorageException =>
            info(s"Failed to truncate $tp", e)
            partitionsWithError += tp
        }
      }
    }

    handlePartitionsWithErrors(partitionsWithError)
    updateFetchOffsetAndMaybeMarkTruncationComplete(fetchOffsets)
  }

  private def maybeTruncateToEpochEndOffsets(fetchedEpochs: Map[TopicPartition, EpochEndOffset]): ResultWithPartitions[Map[TopicPartition, OffsetTruncationState]] = {
    val fetchOffsets = mutable.HashMap.empty[TopicPartition, OffsetTruncationState]
    val partitionsWithError = mutable.HashSet.empty[TopicPartition]

    fetchedEpochs.foreach { case (tp, leaderEpochOffset) =>
      try {
        leaderEpochOffset.error match {
          case Errors.NONE =>
            val offsetTruncationState = getOffsetTruncationState(tp, leaderEpochOffset)
            truncate(tp, offsetTruncationState)
            fetchOffsets.put(tp, offsetTruncationState)

          case Errors.FENCED_LEADER_EPOCH =>
            onPartitionFenced(tp)

          case error =>
            info(s"Retrying leaderEpoch request for partition $tp as the leader reported an error: $error")
            partitionsWithError += tp
        }
      } catch {
        case e: KafkaStorageException =>
          info(s"Failed to truncate $tp", e)
          partitionsWithError += tp
      }
    }

    ResultWithPartitions(fetchOffsets, partitionsWithError)
  }

  private def onPartitionFenced(tp: TopicPartition): Unit = inLock(partitionMapLock) {
    Option(partitionStates.stateValue(tp)).foreach { currentFetchState =>
      val currentLeaderEpoch = currentFetchState.currentLeaderEpoch
      info(s"Partition $tp has an older epoch ($currentLeaderEpoch) than the current leader. Will await " +
        s"the new LeaderAndIsr state before resuming fetching.")
      partitionStates.remove(tp)
    }
  }

  private def processFetchRequest(fetchStates: Map[TopicPartition, PartitionFetchState],
                                  fetchRequest: FetchRequest.Builder): Unit = {
    val partitionsWithError = mutable.Set[TopicPartition]()
    var responseData: Seq[(TopicPartition, FetchData)] = Seq.empty

    try {
      trace(s"Sending fetch request $fetchRequest")
      responseData = fetchFromLeader(fetchRequest)
    } catch {
      case t: Throwable =>
        if (isRunning) {
          warn(s"Error in response for fetch request $fetchRequest", t)
          inLock(partitionMapLock) {
            partitionsWithError ++= partitionStates.partitionSet.asScala
            // there is an error occurred while fetching partitions, sleep a while
            // note that `ReplicaFetcherThread.handlePartitionsWithError` will also introduce the same delay for every
            // partition with error effectively doubling the delay. It would be good to improve this.
            partitionMapCond.await(fetchBackOffMs, TimeUnit.MILLISECONDS)
          }
        }
    }
    fetcherStats.requestRate.mark()

    if (responseData.nonEmpty) {
      // process fetched data
      inLock(partitionMapLock) {
        //对结果响应的处理
        responseData.foreach { case (topicPartition, partitionData) =>
          Option(partitionStates.stateValue(topicPartition)).foreach { currentFetchState =>
            // It's possible that a partition is removed and re-added or truncated when there is a pending fetch request.
            // In this case, we only want to process the fetch response if the partition state is ready for fetch and
            // the current offset is the same as the offset requested.
            val fetchState = fetchStates(topicPartition)
            if (fetchState.fetchOffset == currentFetchState.fetchOffset && currentFetchState.isReadyForFetch) {
              partitionData.error match {
                case Errors.NONE =>
                  try {
                    // Once we hand off the partition data to the subclass, we can't mess with it any more in this thread
                    //TODO 核心代码，处理拉取到的数据
                    val logAppendInfoOpt = processPartitionData(topicPartition, currentFetchState.fetchOffset,
                      partitionData)

                    logAppendInfoOpt.foreach { logAppendInfo =>
                      val validBytes = logAppendInfo.validBytes
                      val nextOffset = if (validBytes > 0) logAppendInfo.lastOffset + 1 else currentFetchState.fetchOffset
                      fetcherLagStats.getAndMaybePut(topicPartition).lag = Math.max(0L, partitionData.highWatermark - nextOffset)

                      // ReplicaDirAlterThread may have removed topicPartition from the partitionStates after processing the partition data
                      if (validBytes > 0 && partitionStates.contains(topicPartition)) {
                        // Update partitionStates only if there is no exception during processPartitionData
                        val newFetchState = PartitionFetchState(nextOffset, fetchState.currentLeaderEpoch,
                          state = Fetching)
                        partitionStates.updateAndMoveToEnd(topicPartition, newFetchState)
                        fetcherStats.byteRate.mark(validBytes)
                      }
                    }
                  } catch {
                    case ime: CorruptRecordException =>
                      // we log the error and continue. This ensures two things
                      // 1. If there is a corrupt message in a topic partition, it does not bring the fetcher thread
                      //    down and cause other topic partition to also lag
                      // 2. If the message is corrupt due to a transient state in the log (truncation, partial writes
                      //    can cause this), we simply continue and should get fixed in the subsequent fetches
                      error(s"Found invalid messages during fetch for partition $topicPartition " +
                        s"offset ${currentFetchState.fetchOffset}", ime)
                      partitionsWithError += topicPartition
                    case e: KafkaStorageException =>
                      error(s"Error while processing data for partition $topicPartition", e)
                      partitionsWithError += topicPartition
                    case e: Throwable =>
                      throw new KafkaException(s"Error processing data for partition $topicPartition " +
                        s"offset ${currentFetchState.fetchOffset}", e)
                  }
                case Errors.OFFSET_OUT_OF_RANGE =>
                  if (!handleOutOfRangeError(topicPartition, currentFetchState))
                    partitionsWithError += topicPartition

                case Errors.UNKNOWN_LEADER_EPOCH =>
                  debug(s"Remote broker has a smaller leader epoch for partition $topicPartition than " +
                    s"this replica's current leader epoch of ${fetchState.currentLeaderEpoch}.")
                  partitionsWithError += topicPartition

                case Errors.FENCED_LEADER_EPOCH =>
                  onPartitionFenced(topicPartition)

                case Errors.NOT_LEADER_FOR_PARTITION =>
                  debug(s"Remote broker is not the leader for partition $topicPartition, which could indicate " +
                    "that the partition is being moved")
                  partitionsWithError += topicPartition

                case _ =>
                  error(s"Error for partition $topicPartition at offset ${currentFetchState.fetchOffset}",
                    partitionData.error.exception)
                  partitionsWithError += topicPartition
              }
            }
          }
        }
      }
    }

    if (partitionsWithError.nonEmpty) {
      debug(s"Handling errors for partitions $partitionsWithError")
      handlePartitionsWithErrors(partitionsWithError)
    }
  }

  def markPartitionsForTruncation(topicPartition: TopicPartition, truncationOffset: Long) {
    partitionMapLock.lockInterruptibly()
    try {
      Option(partitionStates.stateValue(topicPartition)).foreach { state =>
        val newState = PartitionFetchState(math.min(truncationOffset, state.fetchOffset),
          state.currentLeaderEpoch, state.delay, state = Truncating)
        partitionStates.updateAndMoveToEnd(topicPartition, newState)
        partitionMapCond.signalAll()
      }
    } finally partitionMapLock.unlock()
  }

  def addPartitions(initialFetchStates: Map[TopicPartition, OffsetAndEpoch]) {
    partitionMapLock.lockInterruptibly()
    try {
      initialFetchStates.foreach { case (tp, initialFetchState) =>
        // We can skip the truncation step iff the leader epoch matches the existing epoch
        val currentState = partitionStates.stateValue(tp)
        val updatedState = if (currentState != null && currentState.currentLeaderEpoch == initialFetchState.leaderEpoch) {
          currentState
        } else {
          val initialFetchOffset = if (initialFetchState.offset < 0)
            fetchOffsetAndTruncate(tp, initialFetchState.leaderEpoch)
          else
            initialFetchState.offset
          PartitionFetchState(initialFetchOffset, initialFetchState.leaderEpoch, state = Truncating)
        }
        partitionStates.updateAndMoveToEnd(tp, updatedState)
      }

      partitionMapCond.signalAll()
    } finally partitionMapLock.unlock()
  }

  /**
    * Loop through all partitions, updating their fetch offset and maybe marking them as
    * truncation completed if their offsetTruncationState indicates truncation completed
    *
    * @param fetchOffsets the partitions to update fetch offset and maybe mark truncation complete
    */
  private def updateFetchOffsetAndMaybeMarkTruncationComplete(fetchOffsets: Map[TopicPartition, OffsetTruncationState]) {
    val newStates: Map[TopicPartition, PartitionFetchState] = partitionStates.partitionStates.asScala
      .map { state =>
        val currentFetchState = state.value
        val maybeTruncationComplete = fetchOffsets.get(state.topicPartition) match {
          case Some(offsetTruncationState) =>
            val state = if (offsetTruncationState.truncationCompleted) Fetching else Truncating
            PartitionFetchState(offsetTruncationState.offset, currentFetchState.currentLeaderEpoch,
              currentFetchState.delay, state)
          case None => currentFetchState
        }
        (state.topicPartition, maybeTruncationComplete)
      }.toMap
    partitionStates.set(newStates.asJava)
  }

  /**
   * Called from ReplicaFetcherThread and ReplicaAlterLogDirsThread maybeTruncate for each topic
   * partition. Returns truncation offset and whether this is the final offset to truncate to
   *
   * For each topic partition, the offset to truncate to is calculated based on leader's returned
   * epoch and offset:
   *  -- If the leader replied with undefined epoch offset, we must use the high watermark. This can
   *  happen if 1) the leader is still using message format older than KAFKA_0_11_0; 2) the follower
   *  requested leader epoch < the first leader epoch known to the leader.
   *  -- If the leader replied with the valid offset but undefined leader epoch, we truncate to
   *  leader's offset if it is lower than follower's Log End Offset. This may happen if the
   *  leader is on the inter-broker protocol version < KAFKA_2_0_IV0
   *  -- If the leader replied with leader epoch not known to the follower, we truncate to the
   *  end offset of the largest epoch that is smaller than the epoch the leader replied with, and
   *  send OffsetsForLeaderEpochRequest with that leader epoch. In a more rare case, where the
   *  follower was not tracking epochs smaller than the epoch the leader replied with, we
   *  truncate the leader's offset (and do not send any more leader epoch requests).
   *  -- Otherwise, truncate to min(leader's offset, end offset on the follower for epoch that
   *  leader replied with, follower's Log End Offset).
   *
   * @param tp                    Topic partition
   * @param leaderEpochOffset     Epoch end offset received from the leader for this topic partition
   */
  private def getOffsetTruncationState(tp: TopicPartition,
                                       leaderEpochOffset: EpochEndOffset): OffsetTruncationState = inLock(partitionMapLock) {
    if (leaderEpochOffset.endOffset == UNDEFINED_EPOCH_OFFSET) {
      // truncate to initial offset which is the high watermark for follower replica. For
      // future replica, it is either high watermark of the future replica or current
      // replica's truncation offset (when the current replica truncates, it forces future
      // replica's partition state to 'truncating' and sets initial offset to its truncation offset)
      warn(s"Based on replica's leader epoch, leader replied with an unknown offset in $tp. " +
           s"The initial fetch offset ${partitionStates.stateValue(tp).fetchOffset} will be used for truncation.")
      OffsetTruncationState(partitionStates.stateValue(tp).fetchOffset, truncationCompleted = true)
    } else if (leaderEpochOffset.leaderEpoch == UNDEFINED_EPOCH) {
      // either leader or follower or both use inter-broker protocol version < KAFKA_2_0_IV0
      // (version 0 of OffsetForLeaderEpoch request/response)
      warn(s"Leader or replica is on protocol version where leader epoch is not considered in the OffsetsForLeaderEpoch response. " +
           s"The leader's offset ${leaderEpochOffset.endOffset} will be used for truncation in $tp.")
      OffsetTruncationState(min(leaderEpochOffset.endOffset, logEndOffset(tp)), truncationCompleted = true)
    } else {
      val replicaEndOffset = logEndOffset(tp)

      // get (leader epoch, end offset) pair that corresponds to the largest leader epoch
      // less than or equal to the requested epoch.
      endOffsetForEpoch(tp, leaderEpochOffset.leaderEpoch) match {
        case Some(OffsetAndEpoch(followerEndOffset, followerEpoch)) =>
          if (followerEpoch != leaderEpochOffset.leaderEpoch) {
            // the follower does not know about the epoch that leader replied with
            // we truncate to the end offset of the largest epoch that is smaller than the
            // epoch the leader replied with, and send another offset for leader epoch request
            val intermediateOffsetToTruncateTo = min(followerEndOffset, replicaEndOffset)
            info(s"Based on replica's leader epoch, leader replied with epoch ${leaderEpochOffset.leaderEpoch} " +
              s"unknown to the replica for $tp. " +
              s"Will truncate to $intermediateOffsetToTruncateTo and send another leader epoch request to the leader.")
            OffsetTruncationState(intermediateOffsetToTruncateTo, truncationCompleted = false)
          } else {
            val offsetToTruncateTo = min(followerEndOffset, leaderEpochOffset.endOffset)
            OffsetTruncationState(min(offsetToTruncateTo, replicaEndOffset), truncationCompleted = true)
          }
        case None =>
          // This can happen if the follower was not tracking leader epochs at that point (before the
          // upgrade, or if this broker is new). Since the leader replied with epoch <
          // requested epoch from follower, so should be safe to truncate to leader's
          // offset (this is the same behavior as post-KIP-101 and pre-KIP-279)
          warn(s"Based on replica's leader epoch, leader replied with epoch ${leaderEpochOffset.leaderEpoch} " +
            s"below any replica's tracked epochs for $tp. " +
            s"The leader's offset only ${leaderEpochOffset.endOffset} will be used for truncation.")
          OffsetTruncationState(min(leaderEpochOffset.endOffset, replicaEndOffset), truncationCompleted = true)
      }
    }
  }

  /**
   * Handle the out of range error. Return true if the request succeeded or was fenced, which means we need
   * not backoff and retry. False if there was a retriable error.
   */
  private def handleOutOfRangeError(topicPartition: TopicPartition,
                                    fetchState: PartitionFetchState): Boolean = {
    try {
      val newOffset = fetchOffsetAndTruncate(topicPartition, fetchState.currentLeaderEpoch)
      val newFetchState = PartitionFetchState(newOffset, fetchState.currentLeaderEpoch, state = Fetching)
      partitionStates.updateAndMoveToEnd(topicPartition, newFetchState)
      info(s"Current offset ${fetchState.fetchOffset} for partition $topicPartition is " +
        s"out of range, which typically implies a leader change. Reset fetch offset to $newOffset")
      true
    } catch {
      case _: FencedLeaderEpochException =>
        onPartitionFenced(topicPartition)
        true

      case e @ (_ : UnknownTopicOrPartitionException |
                _ : UnknownLeaderEpochException |
                _ : NotLeaderForPartitionException) =>
        info(s"Could not fetch offset for $topicPartition due to error: ${e.getMessage}")
        false

      case e: Throwable =>
        error(s"Error getting offset for partition $topicPartition", e)
        false
    }
  }

  /**
   * Handle a partition whose offset is out of range and return a new fetch offset.
   */
  protected def fetchOffsetAndTruncate(topicPartition: TopicPartition, currentLeaderEpoch: Int): Long = {
    val replicaEndOffset = logEndOffset(topicPartition)

    /**
     * Unclean leader election: A follower goes down, in the meanwhile the leader keeps appending messages. The follower comes back up
     * and before it has completely caught up with the leader's logs, all replicas in the ISR go down. The follower is now uncleanly
     * elected as the new leader, and it starts appending messages from the client. The old leader comes back up, becomes a follower
     * and it may discover that the current leader's end offset is behind its own end offset.
     *
     * In such a case, truncate the current follower's log to the current leader's end offset and continue fetching.
     *
     * There is a potential for a mismatch between the logs of the two replicas here. We don't fix this mismatch as of now.
     */
    val leaderEndOffset = fetchLatestOffsetFromLeader(topicPartition, currentLeaderEpoch)
    if (leaderEndOffset < replicaEndOffset) {
      warn(s"Reset fetch offset for partition $topicPartition from $replicaEndOffset to current " +
        s"leader's latest offset $leaderEndOffset")
      truncate(topicPartition, OffsetTruncationState(leaderEndOffset, truncationCompleted = true))
      leaderEndOffset
    } else {
      /**
       * If the leader's log end offset is greater than the follower's log end offset, there are two possibilities:
       * 1. The follower could have been down for a long time and when it starts up, its end offset could be smaller than the leader's
       * start offset because the leader has deleted old logs (log.logEndOffset < leaderStartOffset).
       * 2. When unclean leader election occurs, it is possible that the old leader's high watermark is greater than
       * the new leader's log end offset. So when the old leader truncates its offset to its high watermark and starts
       * to fetch from the new leader, an OffsetOutOfRangeException will be thrown. After that some more messages are
       * produced to the new leader. While the old leader is trying to handle the OffsetOutOfRangeException and query
       * the log end offset of the new leader, the new leader's log end offset becomes higher than the follower's log end offset.
       *
       * In the first case, the follower's current log end offset is smaller than the leader's log start offset. So the
       * follower should truncate all its logs, roll out a new segment and start to fetch from the current leader's log
       * start offset.
       * In the second case, the follower should just keep the current log segments and retry the fetch. In the second
       * case, there will be some inconsistency of data between old and new leader. We are not solving it here.
       * If users want to have strong consistency guarantees, appropriate configurations needs to be set for both
       * brokers and producers.
       *
       * Putting the two cases together, the follower should fetch from the higher one of its replica log end offset
       * and the current leader's log start offset.
       */
      val leaderStartOffset = fetchEarliestOffsetFromLeader(topicPartition, currentLeaderEpoch)
      warn(s"Reset fetch offset for partition $topicPartition from $replicaEndOffset to current " +
        s"leader's start offset $leaderStartOffset")
      val offsetToFetch = Math.max(leaderStartOffset, replicaEndOffset)
      // Only truncate log when current leader's log start offset is greater than follower's log end offset.
      if (leaderStartOffset > replicaEndOffset)
        truncateFullyAndStartAt(topicPartition, leaderStartOffset)
      offsetToFetch
    }
  }

  def delayPartitions(partitions: Iterable[TopicPartition], delay: Long) {
    partitionMapLock.lockInterruptibly()
    try {
      for (partition <- partitions) {
        Option(partitionStates.stateValue(partition)).foreach { currentFetchState =>
          if (!currentFetchState.isDelayed) {
            partitionStates.updateAndMoveToEnd(partition, PartitionFetchState(currentFetchState.fetchOffset,
              currentFetchState.currentLeaderEpoch, new DelayedItem(delay), currentFetchState.state))
          }
        }
      }
      partitionMapCond.signalAll()
    } finally partitionMapLock.unlock()
  }

  def removePartitions(topicPartitions: Set[TopicPartition]) {
    partitionMapLock.lockInterruptibly()
    try {
      topicPartitions.foreach { topicPartition =>
        partitionStates.remove(topicPartition)
        fetcherLagStats.unregister(topicPartition)
      }
    } finally partitionMapLock.unlock()
  }

  def partitionCount() = {
    partitionMapLock.lockInterruptibly()
    try partitionStates.size
    finally partitionMapLock.unlock()
  }

  // Visible for testing
  private[server] def fetchState(topicPartition: TopicPartition): Option[PartitionFetchState] = inLock(partitionMapLock) {
    Option(partitionStates.stateValue(topicPartition))
  }

  private[server] def partitionsAndOffsets: Map[TopicPartition, InitialFetchState] = inLock(partitionMapLock) {
    partitionStates.partitionStates.asScala.map { state =>
      val initialFetchState = InitialFetchState(sourceBroker,
        currentLeaderEpoch = state.value.currentLeaderEpoch,
        initOffset = state.value.fetchOffset)
      state.topicPartition -> initialFetchState
    }.toMap
  }

  protected def toMemoryRecords(records: Records): MemoryRecords = {
    records match {
      case r: MemoryRecords => r
      case r: FileRecords =>
        val buffer = ByteBuffer.allocate(r.sizeInBytes)
        r.readInto(buffer, 0)
        MemoryRecords.readableRecords(buffer)
    }
  }

}

object AbstractFetcherThread {

  case class ResultWithPartitions[R](result: R, partitionsWithError: Set[TopicPartition])

}

object FetcherMetrics {
  val ConsumerLag = "ConsumerLag"
  val RequestsPerSec = "RequestsPerSec"
  val BytesPerSec = "BytesPerSec"
}

class FetcherLagMetrics(metricId: ClientIdTopicPartition) extends KafkaMetricsGroup {

  private[this] val lagVal = new AtomicLong(-1L)
  private[this] val tags = Map(
    "clientId" -> metricId.clientId,
    "topic" -> metricId.topicPartition.topic,
    "partition" -> metricId.topicPartition.partition.toString)

  newGauge(FetcherMetrics.ConsumerLag,
    new Gauge[Long] {
      def value = lagVal.get
    },
    tags
  )

  def lag_=(newLag: Long) {
    lagVal.set(newLag)
  }

  def lag = lagVal.get

  def unregister() {
    removeMetric(FetcherMetrics.ConsumerLag, tags)
  }
}

class FetcherLagStats(metricId: ClientIdAndBroker) {
  private val valueFactory = (k: ClientIdTopicPartition) => new FetcherLagMetrics(k)
  val stats = new Pool[ClientIdTopicPartition, FetcherLagMetrics](Some(valueFactory))

  def getAndMaybePut(topicPartition: TopicPartition): FetcherLagMetrics = {
    stats.getAndMaybePut(ClientIdTopicPartition(metricId.clientId, topicPartition))
  }

  def isReplicaInSync(topicPartition: TopicPartition): Boolean = {
    val fetcherLagMetrics = stats.get(ClientIdTopicPartition(metricId.clientId, topicPartition))
    if (fetcherLagMetrics != null)
      fetcherLagMetrics.lag <= 0
    else
      false
  }

  def unregister(topicPartition: TopicPartition) {
    val lagMetrics = stats.remove(ClientIdTopicPartition(metricId.clientId, topicPartition))
    if (lagMetrics != null) lagMetrics.unregister()
  }

  def unregister() {
    stats.keys.toBuffer.foreach { key: ClientIdTopicPartition =>
      unregister(key.topicPartition)
    }
  }
}

class FetcherStats(metricId: ClientIdAndBroker) extends KafkaMetricsGroup {
  val tags = Map("clientId" -> metricId.clientId,
    "brokerHost" -> metricId.brokerHost,
    "brokerPort" -> metricId.brokerPort.toString)

  val requestRate = newMeter(FetcherMetrics.RequestsPerSec, "requests", TimeUnit.SECONDS, tags)

  val byteRate = newMeter(FetcherMetrics.BytesPerSec, "bytes", TimeUnit.SECONDS, tags)

  def unregister() {
    removeMetric(FetcherMetrics.RequestsPerSec, tags)
    removeMetric(FetcherMetrics.BytesPerSec, tags)
  }

}

case class ClientIdTopicPartition(clientId: String, topicPartition: TopicPartition) {
  override def toString: String = s"$clientId-$topicPartition"
}

sealed trait ReplicaState
case object Truncating extends ReplicaState
case object Fetching extends ReplicaState

object PartitionFetchState {
  def apply(offset: Long, currentLeaderEpoch: Int, state: ReplicaState): PartitionFetchState = {
    PartitionFetchState(offset, currentLeaderEpoch, new DelayedItem(0), state)
  }
}


/**
 * case class to keep partition offset and its state(truncatingLog, delayed)
 * This represents a partition as being either:
 * (1) Truncating its log, for example having recently become a follower
 * (2) Delayed, for example due to an error, where we subsequently back off a bit
 * (3) ReadyForFetch, the is the active state where the thread is actively fetching data.
 */
case class PartitionFetchState(fetchOffset: Long,
                               currentLeaderEpoch: Int,
                               delay: DelayedItem,
                               state: ReplicaState) {

  def isReadyForFetch: Boolean = state == Fetching && !isDelayed

  def isTruncating: Boolean = state == Truncating && !isDelayed

  def isDelayed: Boolean = delay.getDelay(TimeUnit.MILLISECONDS) > 0

  override def toString: String = {
    s"FetchState(fetchOffset=$fetchOffset" +
      s", currentLeaderEpoch=$currentLeaderEpoch" +
      s", state=$state" +
      s", delay=${delay.delayMs}ms" +
      s")"
  }
}

case class OffsetTruncationState(offset: Long, truncationCompleted: Boolean) {

  def this(offset: Long) = this(offset, true)

  override def toString = "offset:%d-truncationCompleted:%b".format(offset, truncationCompleted)
}

case class OffsetAndEpoch(offset: Long, leaderEpoch: Int) {
  override def toString: String = {
    s"(offset=$offset, leaderEpoch=$leaderEpoch)"
  }
}
