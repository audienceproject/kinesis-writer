package com.audienceproject.aws.kinesis


import com.amazonaws.kinesis.agg.AggRecord
import com.audienceproject.BuildInfo
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.StringUtils
import org.apache.logging.log4j.{LogManager, Logger}
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Random

class KinesisWriter {

  val logger: Logger = LogManager.getLogger(this.getClass.getName)
  logger.info(s"Using ${BuildInfo.name} version ${BuildInfo.version} built at ${BuildInfo.buildDate}")

  /**
    * The maximum number of linear back-off retries before giving up and throwing an exception
    */
  val maximumRetries = 30

  /**
    * The maximum size in bytes of the last User Record that we can add to the Stream Record.
    * This is very empiric becuase we don't have access to the size function of the aggregated record.
    */
  val maximumLastSize = 100000

  /**
    * The maximum size of the aggregated record in bytes before the last record is added. Look above for explanation.
    */
  val maximumSize = 1000000

  private val RANDOM = new Random(42)

  def getExplicitHashKey(ehks: Array[String], streamName: String): String = {
    val randomShard = RANDOM.nextInt(ehks.length)
    logger.info(s"Records going to shard $randomShard of $streamName")
    ehks(randomShard)
  }

  @tailrec
  final def getExplicitHashKeys(streamName: String, client: KinesisClient = KinesisClient.create(), failCount: Int = 0): Array[String] = {
    // The spaces are there so that the printed logs are easier to read.
    logger.debug("       Shard        |                  Start                 |                  End                   |                  Middle")
    try {
      getAllShards(streamName, client)
        .filter(_.sequenceNumberRange.endingSequenceNumber() == null) // Open shards have this set to null
        .map(shard => {
          val range = shard.hashKeyRange
          val middle = BigInt(range.startingHashKey()) + BigInt(range.endingHashKey).-(BigInt(range.startingHashKey()))./%(BigInt(2))._1
          logger.debug(s"${shard.shardId()}|${StringUtils.leftPad(range.startingHashKey(), 40, " ")}|${StringUtils.leftPad(range.endingHashKey(), 40, " ")}|${StringUtils.leftPad(middle.toString, 40, " ")}")
          middle.toString
        }).toArray
    } catch {
      // Linear back-off mechanism
      case ex: LimitExceededException => getExplicitHashKeys(streamName, client, retryLogic(ex, failCount))
      case ex: IllegalArgumentException => getExplicitHashKeys(streamName, client, retryLogic(ex, failCount))
      case ex: Throwable => getExplicitHashKeys(streamName, client, retryLogic(ex, failCount))
    }
  }

  @tailrec
  private def getAllShards(streamName: String, client: KinesisClient, nextToken: Option[String] = None, acc: Seq[Shard] = Seq.empty): Seq[Shard] = {
    val req = if (nextToken.isDefined) {
      DescribeStreamRequest.builder().streamName(streamName).exclusiveStartShardId(nextToken.get).build()
    } else {
      DescribeStreamRequest.builder().streamName(streamName).build()
    }
    val resp = client.describeStream(req)
    val newAcc = acc ++ resp.streamDescription().shards().asScala
    if (resp.streamDescription().hasMoreShards) {
      getAllShards(streamName, client, Some(newAcc.last.shardId()), newAcc)
    } else {
      newAcc
    }
  }

  def retryLogic(ex: Throwable, failCount: Int): Int = {
    // This should be a configuration
    if (failCount > maximumRetries) {
      val finalEx = new Exception(s"Linear back-off failed after $failCount retries. Giving up.")
      logger.error(finalEx)
      throw ex
    }
    logger.warn(ex.getMessage)
    logger.warn(s"Linear back-off activated. Sleeping ${(failCount + 1) * 2} seconds.")
    Thread.sleep((failCount + 1) * 2000)
    failCount
  }
}

object KinesisWriter extends KinesisWriter {

  /**
    * Send an iterator of byte[] to Kinesis. It uses a default Kinesis client built
    * using DefaultAWSCredentialsProviderChain and the default region.
    *
    * Example:
    * {{{
    * val it = List(
    *     Array[Byte](10, 11, 23),
    *     Array[Byte](6, 4, 13)
    * ).toIterator
    *
    * KinesisWriter.write("test-stream", it)
    * }}}
    *
    * @param streamName The name of the Kinesis Stream where the data should go to
    * @param it         The iterator containing byte arrays
    */
  def write(streamName: String, it: Iterator[Array[Byte]]): Int = {
    val aggregator = new MyAggregator
    val client = KinesisClient.create()
    val ehks = getExplicitHashKeys(streamName, client)
    write(aggregator, client, streamName, it, ehks, getExplicitHashKey(ehks, streamName), 0)
  }

