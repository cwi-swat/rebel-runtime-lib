name := "experiment-runner"

import Dependencies._

// for scripting AWS
libraryDependencies ++= Seq(
  "ecs", "ec2", "logs", "netty-nio-client"
).map("software.amazon.awssdk" % _ % "2.0.0-preview-10")

libraryDependencies += "com.lihaoyi" % "ammonite" % "2.0.1" cross CrossVersion.full

libraryDependencies += "com.github.pureconfig" %% "pureconfig" % "0.12.2"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"

//libraryDependencies += "com.github.melrief" %% "purecsv" % "0.0.9"
//libraryDependencies += "io.airlift" % "airline" % "0.6"
libraryDependencies += "org.apache.cassandra" % "cassandra-all" % "3.11.5"
//libraryDependencies += "com.datastax.cassandra" % "cassandra-driver-core" % "3.3.2"
libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "2.22.0"
libraryDependencies += "io.netty" % "netty-all" % "4.1.44.Final"
libraryDependencies += "io.netty" % "netty-common" % "4.1.44.Final"
//dependencyOverrides += "io.netty" % "netty-all" % "4.1.13.Final"
//libraryDependencies += "com.gilt" %% "gfc-timeuuid" % "0.0.8"
libraryDependencies +=  "org.scalaj" %% "scalaj-http" % "2.4.2"

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"

libraryDependencies += "org.scalatest" %% "scalatest" % scalaTestVersion % "test"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-generic"
).map(_ % circeVersion)

libraryDependencies += "io.monix" %% "monix" % "2.3.0"
libraryDependencies += "io.monadless" %% "monadless-monix" % "0.0.13"
libraryDependencies += "io.monadless" %% "monadless-stdlib" % "0.0.13"

//libraryDependencies ++= Seq(
//  "io.gatling" % "gatling-charts",
//  "io.gatling" % "gatling-app",
//  "io.gatling.highcharts" % "gatling-charts-highcharts"
//  //  "io.gatling" % "gatling-test-framework"
//).map(_ % "2.3.0" )

libraryDependencies += "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0"


//excludeDependencies += "io.netty" % "netty-common" % "4.0.51.Final"

fork := true
javaOptions += "-Dconfig.resource=" + Option(System.getProperty("experiment")).getOrElse("AllToAll") + ".conf"
javaOptions += "-Dbatch.extra-description=" + Option(System.getProperty("extra-description")).getOrElse("")


enablePlugins(BuildInfoPlugin)
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoPackage := "info"
