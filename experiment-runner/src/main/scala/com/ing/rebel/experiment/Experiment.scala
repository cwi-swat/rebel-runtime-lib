package com.ing.rebel.experiment

import java.nio.file

import ammonite.ops.ImplicitWd._
import ammonite.ops._
import com.ing.rebel.experiment.aws.AwsLib._
import com.ing.rebel.experiment.aws._
import com.ing.rebel.experiment.report.RunCombinedReport
import com.ing.{RebelExperimentPaths, Reports}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import io.circe.Encoder
import io.monadless.monix.MonadlessTask._
import monix.eval.Task
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ecs.model.{ContainerInstance, Task => EcsTask}

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}


case class TestRun(simulationClass: String, clusterSize: Int,
                   dbClusterSize: Int, testUsers: Int, awsInfo: String = AwsLib.instanceType,
                   uniqueId: String, performanceNodeSize: Int,
                   performanceThrottle: Long, performanceConfig: Config = ConfigFactory.empty(),
                   duration: FiniteDuration,
                   appConfig: Config = ConfigFactory.empty(), variantName: String = "") {
  assert(performanceNodeSize >= 1, "performanceNodeSize should be at least 1, since metrics have to run somewhere too")
  assert(dbClusterSize >= 3, "dbClusterSize should be at least 3, to make sure that Akka persistence can create write/read with quorum")
  // metrics run on first performanceNodes host
  val instancesRequired: Int = clusterSize + dbClusterSize + performanceNodeSize
  assert(instancesRequired <= 100, s"For now we can only address 100 container instances per experiment, so this ($instancesRequired) will not work for $uniqueId.")

  // poor mans scenario info
  // should be unique
  val description: String = s"$uniqueId-$simulationClass-u$testUsers-$awsInfo-n$clusterSize-c$dbClusterSize-p$performanceNodeSize"
}

object TestRun {
  import com.ing.{durationEncoder, configEncoder}
  implicit val encoder: Encoder[TestRun] = io.circe.generic.semiauto.deriveEncoder
}

case class ExperimentResult(success: Boolean = true)

class Experiment(test: TestRun) {
  val uniqueId: String = test.uniqueId

  implicit private val logger: Logger =
    Logger(LoggerFactory.getLogger(getClass.getName + uniqueId))

  def setupAllAppInstances(instancesToUse: Seq[ContainerInstance])
  : Task[(Seq[ContainerInstance], Int, String, String, Seq[String], Seq[String], Set[EcsTask])] = {

    val setup: Task[(Seq[ContainerInstance], Seq[ContainerInstance], Seq[ContainerInstance])] = Task {
      val nrOfNodes = test.clusterSize - 1 // seed is always started
      assert(test.clusterSize >= 1, "Seed node should always be started.")
      assert(test.dbClusterSize >= 0, "Number of Cassandra nodes should always be positive.")
      assert(instancesToUse.size >= test.instancesRequired,
        s"Test requires ${test.instancesRequired} container instances, but got ${instancesToUse.size}")

      logger.info(s"uniqueId: $uniqueId")

      //      reserveContainerInstances(instancesToUse, uniqueId)
      val Seq(performanceInstances, dbInstances, appInstances) =
        recursiveTake(instancesToUse, Seq(test.performanceNodeSize, test.dbClusterSize, test.clusterSize))
      logger.info("Performance instances:  {}\n\t db instances: {}\n\t appInstances: {}",
        performanceInstances.map(_.containerInstanceArn()),
        dbInstances.map(_.containerInstanceArn()),
        appInstances.map(_.containerInstanceArn())
      )

      (performanceInstances, dbInstances, appInstances)
    }

    lift {
      val (performanceInstances, dbInstances, appInstances) = unlift(setup)
      val metricsInstance = performanceInstances.head
      logger.info("Metric instance: {}", metricsInstance.containerInstanceArn())
      val (publicMetricsIp, metrics, metricsGraphitePort, metricsStatsDPort, privateMetricsIp) = unlift(startMetrics(metricsInstance))
      val (allDbTasks, dbPort, dbIpAddress) = unlift(startDb(dbInstances, test.dbClusterSize, metricsStatsDPort, privateMetricsIp))
      val (allAppTasks, allAppIps) = unlift(startApplication(appInstances, test.clusterSize,
        metricsStatsDPort, privateMetricsIp, dbPort, dbIpAddress))
      val tasks = Set(metrics) ++ allDbTasks ++ allAppTasks
      val allAppPublicIps = unlift(Task.gather(allAppTasks.map(t => AwsLib.getEc2Instance(t).map(_.publicIpAddress()))))
      //      val someValue = unlift(delayUntilAvailable(allAppPublicIps))

      (performanceInstances, metricsGraphitePort, publicMetricsIp, privateMetricsIp, allAppIps, allAppPublicIps, tasks)
    }.delayResultBySelector(s => delayUntilAvailable(s._6))
  }

