package com.audienceproject.aws.kinesis

import com.amazonaws.kinesis.agg.RecordAggregator
import com.amazonaws.services.kinesis.model.LimitExceededException
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClient}
import com.mindscapehq.raygun4java.core.RaygunClient
import com.trueaccord.scalapb.GeneratedMessage
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.apache.logging.log4j.{LogManager, Logger}

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.util.Random

object PBScalaKinesisWriter {

    try {
        Class.forName("com.amazonaws.kinesis.agg.RecordAggregator", false, this.getClass.getClassLoader )
    } catch {
        case ex: ClassNotFoundException =>
            throw new ClassNotFoundException("The com.audienceproject:kinesis-writer library " +
                "needs the KinesisAggregator .jar to be in the classpath. This dependency is not yet available " +
                "in Maven and needs to be handled manually. See " +
                "https://github.com/awslabs/kinesis-aggregation/tree/master/java/KinesisAggregator", ex)
    }

    private val logger: Logger = LogManager.getLogger( this.getClass.getName )

    private val raygunClient: Option[RaygunClient] = None

    private val maximumRetries = 30

    private val RANDOM = new Random(42)

    /**
      * Send the items in an iterator to an Amazon Kinesis Stream. The items need to be protocol buffers encoded
      * @param streamName The name of the Kinesis Stream on which to work
      * @param it The iterator containing the data
      * @param client The Amazon Kinesis client which retrives information about the Kinesis Stream.
      */
    def write(streamName: String, it: Iterator[GeneratedMessage], client: AmazonKinesis = new AmazonKinesisClient): Unit = {
        val aggregator = new RecordAggregator
        write(aggregator, client, streamName, it, getExplicitHashKey(streamName, client))
    }

    @tailrec
    final private def write(aggregator: RecordAggregator, client: AmazonKinesis, streamName: String, it: Iterator[GeneratedMessage], ehk: String): Unit = {
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
                var sent = false
                // Needs to be set via a configuration variable
                var failCount = 0
                do {
                    try {
                        val response = client.putRecord(aggRecord.toPutRecordRequest(streamName))
                        logger.info(s"Wrote to shard ${response.getShardId}")
                        sent = true
                    } catch {
                        // Linear back-off mechanism
                        case ex: LimitExceededException =>
                            // This should be a configuration
                            if (failCount > maximumRetries ) {
                                val finalEx = new LimitExceededException(s"Linear back-off failed after $failCount retries. Giving up.")
                                logger.error(finalEx)
                                raygunClient.map(_.Send(finalEx, List("streaming")))
                                throw finalEx
                            }
                            logger.error(ex.getMessage, ex)
                            logger.warn(s"Linear back-off activated. Sleeping ${(failCount + 1) * 2} seconds.")
                            Thread.sleep((failCount + 1) * 2000 )
                            failCount = failCount + 1
                        case ex: Throwable =>
                            raygunClient.map(_.Send(ex, List("streaming")))
                            logger.error(ex.getMessage, ex)
                            throw ex
                    }
                } while (!sent)
                write(aggregator, client, streamName, it, newEhk)
            } else {
                write(aggregator, client, streamName, it, ehk)
            }
        } else {
            val finalRecord = aggregator.clearAndGet
            var failCount = 0
            var sent = false
            do {
                try {
                    client.putRecord(finalRecord.toPutRecordRequest(streamName))
                    sent = true
                } catch {
                    // Linear back-off mechanism
                    case ex: LimitExceededException =>
                        // This should be a configuration
                        if (failCount > maximumRetries ) {
                            val finalEx = new LimitExceededException(s"Linear back-off failed after $failCount retries. Giving up.")
                            logger.error(finalEx)
                            raygunClient.map(_.Send(finalEx, List("streaming")))
                            throw finalEx
                        }
                        logger.error(ex.getMessage, ex)
                        logger.warn(s"Linear back-off activated. Sleeping ${(failCount + 1) * 2} seconds.")
                        Thread.sleep((failCount + 1) * 2000 )
                        failCount = failCount + 1
                    case ex: Throwable =>
                        raygunClient.map(_.Send(ex, List("streaming")))
                        logger.error(ex.getMessage, ex)
                        throw ex
                }
            } while (!sent)
        }
    }

    @tailrec
    final private def getExplicitHashKey(streamName: String, client: AmazonKinesis = new AmazonKinesisClient, failCount: Int = 0) : String = {
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
            case ex: LimitExceededException =>
                // This should be a configuration
                if (failCount > maximumRetries ) {
                    val finalEx = new LimitExceededException(s"Linear back-off failed after $failCount retries. Giving up.")
                    logger.error(finalEx)
                    raygunClient.map(_.Send(finalEx, List("streaming")))
                    throw finalEx
                }
                logger.error(ex.getMessage, ex)
                logger.warn(s"Linear back-off activated. Sleeping ${(failCount + 1) * 2} seconds.")
                Thread.sleep((failCount + 1) * 2000 )
                getExplicitHashKey(streamName, client, failCount + 1)
            case ex: Throwable =>
                raygunClient.map(_.Send(ex, List("streaming")))
                logger.error(ex.getMessage, ex)
                throw ex
        }
    }

}