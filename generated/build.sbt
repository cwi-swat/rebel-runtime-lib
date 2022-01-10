name          := "simple-transaction"

// TODO: Put specification version/date here
//version       := "0.1-GENERATED"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")


libraryDependencies += "com.ing.rebel" %% "rebel-runtime-lib" % (version in ThisBuild).value

//addCommandAlias("cassandra", "runMain com.ing.rebel.boot.Cassandra")

// using cassandra
//addCommandAlias("run1", "runMain com.ing.corebank.rebel.Boot -Dakka.remote.netty.tcp.port=2551 -Drebel.visualisation.port=8001")
//addCommandAlias("run2", "runMain com.ing.corebank.rebel.Boot -Dakka.remote.netty.tcp.port=2552 -Drebel.visualisation.port=8002")
//addCommandAlias("run3", "runMain com.ing.corebank.rebel.Boot -Dakka.remote.netty.tcp.port=2553 -Drebel.visualisation.port=8003")
//addCommandAlias("run4", "runMain com.ing.corebank.rebel.Boot -Dakka.remote.netty.tcp.port=2554 -Drebel.visualisation.port=8004")


//// gatling
//libraryDependencies ++= {
//  val gatlingV = "2.2.0"
//  Seq(
//    "io.gatling.highcharts" %  "gatling-charts-highcharts"            % gatlingV         % "it,test,gatling",
//    "io.gatling"            %  "gatling-test-framework"               % gatlingV         % "it,test,gatling"
//  )
//}
//
//enablePlugins(GatlingPlugin)
//lazy val root = project.in(file("."))
//  .configs(Gatling)
//  .configs(IntegrationTest)
//  .settings(GatlingPlugin.gatlingSettings: _*)
//  .settings(scalaSource in Gatling := sourceDirectory.value / "perf/scala")
//  .settings(resourceDirectory in Gatling := sourceDirectory.value / "perf/resources")
//  .settings(testOptions in Gatling := Seq(Tests.Filter(_ endsWith "Simulation")))
//  .settings(fullClasspath in Gatling += crossTarget.value / "gatling-classes")
