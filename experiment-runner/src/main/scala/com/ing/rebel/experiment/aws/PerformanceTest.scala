package com.ing.rebel.experiment.aws

import java.util.UUID

import com.ing.rebel.experiment.aws.AwsLib.{ConfigHelper, defaultContainerDefinition, repository, startTaskAsync}
import software.amazon.awssdk.services.ecs.model._
import com.ing.rebel.experiment.aws.AwsLib.{defaultContainerDefinition, getEc2Instance, getHostPort, logger, repository, startTaskAsync}
import io.monadless.monix.MonadlessTask.{lift, unlift}
import org.apache.cassandra.tools.NodeProbe
import com.ing.rebel.experiment.aws.AwsLib._
import com.ing.rebel.experiment.aws.Db.cassandraPort
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import software.amazon.awssdk.services.ecs.model.{Task => EcsTask, _}

import scala.concurrent.duration._
import scala.collection.JavaConverters._


object PerformanceTest {
  val defaultSignalPort = 8080

  def start(uniqueId: String, description: String, simulationClass: String, nodeIpAddresses: Seq[String], metricsIPAddress: String,
            metricsGraphitePort: Int, containerInstanceArn: String, extraNodes: Seq[String], leader: Boolean, extraConfig: Map[String, String] = Map()): Task[EcsTask] = {

    val ipAddressesCommands: Seq[String] = nodeIpAddresses.zipWithIndex.map {
      case (ip, index) =>
        s"-Drebel.baseurls.$index=http://$ip:${Seed.defaultAppHttpPort}"
    }
    val ipAddressesOpts = ipAddressesCommands.mkString(" ")

    val extraNodesJavaOpts: String = extraNodes.zipWithIndex.map {
      case (ip, index) =>
        s"-Drebel.scenario.other-node-uris.$index=$ip"
    }.mkString(" ")


    val graphiteConfig: Map[String, String] = Map(
      "rebel.scenario.leader" -> leader.toString,
      "gatling.data.writers.0" -> "console",
      "gatling.data.writers.1" -> "file",
      "gatling.data.writers.2" -> "graphite",
      "gatling.data.graphite.host" -> metricsIPAddress,
      "gatling.data.graphite.port" -> metricsGraphitePort.toString,
      "gatling.data.graphite.rootPathPrefix" -> s"gatling.${UUID.randomUUID()}" // TODO find a way to use known id here
    )

    val config: String = ConfigHelper.configToJavaOpts(graphiteConfig ++ extraConfig)

    val performanceDefinition: ContainerDefinition =
      defaultContainerDefinition("performance").toBuilder
        .name("performance")
        .image(s"$repository/generated-performancetest:latest")
        .memory(AwsLib.addressableContainerInstanceMemory - Metrics.memory)
        .environment(
          KeyValuePair.builder().name("JAVA_OPTS").value(s"$ipAddressesOpts $extraNodesJavaOpts $config").build(),
          // needed for account creation before test (AllToAllSimulation)
          KeyValuePair.builder().name("REBEL_HOST").value(nodeIpAddresses.head).build()
        ).command(
        "-rf", s"results/$description",
        "-rd", description,
        "-s", simulationClass)
          .portMappings(
            // port for signal
            PortMapping.builder().containerPort(defaultSignalPort).hostPort(defaultSignalPort).build()
          )
        .mountPoints(
          MountPoint.builder().containerPath("/opt/gatling/user-files/").readOnly(false).sourceVolume("user-files").build(),
          MountPoint.builder().containerPath("/opt/gatling/results").readOnly(false).sourceVolume("gatling-results").build()
        ).extraHosts(HostEntry.builder().hostname("metrics").ipAddress(metricsIPAddress).build())
          .build()

    startTaskAsync(uniqueId, "performance", performanceDefinition,
      Seq(containerInstanceArn),
      Seq(Volume.builder().name("user-files").host(HostVolumeProperties.builder().sourcePath("/opt/gatling/user-files/").build()).build(),
        Volume.builder().name("gatling-results").host(HostVolumeProperties.builder().sourcePath("/opt/gatling/results").build()).build()))
      .flatMap(_._2)
  }
}
