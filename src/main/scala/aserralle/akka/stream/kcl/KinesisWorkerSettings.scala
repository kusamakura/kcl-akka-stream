/*
 * Copyright (C) 2018 Albert Serrallé
 */

package aserralle.akka.stream.kcl

import scala.concurrent.duration._

case class KinesisWorkerSourceSettings(
    bufferSize: Int,
    terminateStreamGracePeriod: FiniteDuration) {
  require(
    bufferSize >= 1,
    "Buffer size must be greater than 0; use size 1 to disable stage buffering"
  )
}
case class KinesisWorkerCheckpointSettings(maxBatchSize: Int,
                                           maxBatchWait: FiniteDuration) {
  require(
    maxBatchSize >= 1,
    "Batch size must be greater than 0; use size 1 to commit records one at a time"
  )
}

object KinesisWorkerSourceSettings {

  val defaultInstance = KinesisWorkerSourceSettings(1000, 1.minute)

  /**
    * Java API
    */
  def create(bufferSize: Int, terminateStreamGracePeriod: FiniteDuration)
    : KinesisWorkerSourceSettings =
    KinesisWorkerSourceSettings(bufferSize, terminateStreamGracePeriod)

}

object KinesisWorkerCheckpointSettings {

  val defaultInstance = KinesisWorkerCheckpointSettings(1000, 10.seconds)

  /**
    * Java API
    */
  def create(maxBatchSize: Int,
             maxBatchWait: FiniteDuration): KinesisWorkerCheckpointSettings =
    KinesisWorkerCheckpointSettings(maxBatchSize, maxBatchWait)

}
