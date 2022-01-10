package com.ing.rebel.experiment.aws

import com.ing.rebel.experiment.aws.AwsLib.{defaultContainerDefinition, getEc2Instance, getHostPort, repository, startTaskAsync}
import com.typesafe.scalalogging.LazyLogging
import io.monadless.monix.MonadlessTask.{lift, unlift}
import monix.eval.Task
import org.apache.cassandra.tools.NodeProbe
import software.amazon.awssdk.services.ecs.model.{Task => EcsTask, _}
import scala.reflect.ClassTag

import scala.collection.JavaConverters._
import scala.concurrent.duration._

object Db extends LazyLogging {
  val cassandraPort = 9042
  val cassandraGossipPort = 7000
  val jxmPort = 7199

  def dbSeedDefinition(broadcastIp: String, metricsIp: String, externalIp: String): ContainerDefinition =
    defaultContainerDefinition("db").toBuilder
      .name("db")
      .image(s"$repository/cassandra_reporting:latest")
      .memory(AwsLib.addressableContainerInstanceMemory)
      .portMappings(
        PortMapping.builder().containerPort(cassandraPort).hostPort(cassandraPort).build(),
        PortMapping.builder().containerPort(cassandraGossipPort).hostPort(cassandraGossipPort).build(),
        PortMapping.builder().containerPort(jxmPort).hostPort(jxmPort).build()
      ).environment(
      KeyValuePair.builder().name("CASSANDRA_BROADCAST_ADDRESS").value(broadcastIp).build(),
      KeyValuePair.builder().name("LOCAL_JMX").value("no").build(),
      // Make sure JMX is available remotely to allow NodeTool to connect
      KeyValuePair.builder().name("JVM_EXTRA_OPTS").value("-Dcom.sun.management.jmxremote= -Dcom.sun.management.jmxremote.port=7199 -Dcom.sun.management.jmxremote.rmi.port=7199 -Dsun.rmi.level=FINEST" +
        s" -Djava.rmi.server.hostname=$externalIp -Dcom.sun.management.jmxremote.local.only=false -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false -Djava.rmi.server.logCalls=true").build())
      .extraHosts(HostEntry.builder().hostname("metrics").ipAddress(metricsIp).build())
      .build()

  def dbNodeDefinition(broadcastIp: String, seedIp: String, metricsIp: String, externalIp: String): ContainerDefinition = {
    val definition = dbSeedDefinition(broadcastIp, metricsIp, externalIp)
    definition.toBuilder
      .environment((definition.environment().asScala :+ KeyValuePair.builder().name("CASSANDRA_SEEDS").value(seedIp).build()).asJava)
      .build()
  }

  def start(uniqueId: String, metricsIp: String, metricsStatsdPort: Int, containerInstanceArn: String): Task[EcsTask] =
  // Task to make sure initialization is also delayed until execution
    getEc2Instance(containerInstanceArn).flatMap { instance =>
      val broadcastIp = instance.privateIpAddress
      startTaskAsync(uniqueId, "db", dbSeedDefinition(broadcastIp, metricsIp, instance.publicIpAddress()), Seq(containerInstanceArn)).flatMap(_._2)
    }

  def startExtraNode(uniqueId: String, seedIp: String, metricsIp: String, metricsStatsdPort: Int, containerInstanceArn: String): Task[EcsTask] =
    getEc2Instance(containerInstanceArn).flatMap { instance =>
      val broadcastIp = instance.privateIpAddress
      startTaskAsync(uniqueId, "db", dbNodeDefinition(broadcastIp, seedIp, metricsIp, instance.publicIpAddress()), Seq(containerInstanceArn)).flatMap(_._2)
    }

  def delayUntilClusterAlive(seedTask: EcsTask, tasks: Set[EcsTask]): Task[Set[String]] = lift {
    logger.info("Getting all C* host/ips")

    val (seedIp, seedPort) = {
      val dbIp: String = unlift(getEc2Instance(seedTask).map(_.publicIpAddress))
      val dbPort = getHostPort(seedTask, cassandraPort)
      (dbIp, dbPort)
    }

    // + 1 to account for seed
    val requestedDbSize = tasks.size + 1

    // make sure we can actually connect
    val connectedCluster: NodeProbe = unlift(Task {
      logger.info("Trying to connect to C* cluster {} {}", seedIp, seedPort)
      val nodeProbe = new NodeProbe(seedIp, Db.jxmPort)

      logger.info(s"C* connection on $seedIp:$seedPort is Alive!")
      nodeProbe
    }.delayExecution(2.second).transform(a => a, e => {
      logger.error("Error during connecting C*: {}", e.getMessage)
      throw e
    }).onErrorRestart(Long.MaxValue))
    //

    logger.info("Session connecting: {}", connectedCluster)

    // restart until all database servers are connected
    val allAlive = unlift(Task {
      logger.info("Querying state {}", connectedCluster.getClusterName)
      val hosts = connectedCluster.getLiveNodes.asScala
      logger.info("Connected count {}, requested {}", hosts.size, requestedDbSize)
      hosts
    }.delayExecution(2.seconds).map { h => logger.info("hosts {}", h); h }
      .restartUntil(_.size == requestedDbSize))

    val broadcastIps = allAlive.toSet

    logger.info("All alive: {}", broadcastIps)

    connectedCluster.close()

    broadcastIps
  }
}