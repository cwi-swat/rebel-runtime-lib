package com.ing

import java.util.{NoSuchElementException, UUID}

import ammonite.ops._
import org.apache.cassandra.db.marshal.TimeUUIDType
import org.apache.cassandra.utils.UUIDGen
//import com.gilt.timeuuid.TimeUuid
import com.ing.Batch.config
import com.ing.BatchConfig.Variant
import com.ing.rebel.experiment.aws.AwsLib
import com.ing.rebel.experiment.{Experiment, ExperimentResult, TestRun}
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import com.typesafe.scalalogging.{LazyLogging, Logger}
import io.circe.Encoder
import io.circe.generic.auto._
import monix.execution.Scheduler
import org.joda.time.DateTime
import pureconfig._
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import io.monadless.stdlib.MonadlessFuture._
import monix.eval.{Coeval, Task}
import monix.execution.schedulers.SchedulerService

object BatchConfig extends LazyLogging {

  case class BatchConfig(
                    description: String = "batch",
                    extraDescription: Option[String],
                    rerunCount: Int = 1,
                    default: Config,
                    n: Config,
                    variants: List[Config]
                  )

  sealed trait ClusterSize
  case class AbsoluteSize(size: Int) extends ClusterSize
  case class RelativeSize(multiplier: Int) extends ClusterSize

  // if db-cluster-size contains a letter, evaluate is as a multiplier
  implicit val dbClusterSizeReader: ConfigReader[ClusterSize] =
    ConfigReader[String].map(s => if (s.exists(_.isLetter)) RelativeSize(s.filter(_.isDigit).toInt) else AbsoluteSize(s.toInt))

  case class Variant(
                      description: String,
                      clusterSizes: List[Int],
                      dbClusterSizes: List[ClusterSize] = List(RelativeSize(1)),
                      simulationClasses: List[String],
                      userCounts: List[Int],
                      userMultipliers: List[ClusterSize] = List(AbsoluteSize(1)),
                      performanceNodeSizes: List[ClusterSize],
                      performanceThrottles: List[Long],
                      performanceConfigs: List[Config],
                      durations: List[FiniteDuration],
                      appConfigs: List[Config],
                      buildInfo: String = info.BuildInfo.toString
                    )

  def parseBatchConfig(config: Config): BatchConfig = {
    val parsedConfig = loadConfig[BatchConfig](config, "batch")
    parsedConfig match {
      case Left(failures) => logger.error(failures.toString); throw new NoSuchElementException(failures.toString)
      case Right(batch) => batch
    }
  }

  def getVariants(batchConfig: BatchConfig): List[Variant] = {
    val parsedVariants: List[Variant] = for {
      variant <- batchConfig.variants
      consolidatedVariantConfig = batchConfig.n.withFallback(variant).withFallback(batchConfig.default)
    } yield loadConfig[Variant](consolidatedVariantConfig) match {
      case Left(failures) => logger.error(failures.toString); throw new NoSuchElementException(failures.toString)
      case Right(b) => b
    }
    parsedVariants
  }

