name := "generated-performancetest"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

// Gatling
//resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
enablePlugins(GatlingPlugin)
libraryDependencies ++= Seq(
  "io.gatling.highcharts" % "gatling-charts-highcharts",
  "io.gatling" % "gatling-test-framework"
).map(_ % "3.3.1") // fixes bug where actor system did not stop fast enough

// for Zipf
libraryDependencies += "org.apache.commons" % "commons-math3" % "3.6.1"

libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.14.3" % "test"

// for gatling?
//libraryDependencies += "io.netty" % "netty-tcnative-boringssl-static" % "2.0.17.Final"

// work around to make sure correct dependency is used... TODO find out why this does not work ootb
//libraryDependencies += "io.netty" % "netty-all" % "4.0.52.Final"
//libraryDependencies += "io.netty" % "netty" % "4.0.52.Final"

// needed for -J-D parameters
fork := true

// Docker
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

maintainer in Docker := "Tim Soethout"
version in Docker := "latest"
//daemonUser in Docker := "app" // user in the Docker image which will execute the application (must already exist)
//dockerBaseImage := "java:8" // Docker image to use as a base for the application image
dockerBaseImage := "denvazh/gatling:2.3.1" // Docker image to use as a base for the application image
//dockerExposedPorts in Docker := Seq(8080, 8001, 2551) // Ports to expose from container for Docker container linking
//dockerRepository := Some("docker.europe.intranet:5000") // Repository used when publishing Docker image
// entrypoint
mainClass in Compile := Some("io.gatling.app.Gatling")
mainClass in assembly := Some("io.gatling.app.Gatling")

defaultLinuxInstallLocation in Docker := "/opt/gatling"
//dockerEntrypoint := Seq("bin/gatling.sh")
dockerEntrypoint := Seq("generated-performancetest")
//mainClass := Some("io.gatling.app.Gatling")

import com.typesafe.sbt.packager.docker._

//dockerCommands := {
//  // first remove all libs from image
//  println(s"Original dockerCommands ${dockerCommands.value}")
//  dockerCommands.value.filter {
//    case Cmd("FROM", args@_*)    => true
//    case Cmd("LABEL", args@_*)   => true
//    case Cmd("WORKDIR", args@_*) => true
//    case cmd                     => false
//  } ++ Seq(
//    Cmd("RUN", "apk add --update libc6-compat"), // needed for newer netty
//    Cmd("RUN", "rm -rf lib/*.jar ; ls lib"),
//    Cmd("COPY", "opt/gatling/01-gatling.conf /etc/sysctl.d/01-gatling.conf")
//  ) ++
//    dockerCommands.value.filter {
//      // only keep these
//      case Cmd("ADD", args@_*)            => true
////      case Cmd("COPY", args@_*)            => true
//      case ExecCmd("ENTRYPOINT", args@_*) => true
//      case cmd                            => false
//      // somehow this does not work.... reappears in final image
//    } ++ Seq(
//    Cmd("RUN", "rm -rf user-files/* ; ls user-files"))
//}

dockerCommands := {
  //  // first remove all libs from image
  dockerCommands.value.patch(2,
    Seq(Cmd("RUN", "rm -rf lib/*.jar ; ls lib")),
    0) ++ Seq(
    Cmd("RUN", "rm -rf user-files/* ; ls user-files"))
}



// Docker on AWS ECR

import com.amazonaws.regions.{Region, Regions}

enablePlugins(EcrPlugin)
region in Ecr := Region.getRegion(Regions.EU_CENTRAL_1)
repositoryName in Ecr := (packageName in Docker).value
localDockerImage in Ecr := (packageName in Docker).value + ":" + (version in Docker).value
version in Ecr := (version in Docker).value
repositoryTags in Ecr += version.value.replace('+', '-')

// Create the repository before authentication takes place (optional)
login in Ecr := ((login in Ecr) dependsOn (createRepository in Ecr)).value

// Authenticate and publish a local Docker image before pushing to ECR
push in Ecr := ((push in Ecr) dependsOn(publishLocal in Docker, login in Ecr)).value

// test jar, needed for perf-docker
sourceDirectory in Compile := (sourceDirectory in Gatling).value


javaOptions in run ++= Seq(
  "-XX:+UseG1GC",
  "-XX:MaxGCPauseMillis=30",
  "-XX:G1HeapRegionSize=16m",
  "-XX:InitiatingHeapOccupancyPercent=75",
  "-XX:+ParallelRefProcEnabled",
  "-XX:+UnlockExperimentalVMOptions",
  "-XX:+PerfDisableSharedMem",
  "-XX:+AggressiveOpts",
  "-XX:+OptimizeStringConcat",
  "-XX:+HeapDumpOnOutOfMemoryError",
  "-XX:+PrintGCTimeStamps", // JDK8
  "-XX:+UseCGroupMemoryLimitForHeap",
  "-XX:MaxRAMFraction=1",
  "-XX:InitialRAMFraction=2",
  "-XshowSettings:vm",
  "-javaagent:/opt/gatling/jmxtrans-agent-1.2.6.jar=/opt/gatling/jmxtrans-agent.xml"
)

// should result in configs in docker
javaOptions in Universal ++= {
  (javaOptions in run).value ++
    Seq(
      //      "-J-XX:FlightRecorderOptions=defaultrecording=true,dumponexit=true,dumponexitpath=/opt/docker/jfr/recording.jfr,settings=profile,disk=true,maxage=12h,repository=/opt/docker/jfr/"
    )
}.map(opt => s"-J$opt") ++ Seq(
  "-Djava.net.preferIPv4Stack=true",
  "-Djava.net.preferIPv6Addresses=false",
  "-Dcom.sun.management.jmxremote.authenticate=false",

)

/** Gatling own startup script
  * DEFAULT_JAVA_OPTS="-server"
  * DEFAULT_JAVA_OPTS="${DEFAULT_JAVA_OPTS} -Xmx1G"
  * DEFAULT_JAVA_OPTS="${DEFAULT_JAVA_OPTS} -XX:+UseG1GC -XX:MaxGCPauseMillis=30 -XX:G1HeapRegionSize=16m -XX:InitiatingHeapOccupancyPercent=75 -XX:+ParallelRefProcEnabled"
  * DEFAULT_JAVA_OPTS="${DEFAULT_JAVA_OPTS} -XX:+PerfDisableSharedMem -XX:+AggressiveOpts -XX:+OptimizeStringConcat"
  * DEFAULT_JAVA_OPTS="${DEFAULT_JAVA_OPTS} -XX:+HeapDumpOnOutOfMemoryError"
  * DEFAULT_JAVA_OPTS="${DEFAULT_JAVA_OPTS} -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false"
  */
