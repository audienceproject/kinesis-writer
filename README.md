## Kinesis Writer

This very simple library can be used to write data to an Amazon Kinesis Stream. There are some prerequisites and restrictions though.
This package was made for _Scala_ and has not been tested with _Java_.
The data that is supposed to be written to the Kinesis Stream has to be an `Iterator[GeneratedMessage]`. `GeneratedMessage` is a trait given to _Protocol Buffers_ classes generated using the _ScalaPB_ sbt plugin [http://trueaccord.github.io/ScalaPB/](http://trueaccord.github.io/ScalaPB/)

## How to use it

Easiest is to just to provide the Kinesis Stream name and the iterator. The Kinesis client is build for you with the default profile credentials provider.
This works great on Amazon EC2.

```
    // PBMessage is class generated from a Protocol Buffers .proto file
    // with the help of the ScalaPB sbt plugin.
    val it = List(
        new PBMessage("now"),
        new PBMessage("yesterday"),
        new PBMessage("tomorrow")
    ).toIterator

    PBScalaKinesisWriter.write("test-stream", it)
```

You can also specify a Kinesis client.
This is mostly useful when running outside AWS.

```
    // PBMessage is class generated from a Protocol Buffers .proto file
    // with the help of the ScalaPB sbt plugin.
    val it = List(
        new PBMessage("now"),
        new PBMessage("yesterday"),
        new PBMessage("tomorrow")
    ).toIterator
    
    val client = new AmazonKinesisClient(new ProfileCredentialsProvider("my-custom-profile"))

    PBScalaKinesisWriter.write("test-stream", it, client)
``` 

If you use [Raygun](https://raygun.com/), you can have exceptions sent there

```
    val it = List(
        new PBMessage("now"),
        new PBMessage("yesterday"),
        new PBMessage("tomorrow")
    ).toIterator
      
    val raygun = new RaygunClient("your-raygun-key")

    PBScalaKinesisWriter.write("test-stream", it, raygun)
```

```
    val it = List(
        new PBMessage("now"),
        new PBMessage("yesterday"),
        new PBMessage("tomorrow")
    ).toIterator

    val client = new AmazonKinesisClient(new ProfileCredentialsProvider("my-custom-profile"))

    val raygun = new RaygunClient("your-raygun-key")

    PBScalaKinesisWriter.write("test-stream", it, client, raygun)
```

## Known issues

This project depends on some classes which are not available in Maven. The classes in question are part of the Kinesis Aggregation repository [https://github.com/awslabs/kinesis-aggregation/tree/master/java/KinesisAggregator](https://github.com/awslabs/kinesis-aggregation/tree/master/java/KinesisAggregator).
If you try to use the `kinesis-writer` and get an error message like bellow, it means you are missing the _KinesisAggregator_ _.jar_ file. You can download a readily available _.jar_ from here [https://github.com/awslabs/kinesis-aggregation/tree/master/java/KinesisAggregator/dist](https://github.com/awslabs/kinesis-aggregation/tree/master/java/KinesisAggregator/dist). 

```
Error:scalac: missing or invalid dependency detected while loading class file 'PBScalaKinesisWriter.class'.
Could not access term kinesis in package com.amazonaws,
because it (or its dependencies) are missing. Check your build definition for
missing or conflicting dependencies. (Re-run with `-Ylog-classpath` to see the problematic classpath.)
A full rebuild may help if 'PBScalaKinesisWriter.class' was compiled against an incompatible version of com.amazonaws.
```