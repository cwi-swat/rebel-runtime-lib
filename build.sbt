import Dependencies._

organization in ThisBuild := "com.ing.rebel"

scalaVersion in ThisBuild := "2.12.10"

name := "rebel-runtime-lib"

// semantic versioning: http://semver.org using sbt-dynver

scalacOptions in ThisBuild := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  kryo ++
  Seq(
//    "com.typesafe.akka"     %% "akka-actor-typed"       % akkaV,
    "com.typesafe.akka"     %% "akka-remote"            % akkaV,
    "com.typesafe.akka"     %%  "akka-actor"            % akkaV,
    "com.typesafe.akka"     %%  "akka-cluster"          % akkaV,
//    "com.typesafe.akka"     %%  "akka-contrib"          % akkaV,
    "com.typesafe.akka"     %% "akka-cluster-tools"     % akkaV,
    "com.typesafe.akka"     %% "akka-cluster-sharding"  % akkaV,
    "com.typesafe.akka"     %% "akka-cluster-metrics"   % akkaV,
    "com.typesafe.akka"     %% "akka-http"              % akkaHttpV,
    "ch.megard"             %% "akka-http-cors"         % "0.4.2",
//    "com.esotericsoftware" % "kryo" % "5.0.0-RC3",
    "io.circe"              %% "circe-core"             % circeVersion,
//    "io.circe"              %% "circe-jawn"             % circeVersion,
    "io.circe"              %% "circe-parser"           % circeVersion,
    "io.circe"              %% "circe-generic"          % circeVersion,
    "io.circe"              %% "circe-generic-extras"   % "0.12.2",
    "de.heikoseeberger"     %% "akka-http-circe"        % "1.30.0",
    "org.typelevel"         %% "cats-core"              % "2.1.0",
//    "org.apache.cassandra"  %  "cassandra-all"          % "3.11.0",
    "com.typesafe.akka"     %% "akka-persistence-cassandra" % cassandraPersistenceVersion,
    // to make sure akka-persistence-cassandra does not include old version
    "com.typesafe.akka"     %% "akka-persistence-query"   % akkaV,
      //    "com.github.swagger-akka-http" %% "swagger-akka-http" % "0.7.2",
    "com.github.nscala-time" %% "nscala-time"           % "2.22.0",
    "org.typelevel"         %% "squants"                % "1.6.0",
    "com.github.pureconfig" %% "pureconfig"             % "0.12.2",
//    "com.github.melrief"    %% "pureconfig-enum"        % pureConfigVersion,

//    "org.aspectj"           %  "aspectjweaver"          % "1.8.13",
    "it.unimi.dsi"          % "dsiutils"                % "2.6.0" exclude("com.google.guava", "guava"),
    "org.scala-lang"        % "scala-reflect" % scalaVersion.value,
//    "com.github.oshi" % "oshi-core" % "4.0.0", // included in Kamon
    "org.iq80.leveldb"      %  "leveldb"                % "0.12" % "test",
    "com.typesafe.akka"     %% "akka-testkit"           % akkaV  % "test",
    "com.typesafe.akka"     %% "akka-multi-node-testkit"% akkaV % "test",
    "com.typesafe.akka"     %% "akka-http-testkit"      % akkaHttpV  % "test",
    "org.scalactic"         %% "scalactic"              % "3.1.0",
    "org.scalatest"         %% "scalatest"              % "3.1.0" % "test",
    "com.ironcorelabs"      %% "cats-scalatest" % "3.0.5" % "test", // in sync with Cats version
    // required for scalatest html report
//    "org.pegdown"           %  "pegdown"                % "1.6.0"  % "test",
    "org.scalacheck"        %% "scalacheck"             % "1.14.3" % "test",
    "org.scalatestplus" %% "scalacheck-1-14" % "3.1.0.1" % "test",
    "com.typesafe.akka"     %% "akka-persistence-cassandra-launcher" % cassandraPersistenceVersion % Test
  )
}

libraryDependencies ++= Seq(
  "io.kamon"              %% "kamon-bundle"             % "2.1.0",
  "io.kamon"              %% "kamon-statsd"           % "2.1.0",
  "io.kamon" %% "kamon-influxdb" % "2.1.0"
  // override version, required for Kamon to work correctly...
  //    "org.asynchttpclient"   % "async-http-client"       % "2.0.39",
//  "io.kamon"              %% "kamon-akka"         % "2.0.0",
  //    "io.kamon"              %% "kamon-akka-remote-2.4"  % kamonVersion7,
  //    "io.kamon"              %% "kamon-log-reporter"     % "1.0.0",
//  "io.kamon"              %% "kamon-system-metrics"   % "2.020",
//  "io.kamon"              %% "kamon-akka-http"        % "2.0.2",
//  "io.kamon"              %% "kamon-autoweave"        % "0.6.5",
//  "io.kamon"              %% "kamon-cassandra-client" % "1.0.6"
)

