package com.ing.rebel.experiment.aws

import com.ing.rebel.experiment.aws.AwsLib.{ConfigHelper, defaultContainerDefinition, getEc2Instance, repository, startTaskAsync, _}
import com.typesafe.config.Config
import monix.eval.Task
import software.amazon.awssdk.services.ecs.model.{Task => EcsTask, _}

import scala.collection.JavaConverters._

object Seed {
  val defaultAppHttpPort = "8080"

  def seedDefinition(clusteringIp: String, clusteringPort: Int, dbPort: Int, dbIpAddress: String, metricsIp: String,
                     metricsStatsdPort: Int, extraAppConfig: Config): ContainerDefinition = {

    val configMap: Map[String, String] = ConfigHelper.configToMap(extraAppConfig) +
      // be extra sure it uses the non-blocking random
      ("java.security.egd" -> "file:/dev/./urandom")
    val configOpts: String = ConfigHelper.configToJavaOpts(configMap)

    defaultContainerDefinition("seed").toBuilder
      .name("seed")
      .image(s"$repository/simple-transaction:latest")
      .memoryReservation(addressableContainerInstanceMemory - 500)
      .memory(addressableContainerInstanceMemory)
      .environment(
        KeyValuePair.builder().name("CASSANDRA_HOST").value(dbIpAddress).build(),
        KeyValuePair.builder().name("CASSANDRA_PORT").value(dbPort.toString).build(),
        KeyValuePair.builder().name("JAVA_OPTS").value(configOpts).build()
      )
      .portMappings(
        PortMapping.builder().containerPort(80).hostPort(80).build(),
        PortMapping.builder().containerPort(8080).hostPort(8080).build(),
        PortMapping.builder().containerPort(2551).hostPort(2551).build())
      .command(
        s"-Drebel.clustering.ip=$clusteringIp",
        s"-Drebel.clustering.port=$clusteringPort",
        "-Drebel.sync.type=twophasecommit",
        "-Drebel.visualisation.enabled=false",
        //          s"-Dkamon.statsd.hostname=$metricsIp",
        //          s"-Dkamon.statsd.port=$metricsStatsdPort",
        s"-Dkamon.influxdb.hostname=$metricsIp",
        s"-Dkamon.influxdb.port=${Metrics.influxDbHostPort}",
        "-Dkamon.influxdb.database=telegraf")
      .mountPoints(
        MountPoint.builder().containerPath("/opt/docker/jfr").readOnly(false).sourceVolume("jfr").build()
      ).build()
  }

  def start(uniqueId: String, dbPort: Int, dbIpAddress: String, metricsIp: String, metricsStatsdPort: Int,
            containerInstanceArn: String, extraAppConfig: Config): Task[(EcsTask, Task[EcsTask])] = {
    getEc2Instance(containerInstanceArn).flatMap { instance =>
      val clusteringIp = instance.privateIpAddress()

      // SEED
      val seedDef = seedDefinition(clusteringIp, 2551, dbPort, dbIpAddress, metricsIp, metricsStatsdPort, extraAppConfig)

      startTaskAsync(uniqueId, "seed", seedDef, Seq(containerInstanceArn),
        volumes = Seq(Volume.builder().name("jfr").host(HostVolumeProperties.builder().sourcePath(s"/opt/docker/jfr/jfr-$uniqueId").build()).build()))
    }
  }
}

object Node {
  //noinspection ScalaStyle
  def start(uniqueId: String, dbPort: Int, dbIpAddress: String, seedIpAddress: String, seedPort: Int,
            metricsIp: String, metricsStatsdPort: Int, containerInstanceArn: String, extraAppConfig: Config): Task[(EcsTask, Task[EcsTask])] = {
    getEc2Instance(containerInstanceArn).flatMap { instance =>
      val clusteringIp = instance.privateIpAddress()

      val definition = Seed.seedDefinition(clusteringIp, 2551, dbPort, dbIpAddress, metricsIp, metricsStatsdPort, extraAppConfig)
      val nodeDefinition: ContainerDefinition = definition.toBuilder
        .name("node").logConfiguration(awsLogConfiguration("node"))
        .command((definition.command().asScala :+ s"-Dakka.cluster.seed-nodes.0=akka://rebel-system@$seedIpAddress:$seedPort").asJava)
        .environment((definition.environment().asScala :+ KeyValuePair.builder().name("CASSANDRA_CREATE").value("false").build()).asJava)
        .build()

      startTaskAsync(uniqueId, "node", nodeDefinition, Seq(containerInstanceArn),
        volumes = Seq(Volume.builder().name("jfr").host(HostVolumeProperties.builder().sourcePath(s"/opt/docker/jfr/jfr-$uniqueId").build()).build()))
    }
  }
}