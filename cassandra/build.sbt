name := "cassandra"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence-cassandra-launcher",
  "com.typesafe.akka" %% "akka-persistence-cassandra"
).map(_ % "0.56")