  /**
    * Send an iterator of byte[] to Kinesis.
    *
    * Example:
    * {{{
    * val it = List(
    *     Array[Byte](10, 11, 23),
    *     Array[Byte](6, 4, 13)
    * ).toIterator
    *
    * val client = new AmazonKinesisClient(new ProfileCredentialsProvider("my-custom-profile"))
    *
    * KinesisWriter.write("test-stream", it, client)
    * }}}
    *
    * @param streamName The name of the Kinesis Stream where the data should go to
    * @param it         The iterator containing byte arrays
    * @param client     The Kinesis client responsible for sending the data to the Kinesis Streams
    */
  def write(streamName: String, it: Iterator[Array[Byte]], client: KinesisClient): Int = {
    val aggregator = new MyAggregator
    val ehks = getExplicitHashKeys(streamName, client)
    write(aggregator, client, streamName, it, ehks, getExplicitHashKey(ehks, streamName), 0)
  }

  @tailrec
  private final def write(aggregator: MyAggregator, client: KinesisClient, streamName: String, it: Iterator[Array[Byte]], ehks: Array[String], ehk: String, count: Int): Int = {
    if (it.hasNext) {
      val message = it.next()
      // Some convoluted logic to make sure the aggregated record is not too large
      val (aggRecord, batch) = if (aggregator.getSizeBytes >= maximumSize) {
        if (message.length > maximumLastSize) {
          val aggR = aggregator.clearAndGet()
          aggregator.addUserRecord(
            "a",
            ehk,
            message
          )
          aggR
        } else {
          aggregator.addUserRecord(
            "a",
            ehk,
            message
          ) match {
            case (aggR: AggRecord, batch: Seq[Array[Byte]]) =>
              // This should not actually happen
              logger.warn("A full aggregated was returned when one was not expected")
              (aggR, batch)
            case _ => aggregator.clearAndGet()
          }
        }
      } else {
        aggregator.addUserRecord(
          "a",
          ehk,
          message
        )
      }
      if (aggRecord != null) {

        sendAggRecord(client, aggRecord, batch, streamName, ehks)
        write(aggregator, client, streamName, it, ehks, getExplicitHashKey(ehks, streamName), count + aggRecord.getNumUserRecords)
      } else {
        write(aggregator, client, streamName, it, ehks, ehk, count)
      }
    } else {
      val (finalRecord, batch) = aggregator.clearAndGet()
      if (finalRecord != null) {
        sendAggRecord(client, finalRecord, batch, streamName, ehks)
        count + finalRecord.getNumUserRecords
      } else {
        count
      }
    }
  }

  def sendAggRecord(client: KinesisClient, aggRecord: AggRecord, batch: Seq[Array[Byte]], streamName: String, ehks: Array[String]) = {
    logger.info(s"Sending ${aggRecord.getNumUserRecords} user records of a size of ${FileUtils.byteCountToDisplaySize(aggRecord.getSizeBytes)}.")

    var putRecordsRequest = aggRecord.toPutRecordRequest(streamName)
    var sent = false
    // Needs to be set via a configuration variable
    var failCount = 0
    do {
      try {
        val response = client.putRecords(putRecordsRequest)

        if (response.failedRecordCount() > 0) {
          throw new Exception("record failed")
        }
        logger.info(s"Wrote ${aggRecord.getNumUserRecords} user records to shard ${response.records().asScala.head.shardId()}")
        sent = true
      } catch {
        case ex: Throwable =>
          val aggregator = new MyAggregator()
          val ehk = getExplicitHashKey(ehks, streamName)
          var currentAggRecord:AggRecord=null
          for (item <- batch) {
            currentAggRecord =aggregator.addUserRecord("a", ehk, item)._1
          }
          if (aggRecord==null) currentAggRecord=aggregator.clearAndGet()._1
          putRecordsRequest = currentAggRecord.toPutRecordRequest(streamName)
          failCount = retryLogic(ex, failCount)
      }
    } while (!sent)
  }
}
