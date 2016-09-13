package com.audienceproject.aws.kinesis

import com.amazonaws.AmazonClientException
import com.amazonaws.kinesis.agg.RecordAggregator
import com.amazonaws.services.kinesis.model.{LimitExceededException, ProvisionedThroughputExceededException}
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClient}
import com.audienceproject.BuildInfo
import com.mindscapehq.raygun4java.core.RaygunClient
import com.trueaccord.scalapb.GeneratedMessage
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.StringUtils
import org.apache.logging.log4j.{LogManager, Logger}

import scala.annotation.tailrec
import scala.collection.JavaConversions._
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
    def write(streamName: String, it: Iterator[GeneratedMessage]): Unit = {
        val aggregator = new RecordAggregator
        val client = new AmazonKinesisClient
        write(aggregator, client, streamName, it, getExplicitHashKey(streamName, client))
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
    def write(streamName: String, it: Iterator[GeneratedMessage], client: AmazonKinesis): Unit = {
        val aggregator = new RecordAggregator
        write(aggregator, client, streamName, it, getExplicitHashKey(streamName, client))
    }

    /**
      * Send an iterator of Protocol Buffer encoded messages to Kinesis. It uses a default Kinesis client built
      * using DefaultAWSCredentialsProviderChain and the default region.
      * It also uses a RaygunClient. Raygun is a 3rd party error logging service provider.
      *
      * Example:
      * {{{
      * val it = List(
      *     new PBMessage("now"),
      *     new PBMessage("yesterday"),
      *     new PBMessage("tomorrow")
      * ).toIterator
      *
      * val raygunClient = new RaygunClient("your-raygunClient-key")
      *
      * PBScalaKinesisWriter.write("test-stream", it, raygunClient)
      * }}}
      *
      * @param streamName The name of the Kinesis Stream where the data should go to
      * @param it The iterator containing Protocol Buffers messages
      * @param raygunClient The Raygun client which sends exceptions
      */
    def write(streamName: String, it: Iterator[GeneratedMessage], raygunClient: RaygunClient): Unit = {
        val aggregator = new RecordAggregator
        val client = new AmazonKinesisClient
        write(aggregator, client, streamName, it, getExplicitHashKey(streamName, client, 0, Option(raygunClient)), Option(raygunClient))
    }

    /**
      * Send an iterator of Protocol Buffer encoded messages to Kinesis.
      * It also uses a RaygunClient. Raygun is a 3rd party error logging service provider.
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
      * val raygunClient = new RaygunClient("your-raygunClient-key")
      *
      * PBScalaKinesisWriter.write("test-stream", it, client, raygunClient)
      * }}}
      *
      * @param streamName The name of the Kinesis Stream where the data should go to
      * @param it The iterator containing Protocol Buffers messages
      * @param client The Kinesis client responsible for sending the data to the Kinesis Streams
      * @param raygunClient The Raygun client which sends exceptions
      */
    def write(streamName: String, it: Iterator[GeneratedMessage], client: AmazonKinesis, raygunClient: RaygunClient): Unit = {
        val aggregator = new RecordAggregator
        write(aggregator, client, streamName, it, getExplicitHashKey(streamName, client, 0, Option(raygunClient)), Option(raygunClient))
    }

    @tailrec
    private final def write(aggregator: RecordAggregator, client: AmazonKinesis, streamName: String,
                    it: Iterator[GeneratedMessage], ehk: String, raygunClient: Option[RaygunClient] = None): Unit = {
        if (it.hasNext) {
            val aggRecord = aggregator.addUserRecord(
                "a",
                ehk,
                it.next.toByteArray
            )
            if (aggRecord != null) {
                logger.info(s"Sending ${aggRecord.getNumUserRecords} user records of a size of "
                              + s" ${FileUtils.byteCountToDisplaySize(aggRecord.getSizeBytes)}.")
                val newEhk = getExplicitHashKey(streamName, client)
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
                        case ex: ProvisionedThroughputExceededException => failCount = retryLogic(ex, failCount, raygunClient)
                        case ex: Throwable =>
                            if (raygunClient.isDefined) raygunClient.get.Send(ex, List("kinesis"))
                            logger.error(ex.getMessage, ex)
                            throw ex
                    }
                } while (!sent)
                write(aggregator, client, streamName, it, newEhk, raygunClient)
            } else {
                write(aggregator, client, streamName, it, ehk, raygunClient)
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
                        logger.info(s"Wrote last bits and pieces to shard ${response.getShardId}")
                        sent = true
                    } catch {
                        // Linear back-off mechanism
                        case ex: ProvisionedThroughputExceededException => failCount = retryLogic(ex, failCount, raygunClient)
                        case ex: Throwable =>
                            if (raygunClient.isDefined) raygunClient.get.Send(ex, List("kinesis"))
                            logger.error(ex.getMessage, ex)
                            throw ex
                    }
                } while (!sent)
            }
        }
    }

    @tailrec
    private final def getExplicitHashKey(streamName: String, client: AmazonKinesis = new AmazonKinesisClient, failCount: Int = 0, raygunClient: Option[RaygunClient] = None) : String = {
        // The spaces are there so that the printed logs are easier to read.
        logger.debug("       Shard        |                  Start                 |                  End                   |                  Middle")
        // Get shard information again in case the stream was repartitioned
        try {
            val ehks = client.describeStream(streamName).getStreamDescription.getShards.map(shard => {
                val range = shard.getHashKeyRange
                val middle = BigDecimal(range.getStartingHashKey).+(BigDecimal(range.getEndingHashKey).-(BigDecimal(range.getStartingHashKey))./%(BigDecimal(2))._1)
                logger.debug(s"${shard.getShardId}|${StringUtils.leftPad(range.getStartingHashKey, 40, " ")}|${StringUtils.leftPad(range.getEndingHashKey, 40, " ")}|${StringUtils.leftPad(middle.toString, 40, " ")}")
                middle.toString
            } ).toArray
            val randomShard = RANDOM.nextInt(ehks.length)
            logger.info(s"Records going to shard $randomShard")
            ehks(randomShard)
        } catch {
            // Linear back-off mechanism
            case ex: LimitExceededException => getExplicitHashKey(streamName, client, retryLogic(ex, failCount, raygunClient), raygunClient)
            case ex: IllegalArgumentException => getExplicitHashKey(streamName, client, retryLogic(ex, failCount, raygunClient), raygunClient)
            case ex: AmazonClientException => getExplicitHashKey(streamName, client, retryLogic(ex, failCount, raygunClient), raygunClient)
            case ex: Throwable =>
                if (raygunClient.isDefined) raygunClient.get.Send(ex, List("kinesis"))
                logger.error(ex.getMessage, ex)
                throw ex
        }
    }

    private def retryLogic(ex: Throwable, failCount: Int, raygunClient: Option[RaygunClient]): Int = {
        // This should be a configuration
        if (failCount > maximumRetries ) {
            val finalEx = new Exception(s"Linear back-off failed after $failCount retries. Giving up.")
            logger.error(finalEx)
            if (raygunClient.isDefined) raygunClient.get.Send(ex, List("kinesis"))
            throw ex
        }
        logger.warn(ex.getMessage)
        logger.warn(s"Linear back-off activated. Sleeping ${(failCount + 1) * 2} seconds.")
        Thread.sleep((failCount + 1) * 2000 )
        failCount
    }

}