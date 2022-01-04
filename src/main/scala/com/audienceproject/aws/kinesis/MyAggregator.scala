package com.audienceproject.aws.kinesis

import com.amazonaws.kinesis.agg.{AggRecord, RecordAggregator}
import software.amazon.kinesis.retrieval.KinesisClientRecord

import scala.collection.mutable

class MyAggregator {
  val aggregator = new RecordAggregator()
  private val dataList = mutable.ListBuffer.empty[Array[Byte]]
  def addUserRecord(partitionKey: String, explicitHashKey: String, data: Array[Byte]): (AggRecord,List[Array[Byte]]) = {
    val result = aggregator.addUserRecord(partitionKey, explicitHashKey, data)
    dataList.append(data)
    (result,dataList.toList)
  }

  def clearAndGet(): (AggRecord,List[Array[Byte]]) = {
    val result = aggregator.clearAndGet()
    val list = dataList.toList
    dataList.clear()
    (result,list)
  }
  def getSizeBytes() = aggregator.getSizeBytes()
}
