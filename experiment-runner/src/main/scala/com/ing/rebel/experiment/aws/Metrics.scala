package com.ing.rebel.experiment.aws

import com.ing.rebel.experiment.aws.AwsLib.{defaultContainerDefinition, repository, startTaskAsync}
import monix.eval.Task
import software.amazon.awssdk.services.ecs.model.{Task => EcsTask, _}

object Metrics {
  //    def graphiteDataLocation(uniqueId: String) = s"/opt/graphite/storage/${uniqueId.replaceAll("\\W+", "")}/whisper"

  val influxDbHostPort = 8086
  val graphitePort = 2003

  val grafanaDbDir = "/var/lib/grafana/"
  val memory = 1000 // mb

  def start(uniqueId: String, containerInstanceArn: String): Task[EcsTask] = {
    val metricsDefinition = defaultContainerDefinition("metrics").toBuilder
      .name("metrics")
      //        .image("kamon/grafana_graphite")
      .image(s"$repository/grafana_influxdb")
      .memory(memory)
      .portMappings(
        PortMapping.builder().containerPort(3003).hostPort(3003).build(), // grafana
        PortMapping.builder().containerPort(8083).hostPort(8083).build(), // influx admin
        PortMapping.builder().containerPort(8888).hostPort(8888).build(), // chronograf admin
        PortMapping.builder().containerPort(8086).hostPort(influxDbHostPort).build(),
        //        PortMapping.builder().containerPort(8088).hostPort(8088), // used for backup protocol
        PortMapping.builder().containerPort(8089).hostPort(8089).protocol(TransportProtocol.UDP).build(),
        //        PortMapping.builder().containerPort(8126),
        PortMapping.builder().containerPort(8125).hostPort(8125).protocol(TransportProtocol.UDP).build(),
        PortMapping.builder().containerPort(2003).hostPort(graphitePort).build(), //  graphite in TCP
        PortMapping.builder().containerPort(2004).hostPort(2004).build() //  graphite in UDP
//      ).mountPoints(
      //        MountPoint.builder().containerPath(grafanaDbDir).readOnly(false).sourceVolume("grafana-data")
      //        MountPoint.builder().containerPath("/opt/graphite/storage/whisper").readOnly(false).sourceVolume("graphite-data")
    ).environment(KeyValuePair.builder().name("GF_DATABASE_TYPE").value("sqlite3").build())
      .build()

    val volumes: List[Volume] = List(
      //        Volume().name("grafana-data").host(new HostVolumeProperties.builder().sourcePath(grafanaDbDir))
      //        Volume().name("graphite-data").host(new HostVolumeProperties.builder().sourcePath(graphiteDataLocation(uniqueId)))
    )
    startTaskAsync(uniqueId, "metrics", metricsDefinition, Seq(containerInstanceArn), volumes).flatMap(_._2)
  }
}
