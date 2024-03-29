// Run `sbt dependencyUpdates` if you want to see what dependencies can be updated
// Run `sbt dependencyGraph` if you want to see the dependencies

import java.text.SimpleDateFormat
import java.util.Date

/**
  * The groupId in Maven
  */
organization := "com.audienceproject"
ThisBuild / versionScheme := Some("early-semver")
/**
  * The artefactId in Maven
  */
name := "kinesis-writer"

/**
  * The version must match "&#94;(\\d+\\.\\d+\\.\\d+)$" to be considered a release
  */
version := "3.2.1"
description := "Helper class for writing byte[] messages to Amazon Kinesis streams with the maximum throughput possible."

scalaVersion := "2.12.14"
javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")
scalacOptions := Seq("-target:jvm-1.8")
initialize := {
    val _ = initialize.value
    val javaVersion = sys.props("java.specification.version")
    if (javaVersion != "1.8")
        sys.error("Java 1.8 is required for this project. Found " + javaVersion + " instead")
}
/**
  * Additional scala version supported.
  */
crossScalaVersions := Seq("2.12.14", "2.13.6")

libraryDependencies ++= {
    val log4j2Version = "2.17.1"
    Seq(
        "org.apache.logging.log4j" % "log4j-api" % log4j2Version,
        "org.apache.logging.log4j" % "log4j-core" % log4j2Version,
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4j2Version
    )
}

libraryDependencies += "commons-io" % "commons-io" % "2.11.0"

libraryDependencies += "commons-lang" % "commons-lang" % "2.6"

libraryDependencies += "software.amazon.kinesis" % "amazon-kinesis-client" % "2.3.9"
// https://mvnrepository.com/artifact/org.apache.commons/commons-compress
libraryDependencies += "org.apache.commons" % "commons-compress" % "1.21" // found security issue in 1.20



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

/**
  * Maven specific settings for publishing to support Maven native projects
  */
publishMavenStyle := true
Test / publishArtifact := false
pomIncludeRepository := { _ => false }

publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
    else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

val publishSnapshot:Command = Command.command("publishSnapshot") { state =>
    val extracted = Project extract state
    import extracted._
    val currentVersion = getOpt(version).get
    val newState = extracted.appendWithoutSession(Seq(version := s"$currentVersion-SNAPSHOT"), state)
    Project.extract(newState).runTask(Compile / PgpKeys.publishSigned, newState)
    state
}
commands ++= Seq(publishSnapshot)
pomIncludeRepository := { _ => false }
pomExtra := <url>https://github.com/audienceproject/kinesis-writer</url>
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
