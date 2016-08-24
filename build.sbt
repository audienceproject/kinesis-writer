// Run `sbt dependencyUpdates` if you want to see what dependencies can be updated
// Run `sbt dependencyGraph` if you want to see the dependencies

import com.typesafe.sbt._

/**
  * The groupId in Maven
  */
organization := "com.audienceproject"

/**
  * The artefactId in Maven
  */
name := "kinesis-writer"

/**
  * The version must match "&#94;(\\d+\\.\\d+\\.\\d+)$" to be considered a release
  */
version := "1.0.1"
description := "A simple library for parsing command line arguments."

scalaVersion := "2.11.8"

/**
  * Additional scala version supported.
  */
crossScalaVersions := Seq("2.10.6", "2.11.8")

libraryDependencies ++= {
    val log4j2Version = "2.6.2"
    Seq(
        "org.apache.logging.log4j" % "log4j-api" % log4j2Version,
        "org.apache.logging.log4j" % "log4j-core" % log4j2Version,
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4j2Version
    )
}

libraryDependencies ++= {
    Seq(
        "com.mindscapehq" % "core" % "2.1.0",
        "com.mindscapehq.raygun4java" % "core" % "1.0.2"
    )
}

libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-runtime" % "0.5.38"

libraryDependencies += "commons-io" % "commons-io" % "2.5"

libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.4"

libraryDependencies += "com.typesafe" % "config" % "1.3.0"

libraryDependencies ++= {
    val awsVersion = "1.11.29"
    Seq(
        "com.amazonaws" % "aws-java-sdk-kinesis" % awsVersion
    )
}

scalacOptions ++= Seq("-feature", "-deprecation")

assemblyJarName in assembly := name.value + ".jar"

/**
  * Maven specific settings for publishing to support Maven native projects
  */
publishMavenStyle := true
publishArtifact in Test := false
pomIncludeRepository := { _ => false }
publishTo <<= version { (v: String) =>
    val nexus = "https://oss.sonatype.org/"
    if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
    else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}
val publishSnapshot:Command = Command.command("publishSnapshot") { state =>
    val extracted = Project extract state
    import extracted._
    val currentVersion = getOpt(version).get
    val newState =
        Command.process(s"""set version := "$currentVersion-SNAPSHOT" """, state)
    val (s, _) = Project.extract(newState).runTask(PgpKeys.publishLocalSigned in Compile, newState)
    state
}
commands ++= Seq(publishSnapshot)
pomIncludeRepository := { _ => false }
pomExtra := (
    <url>https://github.com/audienceproject/kinesis-writer</url>
    <licenses>
        <license>
            <name>MIT License</name>
            <url>http://www.opensource.org/licenses/mit-license.php</url>
        </license>
    </licenses>
    <scm>
        <url>git@github.com:audienceproject/kinesis-writer.git</url>
        <connection>scm:git:git//github.com/audienceproject/kinesis-writer.git</connection>
        <developerConnection>scm:git:ssh://github.com:audienceproject/kinesis-writer.git</developerConnection>
    </scm>
    <developers>
        <developer>
            <id>audienceproject</id>
            <email>adtdev@audienceproject.com</email>
            <name>AudienceProject Dev</name>
            <organization>AudienceProject</organization>
            <organizationUrl>http://www.audienceproject.com</organizationUrl>
        </developer>
    </developers>
    <distributionManagement>
        <repository>
            <id>oss.sonatype.org</id>
            <url>https://oss.sonatype.org/service/local/staging/deploy/maven2/</url>
        </repository>
        <snapshotRepository>
            <id>oss.sonatype.org</id>
            <url>https://oss.sonatype.org/content/repositories/snapshots</url>
        </snapshotRepository>
    </distributionManagement>)