  def getTestsFromConfig(batch: BatchConfig, variants: Seq[Variant]): Seq[TestRun] = {
    for {
      expParameter <- variants
      size <- expParameter.clusterSizes
      // if not set, increase linearly with load
      dbSizeSetting <- expParameter.dbClusterSizes
      dbSize = dbSizeSetting match {
        case AbsoluteSize(s) => s
        case RelativeSize(multiplier) => size * multiplier
      }
      userMultiplierPerNode <- expParameter.userMultipliers
      userCount <- expParameter.userCounts
      effectiveUserCount = userMultiplierPerNode match {
        case AbsoluteSize(s) => userCount * s
        case RelativeSize(multiplier) => userCount * size * multiplier
      }
      simulationClass <- expParameter.simulationClasses
      performanceNodeSizeMod <- expParameter.performanceNodeSizes
      performanceNodeSize = performanceNodeSizeMod match {
        case AbsoluteSize(s) => s
        case RelativeSize(multiplier) => size * multiplier
      }
      performanceThrottle <- expParameter.performanceThrottles
      // If there is only a single performance node configured for all scenario's, make the throttle scale with the number of nodes
      effectivePerformanceThrottle = (if (expParameter.performanceNodeSizes.equals(List(AbsoluteSize(1)))) size else 1) * performanceThrottle
      performanceConfig <- expParameter.performanceConfigs
      duration <- expParameter.durations
      appConfig <- expParameter.appConfigs
      _ <- 1.to(batch.rerunCount) // create more runs if reruns are requested
    } yield TestRun(simulationClass, clusterSize = size, dbClusterSize = Math.max(3, dbSize), effectiveUserCount,
      uniqueId = UUIDGen.getTimeUUID.toString, performanceNodeSize = performanceNodeSize, performanceConfig = performanceConfig,
      performanceThrottle = effectivePerformanceThrottle,
      duration = duration, appConfig = appConfig, variantName = expParameter.description)
  }
}

object Batch extends LazyLogging {
  private val config: Config = ConfigFactory.load()

  val batchConfig: BatchConfig.BatchConfig = BatchConfig.parseBatchConfig(config)
  val variants: List[Variant] = BatchConfig.getVariants(batchConfig)

  val resultsDir: Path = RebelExperimentPaths.rootDir / "results" /
      s"${DateTime.now().toString()}-${batchConfig.description}${batchConfig.extraDescription.getOrElse("")}"

  implicit val implicitLogger: Logger = this.logger

  def main(args: Array[String]): Unit = {
    // largest first so left over instances can be filled up by smaller tests
    val tests = BatchConfig.getTestsFromConfig(batchConfig, variants).sortBy(-_.instancesRequired)
    logger.info(s"Found tests: \n${tests.mkString("\n")}")
    val totalRequiredInstances: Int = tests.map(_.instancesRequired).max
    logger.info("Total instances required {}", totalRequiredInstances)
    val runTests = AwsLib.queryAvailableInstances().map { availableInstances =>
      assert(availableInstances.size >= totalRequiredInstances,
        s"availableInstances.size ${availableInstances.size} < totalRequiredInstances $totalRequiredInstances")
      logger.info("Instances available: {}", availableInstances.map(_.containerInstanceArn()))

      mkdir ! resultsDir
      logger.info("Results in {}", resultsDir)
      write(resultsDir / "batch.json", Encoder[Seq[Variant]].apply(variants).spaces4)
      write(resultsDir / "batch.conf", config.getObject("batch").render(ConfigRenderOptions.concise().setFormatted(true)))

      variants.foreach(bp => {
        val variantDir = resultsDir / bp.description
        mkdir ! variantDir
        write(variantDir / "variant.json", Encoder[Variant].apply(bp).spaces4)
      }
      )

      Await.result(runAll(tests), Duration.Inf)

      tests.map(_.variantName).foreach { variantName =>
        Reports.generateRReport(Reports.batchReportFile, resultsDir, s"$variantName-BatchReport")
        //      generateBatchReportPerSimulation(resultsDir, variantName)
      }
      Reports.generateRReport(Reports.compareBatchesReportFile, resultsDir, "CompareVariants")
      Reports.generateRReport(Reports.compareBatchesRampReportFile, resultsDir, "CompareVariantsRamp")
      //    generateBatchComparisonReport(resultsDir)
      logger.info("Done with all batch tests")
    }
    Await.result(runTests.runAsync(ioScheduler), Duration.Inf)
  }

  sealed trait TestStatus

  case class Running(test: TestRun, running: Future[TestRun]) extends TestStatus

  case class Waiting(test: TestRun) extends TestStatus

  implicit val ioScheduler: SchedulerService = Scheduler.io(name = "alt-scheduler")

