package com.audienceproject.aws.kinesis

import com.amazonaws.AmazonClientException
import com.amazonaws.kinesis.agg.{AggRecord, RecordAggregator}
import com.amazonaws.services.kinesis.model.{LimitExceededException, ProvisionedThroughputExceededException}
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.audienceproject.BuildInfo
import com.google.protobuf.GeneratedMessage
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.StringUtils
import org.apache.logging.log4j.{LogManager, Logger}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Random

/**
  * This class can be used for writing aggregated Kinesis Stream Records to an Amazon Kinesis Stream.
  * The Kinesis Stream Records are made of several User Records. This is a concept called aggregation in the context
  * of Amazon Kinesis.
  * This class only works with User Records that are Protocol Buffer based and their class is generated with the help
  * of the ScalaPB sbt plugin (http://trueaccord.github.io/ScalaPB/).
  */
object PBScalaKinesisWriter {

    val logger: Logger = LogManager.getLogger( this.getClass.getName )
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

    /**
      * Send an iterator of Protocol Buffer encoded messages to Kinesis. It uses a default Kinesis client built
      * using DefaultAWSCredentialsProviderChain and the default region.
      *
      * Example:
      * {{{
      * val it = List(
      *     new PBMessage("now"),
      *     new PBMessage("yesterday"),
      *     new PBMessage("tomorrow")
      * ).toIterator
      *
      * PBScalaKinesisWriter.write("test-stream", it)
      * }}}
      *
      * @param streamName The name of the Kinesis Stream where the data should go to
      * @param it The iterator containing Protocol Buffers messages
      */
    def write(streamName: String, it: Iterator[GeneratedMessage]): Int = {
        val aggregator = new RecordAggregator
        val client = AmazonKinesisClientBuilder.defaultClient()
        val ehks = getExplicitHashKeys(streamName, client)
        write(aggregator, client, streamName, it, ehks, getExplicitHashKey(ehks, streamName), 0)
    }

    /**
      * Send an iterator of Protocol Buffer encoded messages to Kinesis.
      *
      * Example:
      * {{{
      * val it = List(
      *     new PBMessage("now"),
      *     new PBMessage("yesterday"),
      *     new PBMessage("tomorrow")
      * ).toIterator
      *
      * val client = new AmazonKinesisClient(new ProfileCredentialsProvider("my-custom-profile"))
      *
      * PBScalaKinesisWriter.write("test-stream", it, client)
      * }}}
      *
      * @param streamName The name of the Kinesis Stream where the data should go to
      * @param it The iterator containing Protocol Buffers messages
      * @param client The Kinesis client responsible for sending the data to the Kinesis Streams
      */
    def write(streamName: String, it: Iterator[GeneratedMessage], client: AmazonKinesis): Int = {
        val aggregator = new RecordAggregator
        val ehks = getExplicitHashKeys(streamName, client)
        write(aggregator, client, streamName, it, ehks, getExplicitHashKey(ehks, streamName), 0)
    }