// using shared journal
addCommandAlias("run1", "test:runMain com.ing.example.BootLevelDb -Dakka.remote.netty.tcp.port=2551 -Drebel.visualisation.port=8001 -Drebel.clustering.ip=localhost")
addCommandAlias("run2", "test:runMain com.ing.example.BootLevelDb -Dakka.remote.netty.tcp.port=2552 -Drebel.visualisation.port=8002 -Drebel.endpoints.port=8082 -Dakka.cluster.seed-nodes.0=akka://rebel-system@localhost:2551")
addCommandAlias("run3", "test:runMain com.ing.example.BootLevelDb -Dakka.remote.netty.tcp.port=2553 -Drebel.visualisation.port=8003 -Drebel.endpoints.port=8083 -Dakka.cluster.seed-nodes.0=akka://rebel-system@localhost:2551")
addCommandAlias("run4", "test:runMain com.ing.example.BootLevelDb -Dakka.remote.netty.tcp.port=2554 -Drebel.visualisation.port=8004 -Drebel.endpoints.port=8084 -Dakka.cluster.seed-nodes.0=akka://rebel-system@localhost:2551")


addCommandAlias("cassandra", "cassandra/runMain com.ing.rebel.boot.Cassandra")

val localCassandraSettings = "-Dcassandra-journal.replication-factor=1 -Dcassandra-snapshot-store.replication-factor=1"

// using cassandra
addCommandAlias("crun1", s"test:runMain com.ing.example.BootCassandra $localCassandraSettings -Dakka.remote.netty.tcp.port=2551 -Drebel.visualisation.port=8001 -Dconfig.resource=cluster.conf -Drebel.clustering.ip=localhost")
addCommandAlias("crun2", s"test:runMain com.ing.example.BootCassandra $localCassandraSettings -Dakka.remote.netty.tcp.port=2552 -Drebel.visualisation.port=8002 -Dconfig.resource=cluster.conf -Dakka.cluster.seed-nodes.0=akka://rebel-system@localhost:2551")
addCommandAlias("crun3", s"test:runMain com.ing.example.BootCassandra $localCassandraSettings -Dakka.remote.netty.tcp.port=2553 -Drebel.visualisation.port=8003 -Dconfig.resource=cluster.conf -Dakka.cluster.seed-nodes.0=akka://rebel-system@localhost:2551")
addCommandAlias("crun4", s"test:runMain com.ing.example.BootCassandra $localCassandraSettings -Dakka.remote.netty.tcp.port=2554 -Drebel.visualisation.port=8004 -Dconfig.resource=cluster.conf -Dakka.cluster.seed-nodes.0=akka://rebel-system@localhost:2551")

// run generated version
addCommandAlias("grun1", s"generated/runMain com.ing.corebank.rebel.Boot $localCassandraSettings -Dakka.remote.netty.tcp.port=2551 -Drebel.visualisation.port=8001 -Drebel.clustering.ip=localhost")
addCommandAlias("grun2", s"generated/runMain com.ing.corebank.rebel.Boot $localCassandraSettings -Dakka.remote.netty.tcp.port=2552 -Drebel.visualisation.port=8002 -Drebel.endpoints.port=8082  -Dakka.cluster.seed-nodes.0=akka://rebel-system@localhost:2551")
addCommandAlias("grun3", s"generated/runMain com.ing.corebank.rebel.Boot $localCassandraSettings -Dakka.remote.netty.tcp.port=2553 -Drebel.visualisation.port=8003 -Drebel.endpoints.port=8083 -Dakka.cluster.seed-nodes.0=akka://rebel-system@localhost:2551")
addCommandAlias("grun4", s"generated/runMain com.ing.corebank.rebel.Boot $localCassandraSettings -Dakka.remote.netty.tcp.port=2554 -Drebel.visualisation.port=8004 -Dakka.cluster.seed-nodes.0=akka://rebel-system@localhost:2551")

addCommandAlias("AccountConsistencyChecker", "generated/it:runMain com.ing.rebel.consistency.AccountConsistencyChecker")
addCommandAlias("TransactionConsistencyChecker", "generated/it:runMain com.ing.rebel.consistency.TransactionConsistencyChecker")

// for leveldb testing
fork := true

//testOptions in Test ++= Seq(Tests.Argument(TestFrameworks.ScalaTest, "-o"), Tests.Argument(TestFrameworks.ScalaTest, "-h", "target/test-reports"))

javaOptions in Test += "-Xmx3G"

// commented projects are disabled to improve sbt performance
lazy val root = project in file(".")
// makes sure some settings are overridden for generated code subproject
lazy val generated = (project in file("generated")).dependsOn(root % "test->test;compile->compile;it->test")//.addSbtFiles(file("../generated.sbt"))
lazy val bench = (project in file("bench")).dependsOn(generated % "test->test;compile->test", root % "test->test")
lazy val perf = (project in file("perf")).dependsOn(generated % "test->test;compile->test;compile->compile")
lazy val experiment_runner = (project in file("experiment-runner"))//.dependsOn(root)
lazy val grafana_influxdb = project in file("experiment-runner/grafana_influxdb")
lazy val cassandra_reporting = project in file("experiment-runner/cassandra_reporting")
lazy val cassandra = project in file("cassandra")

// test jar, needed for microbenchmark docker
// enable publishing the jar produced by `test:package`
publishArtifact in (Test, packageBin) := true

// enable publishing the test API jar
publishArtifact in (Test, packageDoc) := true

// enable publishing the test sources jar
publishArtifact in (Test, packageSrc) := true

// add version to jar
enablePlugins(BuildInfoPlugin)
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoPackage := "com.ing.rebel.util"