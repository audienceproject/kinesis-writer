## Kinesis Writer

This very simple library can be used to write data to an _Amazon Kinesis_ stream.

The data that is supposed to be written to the Kinesis Stream has to be an `Array[Byte]`.

## How to use it

Include the library in your project's `build.sbt` file:

```
libraryDependencies += "com.audienceproject" %% "kinesis-writer" % "3.0.1"
```

Easiest is to just to provide the Kinesis Stream name and the iterator. The Kinesis client is build for you with the default profile credentials provider.
This works great on Amazon EC2.

```
val it = List(
    Array[Byte](10, 11, 23),
    Array[Byte](6, 4, 13)
).toIterator

KinesisWriter.write("test-stream", it)
```

You can also specify a Kinesis client.
This is mostly useful when running outside AWS.

```
val it = List(
    Array[Byte](10, 11, 23),
    Array[Byte](6, 4, 13)
).toIterator

val client = new AmazonKinesisClient(new ProfileCredentialsProvider("my-custom-profile"))

ScalaKinesisWriter.write("test-stream", it, client)
``` 


## Known issues

Has code copy pasted from: https://github.com/awslabs/kinesis-aggregation/tree/master/java/KinesisAggregatorV2 due to it not being on maven and manual lib dependencies being subpar.
#### Magical numbers

To make sure that data is successfully consumed on the other side of the _Kinesis_ stream, the maximum record size has empirically been made smaller than what _Amazon_ lists as maximum.
Experiments have shown that records the size of (or close) to 1MB can fail to be consumed correctly.