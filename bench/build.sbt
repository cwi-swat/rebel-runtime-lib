name := "generated-microbenchmark"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

enablePlugins(JmhPlugin)

// enable JMH in test folder (also to make IntelliJ happy, since it can't do test->compile dependencies ATM)
sourceDirectory in Jmh := (sourceDirectory in Test).value
classDirectory in Jmh := (classDirectory in Test).value
dependencyClasspath in Jmh := (dependencyClasspath in Test).value
// rewire tasks, so that 'jmh:run' automatically invokes 'jmh:compile' (otherwise a clean 'jmh:run' would fail)
compile in Jmh := (compile in Jmh).dependsOn(compile in Test).value
run in Jmh := (run in Jmh).dependsOn(Keys.compile in Jmh).evaluated

libraryDependencies += "org.iq80.leveldb" % "leveldb" % "0.7" // % "test"

libraryDependencies += "org.apache.commons" % "commons-math3" % "3.6.1"

fork in Test := true
fork in Compile := true

//mainClass in (Jmh, run) := Some("com.ing.rebel.CustomRunner")

javaOptions in run ++= Seq(
  //  "-Xms3G",
  //  "-Xmx3G",
  "-XX:+UseG1GC",
  "-XX:MaxGCPauseMillis=100",
  "-XX:+UnlockExperimentalVMOptions",
  //  "-XX:G1MixedGCCountTarget=16",
  //  "-Xloggc:gc_g1_3g_85_5_5000_1000_120_100ms.log",
  //  "-XX:+PrintGCDetails",
  //  "-XX:+PrintGCDateStamps",
  //  "-XX:+PrintGCTimeStamps", // JDK8
  //  "-Xlog:gc", // JDK9
  //  "-Xlog:gc*", // JDK9
  //  "-XX:+UseContainerSupport",
  //  "-XX:MinRAMFraction=1",
  "-XX:MaxRAMPercentage=96",
  //  "-XX:MaxRAMFraction=1",
  //  "-XX:InitialRAMFraction=2",
  "-XshowSettings:vm",
  //  "-XX:+UnlockCommercialFeatures",
  "-XX:+FlightRecorder",
  //  "-XX:StartFlightRecording=filename=recording.jfr",
  "-XX:+UnlockDiagnosticVMOptions",
  "-XX:+DebugNonSafepoints"
  //  "-Djava.security.debug=\"provider,engine=SecureRandom\""
)

// should result in configs in docker
javaOptions in Universal ++= {
  (javaOptions in run).value ++
    Seq(
      //      "-XX:StartFlightRecording=defaultrecording=true,dumponexit=true,dumponexitpath=/opt/docker/jfr/,settings=profile,disk=true,maxage=12h,repository=/opt/docker/jfr/"
      "-XX:StartFlightRecording=dumponexit=true,filename=/opt/docker/jfr/recording.jfr,settings=profile,disk=true,maxage=12h"
    )
}.map(opt => s"-J$opt")

// Docker
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)
//enablePlugins(AshScriptPlugin) // for compatibility with alpine linux without bash

maintainer in Docker := "Tim Soethout"
(version in Docker) := (version in Docker).value.replace('+', '-')
dockerUpdateLatest := true
//daemonUser in Docker := "app" // user in the Docker image which will execute the application (must already exist)
dockerBaseImage := "adoptopenjdk/openjdk11:jre-11.0.2.9" // Docker image to use as a base for the application image
//dockerExposedPorts in Docker := Seq(8080, 8001, 2551) // Ports to expose from container for Docker container linking
//dockerRepository := Some("docker.europe.intranet:5000") // Repository used when publishing Docker image
// entrypoint
mainClass in Compile := (mainClass in (Jmh, run)).value
// make sure sources are compiled and packaged, dependency in root/build.sbt enables this to work

// makes sure jmh generation process is done for docker image creation
packageBin in Compile := (packageBin in Jmh).value

// Docker on AWS ECR
import com.amazonaws.regions.{Region, Regions}
enablePlugins(EcrPlugin)
region in Ecr := Region.getRegion(Regions.EU_CENTRAL_1)
repositoryName in Ecr := (packageName in Docker).value
localDockerImage in Ecr := (packageName in Docker).value + ":" + (version in Docker).value
version in Ecr := (version in Docker).value

repositoryTags in Ecr += version.value
// Create the repository before authentication takes place (optional)
login in Ecr := ((login in Ecr) dependsOn (createRepository in Ecr)).value

// Authenticate and publish a local Docker image before pushing to ECR
push in Ecr := ((push in Ecr) dependsOn (publishLocal in Docker, login in Ecr)).value