    @tailrec
    private final def write(aggregator: RecordAggregator, client: AmazonKinesis, streamName: String,
                    it: Iterator[GeneratedMessage], ehks: Array[String], ehk: String, count: Int): Int = {
        if (it.hasNext) {
            val nxt = it.next
            val message = try {
                nxt.toByteArray
            } catch {
                case ex: Throwable =>
                    logger.error(s"Got an error while trying to serialize $nxt")
                    throw ex
            }
            // Some convoluted logic to make sure the aggregated record is not too large
            val aggRecord = if(aggregator.getSizeBytes >= maximumSize) {
                if (message.length > maximumLastSize ) {
                    val aggR = aggregator.clearAndGet
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
                        case aggR: AggRecord =>
                            // This should not actually happen
                            logger.warn("A full aggregated was returned when one was not expected")
                            aggR
                        case _ => aggregator.clearAndGet
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
                logger.info(s"Sending ${aggRecord.getNumUserRecords} user records of a size of "
                              + s" ${FileUtils.byteCountToDisplaySize(aggRecord.getSizeBytes)}.")
                val putRecordRequest = aggRecord.toPutRecordRequest(streamName)
                var sent = false
                // Needs to be set via a configuration variable
                var failCount = 0
                do {
                    try {
                        val response = client.putRecord(putRecordRequest)
                        logger.info(s"Wrote ${aggRecord.getNumUserRecords} user records to shard ${response.getShardId}")
                        sent = true
                    } catch {
                        // Linear back-off mechanism
                        case ex: ProvisionedThroughputExceededException => failCount = retryLogic(ex, failCount)
                        case ex: Throwable => failCount = retryLogic(ex, failCount)
                    }
                } while (!sent)
                write(aggregator, client, streamName, it, ehks, getExplicitHashKey(ehks, streamName), count + aggRecord.getNumUserRecords)
            } else {
                write(aggregator, client, streamName, it, ehks, ehk, count)
            }
        } else {
            val finalRecord = aggregator.clearAndGet
            if ( finalRecord != null ) {
                val putRecordRequest = finalRecord.toPutRecordRequest(streamName)
                var failCount = 0
                var sent = false
                do {
                    try {
                        val response = client.putRecord(putRecordRequest)
                        logger.info(s"Wrote last ${finalRecord.getNumUserRecords} user records to shard ${response.getShardId}")
                        sent = true
                    } catch {
                        // Linear back-off mechanism
                        case ex: ProvisionedThroughputExceededException => failCount = retryLogic(ex, failCount)
                        case ex: Throwable => failCount = retryLogic(ex, failCount)
                    }
                } while (!sent)
                count + finalRecord.getNumUserRecords
            } else {
                count
            }
        }
    }

    private final def getExplicitHashKey(ehks: Array[String], streamName: String) : String = {
        val randomShard = RANDOM.nextInt(ehks.length)
        logger.info(s"Records going to shard $randomShard of $streamName")
        ehks(randomShard)
    }

    @tailrec
    private final def getExplicitHashKeys(streamName: String, client: AmazonKinesis = AmazonKinesisClientBuilder.defaultClient(), failCount: Int = 0): Array[String] = {
        // The spaces are there so that the printed logs are easier to read.
        logger.debug("       Shard        |                  Start                 |                  End                   |                  Middle")
        try {
            client.describeStream(streamName).getStreamDescription.getShards.asScala
                .filter(_.getSequenceNumberRange.getEndingSequenceNumber == null) // Open shards have this set to null
                .map(shard => {
                    val range = shard.getHashKeyRange
                    val middle = BigDecimal(range.getStartingHashKey).+(BigDecimal(range.getEndingHashKey).-(BigDecimal(range.getStartingHashKey))./%(BigDecimal(2))._1)
                    logger.debug(s"${shard.getShardId}|${StringUtils.leftPad(range.getStartingHashKey, 40, " ")}|${StringUtils.leftPad(range.getEndingHashKey, 40, " ")}|${StringUtils.leftPad(middle.toString, 40, " ")}")
                    middle.toString
                }).toArray
        } catch {
            // Linear back-off mechanism
            case ex: LimitExceededException => getExplicitHashKeys(streamName, client, retryLogic(ex, failCount))
            case ex: IllegalArgumentException => getExplicitHashKeys(streamName, client, retryLogic(ex, failCount))
            case ex: AmazonClientException => getExplicitHashKeys(streamName, client, retryLogic(ex, failCount))
            case ex: Throwable => getExplicitHashKeys(streamName, client, retryLogic(ex, failCount))
        }
    }

    private def retryLogic(ex: Throwable, failCount: Int): Int = {
        // This should be a configuration
        if (failCount > maximumRetries ) {
            val finalEx = new Exception(s"Linear back-off failed after $failCount retries. Giving up.")
            logger.error(finalEx)
            throw ex
        }
        logger.warn(ex.getMessage)
        logger.warn(s"Linear back-off activated. Sleeping ${(failCount + 1) * 2} seconds.")
        Thread.sleep((failCount + 1) * 2000 )
        failCount
    }

}