  def delayUntilAvailable(allAppIps: Seq[String]): Task[Unit] = Task.defer {
    logger.info(s"Delaying until all apps are available: $allAppIps")
    val results: Seq[Task[Boolean]] = allAppIps.map(ip =>
      // keep retrying
      checkIfAlive(ip).delayExecution(2.second).onErrorRestart(Long.MaxValue).restartUntil(a => a)
    )
    val availableTask = Task.gather(results).timeout(test.clusterSize * 1.minute + 1.minute)
    availableTask.map { _ =>
      logger.info(s"Started all and available: $allAppIps")
      ()
    }
  }

  private def checkIfAlive(appIp: String): Task[Boolean] = Task {
    logger.debug(s"hitting http://$appIp:${Seed.defaultAppHttpPort}")
    //    Http().singleRequest(HttpRequest(uri = appIp)).map(_.status.isSuccess())
    val string = scalaj.http.Http(s"http://$appIp:${Seed.defaultAppHttpPort}").asString
    //    logger.warn(string.toString)
    if(string.is2xx) {
      logger.info("App {} is alive", appIp)
    }
    string.is2xx
  }

  def runPerformanceTestAndGatherResults(performanceInstances: Seq[ContainerInstance], resultsDir: Path,
                                         appTasks: Set[EcsTask], publicMetricsIp: String, privateMetricsIp: String, allAppIps: Seq[String],
                                         metricsGraphitePort: Int, outputDir: Path): Task[(ExperimentResult, Set[EcsTask])] = {
    lift {
      logger.debug("Starting performance test on {}", performanceInstances)
      val (allFinishedPerformanceTestTasks, allPerformancePublicIps) = unlift(startPerformanceTest(performanceInstances, test.simulationClass,
        metricsGraphitePort, privateMetricsIp, allAppIps, outputDir, test))
      logger.info("Performance test finished, start gathering results")
      val allTasks = appTasks ++ allFinishedPerformanceTestTasks
      unlift(Task.gather(Set(
        Task.gather(allPerformancePublicIps.map(performancePublicIp => fetchGatlingReport(test.description, outputDir, performancePublicIp)))
          .map(_ => RunCombinedReport.createCombinedReport(outputDir.toString())).onErrorHandle(_ => "Error during gatling report"),
        gatherResults(publicMetricsIp, allTasks, test.description, outputDir)
      )))
      // don't fail on report creation
      unlift(Task(Reports.generateRReport(Reports.experimentReportFile, outputDir, "ExperimentReport")).onErrorFallbackTo(Task.now(())))
      (ExperimentResult(), allTasks)
    }
  }