  def tryRun(test: TestRun): TestStatus = {
    import scala.reflect.ClassTag
    val status = AwsLib.reserveContainerInstances(test.instancesRequired, test.uniqueId).map(_.fold(
      availableCount => {
        logger.info(s"Required ${test.instancesRequired}, but $availableCount available")
        Waiting(test): TestStatus
      }
      , reservedInstances => {
        logger.info(s"Starting $test on ${reservedInstances.map(_.containerInstanceArn())}")
        // Group experiment results by variant for better comparison
        val experimentResultDir = resultsDir / test.variantName

        val run: Future[ExperimentResult] = new Experiment(test).experimentTask(reservedInstances, experimentResultDir)
          // continue on error
          .onErrorRecoverWith { case e =>
          //              AwsLib.cleanupAndStop(reservedInstances)
          //                .doOnCancel(cleanupAndStop(instancesToUse))
          logger.error(s"Experiment $test failed", e)
          AwsLib.cleanupAndStop(test.uniqueId).map(_ => ExperimentResult(false))
        }.runAsync(ioScheduler)

        run.failed.foreach(t => logger.error(s"Experiment $test failed", t))
        run.onComplete(t => logger.info(s"Done with $test: $t"))
        // both map and recover to makes sure other experiments try to continue
        val running: Future[TestRun] = run.map(_ => test).recover {
          case e: Throwable =>
            logger.error("Test {} failed because of reason: {}", test, e)
            test
        }
        Running(test, running)
      })).runAsync
    Await.result(status, 30.seconds)
  }

  def runAll(tests: Seq[TestRun], alreadyRunning: Seq[Running] = Seq()): Future[Unit] = {
    if (tests.isEmpty) {
      logger.info(s"Started all tests, waiting on $alreadyRunning")
      Future.sequence(alreadyRunning.map(_.running)).map(_ => ())
    } else {
      // try run everything
      //      val tryStartTasks = tests.map(tryRun)
      val tryStart: Seq[TestStatus] = tests.map(tryRun) //Await.result(Task.sequence(tryStartTasks).runAsync, 2.minutes)
      val started: Seq[Running] = tryStart.collect { case t: Running => t }
      val waiting: Seq[TestRun] = tryStart.collect { case t: Waiting => t }.map(_.test)

      val totalRunning: Seq[Running] = alreadyRunning ++ started
      logger.info(s"Waiting on completion of ${totalRunning.map(_.test)}")
      val overheadDuration = 3.minutes // spin up + spin down
      val etaSequential = tests.map(_.duration + overheadDuration).foldLeft(0.seconds)(_ + _).toCoarsest
      val maxDuration = tests.map(_.duration + overheadDuration).foldLeft(0.seconds)(_.max(_)).toCoarsest

      import monix.execution.Scheduler.Implicits.global

      val etaGuess = if (waiting.isEmpty) {
        maxDuration
      } else {
        val eventualResponse = Await.result(AwsLib.queryContainerInstances().runAsync, 5.seconds)
        ((waiting.map(_.instancesRequired).sum / eventualResponse.containerInstanceArns().size()) + 1) * maxDuration
      }
      logger.info(s"ETA (if sequential): {}", etaSequential)
      logger.info(s"ETA (optimal guess): {}", etaGuess)

      val timer: Task[Unit] = Task.now(()).delayExecution(2.minutes)

      val firstCompleted = Future.firstCompletedOf(totalRunning.map(_.running) :+ timer.runAsync)
      firstCompleted.flatMap {
        case () =>
          logger.info(s"Rechecking if CI available,\n waiting on $totalRunning,\n continuing with $waiting")
          runAll(waiting, totalRunning)
        case finishedTest =>
          val stillRunning: Seq[Running] = totalRunning.filterNot(_.test == finishedTest)
          logger.info(s"finished $finishedTest,\n waiting on $stillRunning,\n continuing with $waiting")
          runAll(waiting, stillRunning)
      }
    }
  }
}