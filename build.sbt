// Run `sbt dependencyUpdates` if you want to see what dependencies can be updated
// Run `sbt dependencyGraph` if you want to see the dependencies

import java.text.SimpleDateFormat
import java.util.Date

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
version := "1.0.11"
description := "Helper class for writing Protocol Buffer messages to Amazon Kinesis streams with the maximum throughput possible."

scalaVersion := "2.11.8"

/**
  * Additional scala version supported.
  */
crossScalaVersions := Seq("2.10.6", "2.11.8")

libraryDependencies ++= {
    val log4j2Version = "2.7"
    Seq(
        "org.apache.logging.log4j" % "log4j-api" % log4j2Version,
        "org.apache.logging.log4j" % "log4j-core" % log4j2Version,
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4j2Version
    )
}

libraryDependencies ++= {
    Seq(
        "com.mindscapehq" % "core" % "2.1.1",
        "com.mindscapehq.raygun4java" % "core" % "1.0.2"
    )
}

libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-runtime" % "0.5.43"

libraryDependencies += "commons-io" % "commons-io" % "2.5"

libraryDependencies += "commons-lang" % "commons-lang" % "2.6"

libraryDependencies += "com.amazonaws" % "amazon-kinesis-client" % "1.7.0"

libraryDependencies += "com.amazonaws" % "aws-java-sdk-kinesis" % "1.11.43"

scalacOptions ++= Seq("-feature", "-deprecation")

lazy val root = (project in file(".")).
                enablePlugins(BuildInfoPlugin).
                settings(
                    buildInfoKeys := Seq[BuildInfoKey](
                        name, version, scalaVersion, sbtVersion,
                        BuildInfoKey.action("buildDate") {
                            val date = new Date(System.currentTimeMillis)
                            val df = new SimpleDateFormat("HH:mm:ss dd-MM-yyyy")
                            df.format(date)
                        }
                    ),
                    buildInfoPackage := "com.audienceproject"
                )

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
    val (s, _) = Project.extract(newState).runTask(PgpKeys.publishSigned in Compile, newState)
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
)