  def experimentTask(instancesToUse: Seq[ContainerInstance], resultsDir: Path): Task[ExperimentResult] = {
    val result: Task[ExperimentResult] = lift {
      logger.info("Starting test {}", test.description)
      val (performanceInstances, metricsGraphitePort, publicMetricsIp, privateMetricsIp, allAppIps, allAppPublicIps, tasks) =
        unlift(setupAllAppInstances(instancesToUse))
      val outputDir = unlift(createOutputDir(test, resultsDir))
      logger.info("Created output Dir {}", outputDir.toString())
      val (expResult, allTasks) = unlift(runPerformanceTestAndGatherResults(performanceInstances, resultsDir, tasks,
        publicMetricsIp, privateMetricsIp, allAppIps, metricsGraphitePort, outputDir).
        // always try to get what you can
        onErrorHandleWith { error =>
        logger.error("Something went wrong during performance test or collecting results", error)
        gatherResults(publicMetricsIp, tasks, test.description, outputDir).
          map(_ => (ExperimentResult(), tasks))
      })
      val tasksToStop: Set[String] = allTasks.map(_.taskArn())
      val _ = unlift(stopTasks(tasksToStop))

      // make sure nodes are stopped
      unlift(Task.gather(tasks.map(task =>
        delayUntilInState(task.taskArn(), "STOPPED"))))
      allAppPublicIps.foreach { appIp =>
        try {
          grabJavaFlightRecorderFile(appIp, resultsDir)
        } catch{
          case e: Throwable =>
            logger.warn(s"Something went wrong during grabJavaFlightRecorderFile($appIp, $resultsDir); continuing", e)
        }
      }
      expResult
    }

    result
      .timeout(test.duration + 3.minutes * test.instancesRequired)
      .onErrorHandleWith { e =>
        logger.error(s"Error during experiment: $test", e)
        logger.error("Trying to clean up now for {}", uniqueId)
        cleanupAndStop(uniqueId).map(_ => stopTasksByExperimentId(test.uniqueId)).map(_ => ExperimentResult(false))
      }
      .doOnFinish { _ => cleanupAndStop(uniqueId) }
      .doOnCancel(cleanupAndStop(uniqueId))
  }

