package com.ing.rebel.boot

import java.nio.file.{Files, Paths}
import java.security.{SecureRandom, Security}
import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown, Props}
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.pattern.AskTimeoutException
import akka.persistence.PersistentActor
import akka.stream.ActorMaterializer
import com.ing.rebel.RebelRestEndPoints
import com.ing.rebel.RebelRestEndPoints.RebelEndpointInfo
import com.ing.rebel.util.XoShiRo256StarStarRandomProvider
import com.ing.rebel.util.metrics.SystemInfoTest
import com.typesafe.config._
import kamon.Kamon
import sun.security.jca.Providers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.matching.Regex

object BootLib {
  val Opt: Regex =
    """(\S+)=(\S+)""".r

  def argsToOpts(args: Seq[String]): Map[String, String] =
    args.view.collect { case Opt(key, value) => key -> value }.toMap

  def applySystemProperties(options: Map[String, String]): Unit =
    for ((key, value) <- options if key startsWith "-D") {
      val k = key.substring(2)
      println(s"Applying $k = $value")
      System.setProperty(k, value)
    }

  def changeSecureRandomSecurityProvider(): Unit = {
    // Make sure fast SecureRandom impl is added to make UUID.randomUUID not block as much
    Security.insertProviderAt(XoShiRo256StarStarRandomProvider, 1)
  }
}

trait BootLib {
  def endpointInfos: Set[RebelEndpointInfo]

  lazy val hostIp: String = HostIP.load().getOrElse("localhost")

  // enable docker stuff
  lazy val config: Config = {
    val configFromDisk: Config = ConfigFactory.load()
    val rebelConfig = configFromDisk.getConfig("rebel")
    val clusteringIp: ConfigValue = rebelConfig.getValue("clustering.ip")
    val clusteringPort = rebelConfig.getValue("clustering.port")
    val clusteringBindPort: ConfigValue = rebelConfig.getValue("clustering.bind-port")
    val ipValue: ConfigValue = ConfigValueFactory.fromAnyRef(hostIp)
    configFromDisk
      // published hostname
      .withValue("akka.remote.netty.tcp.hostname", clusteringIp)
      .withValue("akka.remote.netty.tcp.port", clusteringPort)
      // real hostname to bind on
      .withValue("akka.remote.netty.tcp.bind-hostname", ipValue)
      .withValue("akka.remote.netty.tcp.bind-port", clusteringBindPort)
      // for artery
      .withValue("akka.remote.artery.canonical.hostname", clusteringIp) // external (logical) hostname
      .withValue("akka.remote.artery.canonical.port", clusteringPort) // external (logical) port
      // real hostname to bind on
      .withValue("akka.remote.artery.bind.hostname", ipValue) // internal (bind) hostname
      .withValue("akka.remote.artery.bind.port", clusteringBindPort) // internal (bind) port
  }

  // we need an ActorSystem to host our application in
  implicit lazy val system: ActorSystem = ActorSystem("rebel-system", config)
  implicit lazy val actorMaterializer: ActorMaterializer = ActorMaterializer()

  sys.addShutdownHook {
    println(s"Shutting down $system, waiting max 60 seconds")
    val result = Await.result(system.terminate(), 60.seconds)
    println(s"Completed shutdown of $system; $result")
  }

  def enableKamon(): Unit = {
    Kamon.init()
    // No longer necessary in Kamon 2.0?
//    SystemMetrics.startCollecting()
//    Kamon.addReporter(new InfluxDBReporter())
    //    Kamon.addReporter(new StatsDReporter())
  }

  def logSystemInformation(): Unit = {
    if (Files.exists(Paths.get("/proc/sys/kernel/random/entropy_avail"))) {
      val source = scala.io.Source.fromFile("/proc/sys/kernel/random/entropy_avail")
      val lines = try source.mkString finally source.close()
      system.log.info("/proc/sys/kernel/random/entropy_avail: {}", lines)
    }

    SystemInfoTest.main(Array())
    system.log.info("available processors: {}", Runtime.getRuntime.availableProcessors())
  }

