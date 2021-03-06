/*
 * Copyright (C) 2018 Albert Serrallé
 */

package aserralle.akka.stream.kcl

import java.nio.ByteBuffer
import java.util.Date
import java.util.concurrent.Semaphore

import akka.stream.KillSwitches
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import aserralle.akka.stream.kcl.Errors.WorkerUnexpectedShutdown
import aserralle.akka.stream.kcl.scaladsl.KinesisWorkerSource
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessorFactory
import com.amazonaws.services.kinesis.clientlibrary.interfaces.{
  IRecordProcessorCheckpointer,
  v2
}
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.{
  ShutdownReason,
  Worker
}
import com.amazonaws.services.kinesis.clientlibrary.types.{
  ExtendedSequenceNumber,
  InitializationInput,
  ProcessRecordsInput,
  ShutdownInput
}
import com.amazonaws.services.kinesis.model.Record
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.Eventually
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class KinesisWorkerSourceSourceSpec
    extends WordSpecLike
    with Matchers
    with DefaultTestContext
    with Eventually {

  "KinesisWorker Source" must {

    "publish records downstream" in new KinesisWorkerContext with TestData {
      recordProcessor.initialize(initializationInput)
      recordProcessor.processRecords(recordsInput)

      val producedRecord = sinkProbe.requestNext()
      producedRecord.recordProcessorStartingSequenceNumber shouldBe initializationInput.getExtendedSequenceNumber
      producedRecord.shardId shouldBe initializationInput.getShardId
      producedRecord.millisBehindLatest shouldBe recordsInput.getMillisBehindLatest
      producedRecord.record shouldBe record

      killSwitch.shutdown()

      sinkProbe.expectComplete()
    }

    "publish records downstream using different IRecordProcessor incarnations" in new KinesisWorkerContext
    with TestData {
      recordProcessor.initialize(initializationInput)
      recordProcessor.processRecords(recordsInput)

      var producedRecord = sinkProbe.requestNext()
      producedRecord.recordProcessorStartingSequenceNumber shouldBe initializationInput.getExtendedSequenceNumber
      producedRecord.shardId shouldBe initializationInput.getShardId
      producedRecord.millisBehindLatest shouldBe recordsInput.getMillisBehindLatest
      producedRecord.record shouldBe record

      val newRecordProcessor = recordProcessorFactory.createProcessor()

      newRecordProcessor.initialize(initializationInput)
      newRecordProcessor.processRecords(recordsInput)

      producedRecord = sinkProbe.requestNext()
      producedRecord.recordProcessorStartingSequenceNumber shouldBe initializationInput.getExtendedSequenceNumber
      producedRecord.shardId shouldBe initializationInput.getShardId
      producedRecord.millisBehindLatest shouldBe recordsInput.getMillisBehindLatest
      producedRecord.record shouldBe record

      killSwitch.shutdown()

      sinkProbe.expectComplete()
    }

    "call Worker shutdown on stage completion" in new KinesisWorkerContext {
      killSwitch.shutdown()

      sinkProbe.expectComplete()
      eventually {
        verify(worker).run()
        verify(worker).shutdown()
      }
    }

    "complete the stage if the Worker is shutdown" in new KinesisWorkerContext {
      lock.release()
      sinkProbe.expectComplete()
      eventually {
        verify(worker).run()
      }
    }

    "complete the stage with error if the Worker fails" in new KinesisWorkerContext(
      Some(WorkerUnexpectedShutdown(new RuntimeException()))) {
      sinkProbe.expectError() shouldBe a[WorkerUnexpectedShutdown]
      eventually {
        verify(worker).run()
      }
    }
  }

  private abstract class KinesisWorkerContext(
      workerFailure: Option[Throwable] = None) {
    protected val worker = org.mockito.Mockito.mock(classOf[Worker])
    val lock = new Semaphore(0)
    when(worker.run()).then(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit =
        workerFailure.fold(lock.acquire())(throw _)
    })

    val semaphore = new Semaphore(0)

    var recordProcessorFactory: IRecordProcessorFactory = _
    var recordProcessor: v2.IRecordProcessor = _
    val workerBuilder = { x: IRecordProcessorFactory =>
      recordProcessorFactory = x
      recordProcessor = x.createProcessor()
      semaphore.release()
      worker
    }
    val ((killSwitch, watch), sinkProbe) =
      KinesisWorkerSource(
        workerBuilder,
        KinesisWorkerSourceSettings(bufferSize = 10,
                                    terminateStreamGracePeriod = 1 second))
        .viaMat(KillSwitches.single)(Keep.right)
        .watchTermination()(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

    watch.onComplete(_ => lock.release())

    sinkProbe.ensureSubscription()
    sinkProbe.request(1)

    semaphore.acquire()
  }

  private trait TestData {
    protected val checkpointer =
      org.mockito.Mockito.mock(classOf[IRecordProcessorCheckpointer])

    val initializationInput =
      new InitializationInput()
        .withShardId("shardId")
        .withExtendedSequenceNumber(ExtendedSequenceNumber.AT_TIMESTAMP)
    val record =
      new Record()
        .withApproximateArrivalTimestamp(new Date())
        .withEncryptionType("encryption")
        .withPartitionKey("partitionKey")
        .withSequenceNumber("sequenceNum")
        .withData(ByteBuffer.wrap(Array[Byte](1)))
    val recordsInput =
      new ProcessRecordsInput()
        .withCheckpointer(checkpointer)
        .withMillisBehindLatest(1L)
        .withRecords(List(record).asJava)
  }

  "KinesisWorker checkpoint Flow " must {

    "checkpoint batch of records of different shards" in new KinesisWorkerCheckpointContext {
      val recordProcessor = new IRecordProcessor(_ => (), 1 second)

      val checkpointerShard1 =
        org.mockito.Mockito.mock(classOf[IRecordProcessorCheckpointer])
      var latestRecordShard1: Record = _
      for (i <- 1 to 3) {
        val record = org.mockito.Mockito.mock(classOf[Record])
        when(record.getSequenceNumber).thenReturn(i.toString)
        sourceProbe.sendNext(
          new CommittableRecord(
            "shard-1",
            org.mockito.Mockito.mock(classOf[ExtendedSequenceNumber]),
            1L,
            record,
            recordProcessor,
            checkpointerShard1
          )
        )
        latestRecordShard1 = record
      }
      val checkpointerShard2 =
        org.mockito.Mockito.mock(classOf[IRecordProcessorCheckpointer])
      var latestRecordShard2: Record = _
      for (i <- 1 to 3) {
        val record = org.mockito.Mockito.mock(classOf[Record])
        when(record.getSequenceNumber).thenReturn(i.toString)
        sourceProbe.sendNext(
          new CommittableRecord(
            "shard-2",
            org.mockito.Mockito.mock(classOf[ExtendedSequenceNumber]),
            1L,
            record,
            recordProcessor,
            checkpointerShard2
          )
        )
        latestRecordShard2 = record
      }

      for (_ <- 1 to 6) sinkProbe.requestNext()

      eventually {
        verify(checkpointerShard1).checkpoint(latestRecordShard1)
        verify(checkpointerShard2).checkpoint(latestRecordShard2)
      }

      sourceProbe.sendComplete()
      sinkProbe.expectComplete()
    }

    "not checkpoint the batch if the IRecordProcessor has been shutdown" in new KinesisWorkerCheckpointContext {
      val recordProcessor = new IRecordProcessor(_ => (), 1 second)
      recordProcessor.shutdown(
        new ShutdownInput().withShutdownReason(ShutdownReason.TERMINATE))
      val record = org.mockito.Mockito.mock(classOf[Record])
      when(record.getSequenceNumber).thenReturn("1")
      val committableRecord = new CommittableRecord(
        "shard-1",
        org.mockito.Mockito.mock(classOf[ExtendedSequenceNumber]),
        1L,
        record,
        recordProcessor,
        org.mockito.Mockito.mock(classOf[IRecordProcessorCheckpointer])
      )
      sourceProbe.sendNext(committableRecord)

      sinkProbe.requestNext()

      sourceProbe.sendComplete()
      sinkProbe.expectComplete()
    }

    "fail with Exception if checkpoint action fails" in new KinesisWorkerCheckpointContext {
      val recordProcessor = new IRecordProcessor(_ => (), 1 second)
      val record = org.mockito.Mockito.mock(classOf[Record])
      when(record.getSequenceNumber).thenReturn("1")
      val checkpointer =
        org.mockito.Mockito.mock(classOf[IRecordProcessorCheckpointer])
      val committableRecord = new CommittableRecord(
        "shard-1",
        org.mockito.Mockito.mock(classOf[ExtendedSequenceNumber]),
        1L,
        record,
        recordProcessor,
        checkpointer
      )
      sourceProbe.sendNext(committableRecord)

      val failure = new RuntimeException()
      when(checkpointer.checkpoint(record)).thenThrow(failure)

      sinkProbe.request(1)

      sinkProbe.expectError(failure)
    }

  }

  private trait KinesisWorkerCheckpointContext {
    val (sourceProbe, sinkProbe) =
      TestSource
        .probe[CommittableRecord]
        .via(
          KinesisWorkerSource
            .checkpointRecordsFlow(
              KinesisWorkerCheckpointSettings(maxBatchSize = 100,
                                              maxBatchWait = 500 millis))
        )
        .toMat(TestSink.probe)(Keep.both)
        .run()
  }

}