  def ssh(ipAddress: String, command: String): CommandResult = {
    %%('ssh, "-o StrictHostKeyChecking=no", "-o UserKnownHostsFile=/dev/null", s"ec2-user@$ipAddress", command)
  }

  def scp(fromPath: String, toPath: String): CommandResult = {
    %%('scp, "-o StrictHostKeyChecking=no", "-o UserKnownHostsFile=/dev/null", "-r",
      fromPath, toPath)
  }

  def rsync(peerIp: String, fromPath: String, toPath: String): CommandResult = {
    ssh(peerIp, "sudo yum -y install rsync")

    %%('rsync, "-avrze", "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null", "--progress", fromPath, toPath)
  }

  def grabJavaFlightRecorderFile(nodeIpAddress: String, outputDir: Path): Path = {
    //    Thread.sleep(2000)
    //    val jfrFileName = s"jfr-${test.uniqueId}/recording.jfr"
    val jfrPath = s"/opt/docker/jfr/jfr-${test.uniqueId}/"
    //    ssh(nodeIpAddress, s"docker cp $containerId:/opt/docker/recording.jfr ./$jfrFileName")
    // TODO reuse real dir
    val outputFile = outputDir / test.description / "jfrs" / nodeIpAddress
    mkdir(outputFile)
    //    val tempOutputFile = outputDir / test.description / s"$nodeIpAddress-temp.jfr"
    logger.info("Grabbing JFRs: {}", jfrPath)
    val copy = rsync(nodeIpAddress, s"ec2-user@$nodeIpAddress:$jfrPath", outputFile.toString)
    logger.debug("Copied: {}", copy.toString())
    outputFile
  }

  def startMetrics(containerInstance: ContainerInstance): Task[(String, EcsTask, Int, Int, String)] = {
    // METRICS
    //scp grafana/grafana.db ec2-user@35.158.83.128:
    //ssh ec2-user@35.158.83.128 sudo mv grafana.db /opt/grafana/data/
    getEc2Instance(containerInstance.containerInstanceArn()).flatMap {
      instance =>
        val publicMetricsIp: String = instance.publicIpAddress()
        val metricsConnectString: String = s"ec2-user@$publicMetricsIp"
        logger.info(s"copying grafana files to metrics server: $publicMetricsIp")
        // Try to do this, if dbfile is not there, silently fail, since it is only interesting when actually looking at the metrics live.
        Try {
          val result = scp(RebelExperimentPaths.grafanaDbFilePath.toString, metricsConnectString + ":").toString
          //        %%('scp, "-o StrictHostKeyChecking=no", "-o UserKnownHostsFile=/dev/null",
          //        RebelExperimentPaths.grafanaDbFilePath, metricsConnectString + ":").toString
          logger.debug(result)
          val result2 = ssh(publicMetricsIp,
            s"sudo mkdir --parents ${Metrics.grafanaDbDir}; sudo mv grafana.db ${Metrics.grafanaDbDir}").toString
          logger.debug(result2)
        }.recover { case t => logger.error("Could not add grafana db.", t) }

        logger.info("starting metrics")
        val metricsTask: Task[EcsTask] = Metrics.start(uniqueId, containerInstance.containerInstanceArn())
        metricsTask.flatMap(metrics => {
          val metricsGraphitePort = getHostPort(metrics, Metrics.graphitePort) // hard code for now // getHostPort(metrics, 2003)
          val metricsStatsdPort = getHostPort(metrics, 8125)
          val privateMetricsIp = getEc2Instance(metrics).map(_.privateIpAddress())
          logger.info(s"started metrics on $privateMetricsIp")
          privateMetricsIp.map(pip => (publicMetricsIp, metrics, metricsGraphitePort, metricsStatsdPort, pip))
        })
    }
  }

  def startDb(instances: Seq[ContainerInstance], cassandraNodeCount: Int, metricsStatsdPort: Int, metricsIp: String)
  : Task[(Seq[EcsTask], Int, String)] = {
    require(instances.size == cassandraNodeCount)

    if (cassandraNodeCount > 0) {
      // DB
      logger.info("starting db seed node")
      lift {
        val startedSeed = unlift(Db.start(uniqueId, metricsIp, metricsStatsdPort, instances.head.containerInstanceArn()))
        val dbAliveTask = unlift(Db.delayUntilClusterAlive(startedSeed, Set()).timeout(120.seconds))
        val dbPort = getHostPort(startedSeed, Db.cassandraPort)
        val dbIpAddress = unlift(getEc2Instance(startedSeed)).privateIpAddress()
        logger.info(s"started db seed on $dbIpAddress")

        logger.info(s"starting ${cassandraNodeCount - 1} db other nodes")
        val dbNodesRunning = instances.tail map { instance =>

          def aliveNode = {
            Db.startExtraNode(uniqueId, dbIpAddress, metricsIp, metricsStatsdPort, instance.containerInstanceArn())
          }

          aliveNode
        }
        // gather for now
        // sequence would  get them to start up after each other and increase changes of correct startup.
        val nodes = unlift(Task.gather(dbNodesRunning))
        val ips = unlift(Db.delayUntilClusterAlive(startedSeed, nodes.toSet).timeout(cassandraNodeCount * 30.seconds))
        logger.info("All extra C* nodes started: {}", ips)

        val allEcsTasks = startedSeed +: nodes

        (allEcsTasks, dbPort, dbIpAddress)
      }
    } else {
      // Dummy values, should work
      Task.now((Seq(), 0, ""))
    }
  }

  def startApplication(instances: Seq[ContainerInstance], clusterSize: Int, metricsStatsdPort: Int,
                       metricsIp: String, dbPort: Int, dbIpAddress: String, clustered: Boolean = true): Task[(Seq[EcsTask], Seq[String])] = {
    assert(instances.size == clusterSize)
    // SEED
    lift {
      val firstInstance = instances.head.containerInstanceArn()
      logger.info("starting seed on  {}", firstInstance)
      val (seedStarted, seedRunningTask) = unlift(Seed.start(uniqueId, dbPort, dbIpAddress, metricsIp, metricsStatsdPort, firstInstance, test.appConfig))
      val seedRunning = unlift(seedRunningTask)
      val seedInstance = unlift(getEc2Instance(seedRunning))
      val seedIpAddress = seedInstance.privateIpAddress()
      val seedPort = getHostPort(seedRunning, 2551)
      logger.info(s"started seed on $seedIpAddress")

      // NODES
      val nodesTaskAndIpsTask = instances.tail map { instance =>
        lift {
          val nodeRunningTask = unlift {
            if (clustered) {
              Node.start(uniqueId, dbPort, dbIpAddress, seedIpAddress, seedPort,
                metricsIp, metricsStatsdPort, instance.containerInstanceArn(), test.appConfig)
            } else {
              Seed.start(uniqueId, dbPort, dbIpAddress,
                metricsIp, metricsStatsdPort, instance.containerInstanceArn, test.appConfig)
            }
          }
          val nodeIpAddress = unlift(getEc2Instance(nodeRunningTask._1)).privateIpAddress()
          (nodeRunningTask._2, nodeIpAddress)
        }
      }

      val nodesTaskAndIps = unlift(Task.gather(nodesTaskAndIpsTask))

      // Needed for Monadless macro for logger implicit.
      import scala.reflect.ClassTag

      val nodeIps = nodesTaskAndIps.map(_._2)
      logger.info("Starting {} nodes: {}", nodeIps.size, nodeIps)

      val allPrivateIps = seedIpAddress +: nodeIps

      val runningNodes = Task.gather(nodesTaskAndIps.map(_._1))
      runningNodes.foreachL(_ => logger.info("All nodes started"))

      val allEcsTasks = runningNodes.map(nodeTasks =>
        seedRunning +: nodeTasks
      )

      unlift(allEcsTasks.map((_, allPrivateIps)))
    }
  }

  def createOutputDir(test: TestRun, resultsDir: Path): Task[Path] = Task {
    val outputDir: Path = resultsDir / test.description
    mkdir ! outputDir
    write(outputDir / "test.json", Encoder[TestRun].apply(test).spaces4)
    outputDir
  }

  def startPerformanceTest(instances: Seq[ContainerInstance], simulationClass: String, metricsGraphitePort: Int,
                           metricsIp: String, appIps: Seq[String], outputDir: Path, test: TestRun): Task[(Seq[EcsTask], Seq[String])] = {
    require(instances.size <= test.performanceNodeSize, s"There should be enough instances for ${test.performanceNodeSize}, found ${instances.size}")

    val performanceConfig: Map[String, String] = Map(
      "rebel.scenario.max-duration" -> test.duration.toString,
      "rebel.scenario.users" -> test.testUsers.toString,
      "rebel.scenario.rps-throttle-per-node" -> test.performanceThrottle.toString
      //      "rebel.scenario.repeat-number" -> "1"
    ) ++ ConfigHelper.configToMap(test.performanceConfig)

    val runs: Seq[Task[(EcsTask, String)]] = instances.map { instance =>
      lift {
        // only first instance opens accounts
        val leader = instance == instances.head
        val extraNodes = if(leader) {
          unlift(Task.gather(instances.tail.map(i => AwsLib.getEc2Instance(i.containerInstanceArn()).map(ec2i => s"${ec2i.privateIpAddress()}:8080"))))
        } else {
          Seq()
        }
        val performanceRunningTask = unlift(PerformanceTest.start(test.uniqueId, test.description, simulationClass, appIps, metricsIp, metricsGraphitePort,
          instance.containerInstanceArn(), extraNodes, leader, performanceConfig))
        val performancePublicIp: String = unlift(getEc2Instance(performanceRunningTask)).publicIpAddress()
        logger.info(s"Running performance test on: $performancePublicIp")

        val performanceFinishedTask: EcsTask =
          unlift(delayUntilInState(performanceRunningTask.taskArn(), "STOPPED"))
        logger.debug("Performance test finished: {}", performanceFinishedTask)

        (performanceFinishedTask, performancePublicIp)
      }
    }
    Task.gather(runs).map(_.unzip)
  }


  def gatherResults(publicMetricsIp: String, tasks: Set[EcsTask], testDirectoryName: String, outputDir: Path): Task[Unit] = {
    // Pull results to local machine
    val influxDb: Task[List[Path]] = fetchMetricsTask(publicMetricsIp, outputDir)

    val logs = extractAllLogs(tasks, outputDir).onErrorHandle {
      ex =>
        logger.error("Something went wrong while fetching logs; continuing", ex)
        ()
    }

    val remainingActions = Task.gather(Set(influxDb, logs))

    remainingActions.map { _ =>
      logger.info("Finished all remaining actions")
      logger.info(s"Results copied to local: $outputDir")
      ()
    }
  }

  private def extractAllLogs(tasks: Set[EcsTask], outputDir: Path): Task[Set[file.Path]] = {
    Task.defer {
      val logsDir = outputDir / "logs"
      mkdir ! logsDir

      val logsDirNio = logsDir.toNIO
      logger.info(s"Copying logs to $logsDirNio")

      val logTasks: Set[Task[Try[file.Path]]] = tasks.map {
        task =>
          Task.defer {
            lift {
              val name = task.containers().get(0).name()
              val instance: Instance = unlift(AwsLib.getEc2Instance(task))
              val prefix = s"${instance.privateIpAddress()}-${instance.publicIpAddress()}"
              val logPath: file.Path = unlift(writeAndGetLogEventsPath(name, name, task.taskArn(), logsDirNio, prefix))
              logger.debug(logPath.toString)
              Success(logPath)
            }.onErrorHandle { ex => logger.warn("Error during log fetching", ex); Failure(ex) }
          }
      }
      Task.gather(logTasks).map(_.collect { case Success(p) => p })
    }
  }

  private def fetchGatlingReport(testDirectoryName: String, outputDir: Path, performanceIp: String): Task[String] = {
    Task {
      //      ssh(performanceIp, "sudo yum -y install rsync")

      val gatlingLocation = s"/opt/gatling/results/$testDirectoryName/"
      logger.info(s"Copying gatling report ($performanceIp) to $outputDir")
      // TODO does this work?

      rsync(performanceIp, s"ec2-user@$performanceIp:$gatlingLocation", outputDir.toString()).toString()

      //      %%('rsync, "-avrze", "ssh -o StrictHostKeyChecking=no", "--progress", , outputDir).toString()
    }.onErrorHandle { e => logger.error("Fetching report failed", e); "Fetching Report failed" }
  }

  private def fetchMetricsTask(publicMetricsIp: String, resultsDir: Path): Task[List[Path]] = {
    Task {
      logger.info(s"Exporting InfluxDb series to $resultsDir")

      val testInflux = %%("influx", "--version")

      def runInfluxQuery(args: String*) = {
        %%("influx", "-host", publicMetricsIp,
          "-database", "telegraf", "-format", "csv",
          """-execute""",
          args)
      }

      val tables = runInfluxQuery("SHOW MEASUREMENTS", "-format", "column").out.lines.drop(3).takeWhile(!_.isEmpty)

      logger.info(s"Found influxdb series: $tables")

      logger.debug(s"Testing if influx executable exists: {}", testInflux)

      val metricsDir = resultsDir / "metrics"
      mkdir(metricsDir)

      tables.map(table => {
        //          logger.info(s"""Influxdb: SELECT * FROM "$table" """)
        val commandResult: CommandResult =
          runInfluxQuery(s"""SELECT * FROM "$table"""")
        val path: Path = metricsDir / s"$table.csv"
        write.append(path, commandResult.out.lines.mkString("\n"))
        logger.debug(s"Wrote metrics $table to $path")
        path
      }).toList

    }.onErrorHandle { e => logger.error("Error in fetching metrics", e); List() }
  }

  def recursiveTake[A](seq: Seq[A], takeSeq: Seq[Int]): Seq[Seq[A]] = {
    if (takeSeq.isEmpty) {
      Seq()
    } else {
      val (left, right) = seq.splitAt(takeSeq.head)
      left +: recursiveTake(right, takeSeq.tail)
    }
  }
}