  def logApplicationInformation(cluster: Cluster, rebelConfig: Config): Unit = {
    val system = cluster.system
    system.log.info("Running rebel-lib version: {}", com.ing.rebel.util.BuildInfo.toString)

    system.log.info(
      s"""Actor system `${system.name}` (${cluster.selfAddress}) started
         | with hostname `$hostIp`
         | with seed nodes `${cluster.settings.SeedNodes}`
         | with bind-hostname (port) `${rebelConfig.getString("clustering.ip")}` (`${rebelConfig.getString("clustering.bind-port")}`)""".stripMargin)

    system.log.info("Rebel config:\n {}", config.getObject("rebel").render(ConfigRenderOptions.concise().setFormatted(true)))

    val sr = new SecureRandom()
    system.log.info("Using SecureRandom with {} and {}", sr.getAlgorithm, sr.getProvider.getName)

    import scala.collection.JavaConverters._
    val secureRandomAlgorithms = for {
      p <- Providers.getProviderList.providers.asScala
      s <- p.getServices.asScala
      if s.getType == "SecureRandom"
    } yield s.getAlgorithm
    system.log.info(s"Available SecureRandom Algorithms: $secureRandomAlgorithms")
  }

  def start(): Unit = {
    BootLib.changeSecureRandomSecurityProvider()

    enableKamon()

    system.registerOnTermination {
      system.log.info("Shutting down actorsystem.")
    }

    logSystemInformation()

    val rebelConfig: Config = system.settings.config getConfig "rebel"
    val visualisationEnabled: Boolean = rebelConfig getBoolean "visualisation.enabled"

    val cluster: Cluster = Cluster(system)

    logApplicationInformation(cluster, rebelConfig)

    if (cluster.settings.SeedNodes.isEmpty) {
      system.log.info("No seed nodes configured, creating singleton cluster with self")
      cluster.join(cluster.selfAddress)
    }

    // before starting endpoints, make sure persistence is working
    awaitPersistence(system)

    // Start all sharding
    endpointInfos.foreach(_.specificationShard.start())

    val restPort = rebelConfig getInt "endpoints.port"
    val restHost = system.settings.config getConfig "rebel" getString "endpoints.host"
    val endpointsBind: Future[Http.ServerBinding] = Http().bindAndHandle(new RebelRestEndPoints(system, endpointInfos).flow, interface = restHost, port = restPort)
    system.log.info("Started REST end points on http://{}:{}", restHost, restPort)

    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseServiceUnbind, "http_shutdown") { () =>
      println(s"CoordinatedShutdown.PhaseServiceUnbind: unbinding $endpointsBind")
      import system.dispatcher
      for {
        _ <- endpointsBind.flatMap(_.unbind())
        _ = println(s"Shutdown (all) http binding")
      } yield Done
    }
  }

  def awaitPersistenceProps: Props = Props(new PersistentActor {
    val persistenceId: String = "persistenceInit"

    def receiveRecover: Receive = {
      case _ =>
    }

    def receiveCommand: Receive = {
      case msg =>
        persist(msg) { _ =>
          sender() ! msg
          context.stop(self)
        }
    }
  })

  def awaitPersistence(system: ActorSystem): Unit = {
    system.log.info("starting awaitPersistenceInit")
    import akka.pattern.ask
    import system.dispatcher
    val t0 = System.nanoTime()
    val persistenceStarted: Future[String] = system.actorOf(awaitPersistenceProps, "persistenceInit").ask("hello")(30.seconds).mapTo[String].recoverWith {
      case _: AskTimeoutException => system.actorOf(awaitPersistenceProps, "persistenceInit-1").ask("hello")(30.seconds).mapTo[String]
    }
    try {
      Await.result(persistenceStarted, 120.seconds)
      system.log.info("awaitPersistenceInit took {} ms {}", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0), system.name)
    } catch {
      case e: Throwable => system.log.error("Something went wrong, or taking too long with persistence init; continuing: {}", e)
    }


  }
}
