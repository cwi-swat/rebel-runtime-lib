import com.typesafe.sbt.packager.docker.Cmd
import sbt.Def
//enablePlugins(AspectJWeaver)
//aspectjSettings
//javaOptions <++= AspectjKeys.weaverOptions in Aspectj
// Required for Kamon to work in sbt native packager (and docker image)
fork in run := true
enablePlugins(JavaAppPackaging, JavaAgent)

javaAgents += "org.aspectj" % "aspectjweaver" % "1.9.1"
javaOptions in Universal += "-Dorg.aspectj.tracing.factory=default"


// tests that depend on generated sources
lazy val IT = config("it") extend(Compile, Test) describedAs "Integration tests dependant on generated code"
lazy val testGeneratedSettings: Seq[Def.Setting[_]] = inConfig(IT)(Defaults.testSettings)
configs(IT)
testGeneratedSettings

// multi jvm tests
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

lazy val multiJvm = MultiJvm extend IT describedAs "multi JVM tests dependant on generated code"
lazy val multiJvmGeneratedSettings = inConfig(multiJvm)(testGeneratedSettings)
configs(multiJvm)
SbtMultiJvm.multiJvmSettings
multiJvmGeneratedSettings

// make sure that MultiJvm test are compiled by the default test compilation
//compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test)
// disable parallel tests
//parallelExecution in Test := false
// make sure that MultiJvm tests are executed by the default test target,
// and combine the results from ordinary test and multi-jvm tests
//executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
//  case (testResults, multiNodeResults) =>
//    val overall =
//      if (testResults.overall.id < multiNodeResults.overall.id) multiNodeResults.overall else testResults.overall
//    Tests.Output(overall,
//      testResults.events ++ multiNodeResults.events,
//      testResults.summaries ++ multiNodeResults.summaries)
//}
//javaOptions in run ++= Seq("-Xms128m", "-Xmx1024m")
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
      //disable for now
      "-XX:StartFlightRecording=dumponexit=true,filename=/opt/docker/jfr/recording.jfr,settings=profile,disk=true,maxage=12h"
    )
}.map(opt => s"-J$opt")


//// Docker image
val RebelDocker = config("docker") extend Compile

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

maintainer in RebelDocker := "Tim Soethout"
version in Docker := "latest"
//daemonUser in Docker := "app" // user in the Docker image which will execute the application (must already exist)
dockerBaseImage := "adoptopenjdk/openjdk14" // Docker image to use as a base for the application image
dockerExposedPorts in RebelDocker := Seq(8080, 8001, 2551) // Ports to expose from container for Docker container linking
//dockerRepository := Some("docker.europe.intranet:5000") // Repository used when publishing Docker image
// entrypoint
mainClass in Compile := Some("com.ing.corebank.rebel.Boot")

daemonUser in RebelDocker := "root"
daemonGroup in RebelDocker := (daemonUser in RebelDocker).value

daemonUser in Docker := "root"
// hack to insert at correct location
dockerCommands := dockerCommands.value.patch(4, Seq(
//  Cmd("VOLUME", "/opt/docker/jfr")

//dockerCommands ++= Seq(
  //  Cmd("RUN", "apk -U add haveged openrc && rc-service haveged start && rc-update add haveged"), // makes sure enough entropy is generated
//  maybe not necessary for jdk 14?
//  Cmd("RUN", """sed -i 's/securerandom.source=file:\/dev\/random/securerandom.source=file:\/dev\/.\/urandom/g' $JAVA_HOME/conf/security/java.security"""),
  Cmd("RUN", "mkdir /opt/docker/jfr"),
  Cmd("RUN", "chown -R root:root /opt/docker/jfr"),
  Cmd("RUN", "chmod 777 -R /opt/docker"),
  Cmd("VOLUME", "/opt/docker/jfr"),
//)
), 0)

// Docker on AWS ECR
import com.amazonaws.regions.{Region, Regions}

enablePlugins(EcrPlugin)
region in Ecr := Region.getRegion(Regions.EU_CENTRAL_1)
repositoryName in Ecr := (packageName in RebelDocker).value
localDockerImage in Ecr := (packageName in RebelDocker).value + ":" + (version in RebelDocker).value
version in Ecr := (version in Docker).value
repositoryTags in Ecr += version.value.replace('+', '-')

// Create the repository before authentication takes place (optional)
login in Ecr := ((login in Ecr) dependsOn (createRepository in Ecr)).value

// Authenticate and publish a local Docker image before pushing to ECR
push in Ecr := ((push in Ecr) dependsOn(publishLocal in Docker, login in Ecr)).value

//enablePlugins(YourKit)