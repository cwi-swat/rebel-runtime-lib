package com.ing

import java.util.UUID

import org.apache.cassandra.utils.UUIDGen

//import com.gilt.timeuuid.TimeUuid
import com.ing.rebel.experiment.aws.AwsLib
import com.ing.rebel.experiment.aws.AwsLib._
import com.ing.rebel.experiment.{Experiment, TestRun}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import monix.execution.CancelableFuture
import monix.execution.Scheduler.Implicits.global
import software.amazon.awssdk.services.ecs.model.ContainerInstance

import scala.collection.breakOut
import scala.util.matching.Regex
import io.monadless.monix.MonadlessTask._
//import io.monadless.stdlib.MonadlessFuture._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try


object Start extends LazyLogging {
  /**
    * Script assumes you have ssh-access to the container instances without password (key authentication)
    *
    * Start containers with: `aws ec2 request-spot-fleet --spot-fleet-request-config file://spotcontainerinstances.json`
    *
    * Cancel: for i in (aws ec2 describe-spot-fleet-requests --query 'SpotFleetRequestConfigs[*].[SpotFleetRequestId]' --output text); aws ec2 cancel-spot-fleet-requests --terminate-instances --spot-fleet-request-ids $i; end
    *
    * Pass parameters using key value pairs.
    * Supported
    * n=1; number of application nodes (including seed)
    * c=1; number of cassandra nodes
    * m=message; no spaces for now
    * u=1; number of users
    * p=1; number of performance nodes
    * s=com.ing.corebank.rebel.simple_transaction.simple.OpenAccountSimulation; Simulation class to use
    * onlyStart; only start the app instances, don't run the test and wait.
    */

  val Opt: Regex = """(\S+)=(\S+)""".r
  def argsToOpts(args: Seq[String]): Map[String, String] =
    args.collect { case Opt(key, value) => key -> value }(breakOut)

  def testFromArgs(args: Array[String]): TestRun = {
    val opts: Map[String, String] = argsToOpts(args)

    val nrOfNodes: Int = opts.get("n").flatMap(n => Try(n.toInt).toOption).getOrElse(3)
    val message = opts.getOrElse("m", "auto")
    val nrOfDbNodes: Int = opts.getOrElse("c", "3").toInt
    val users: Int = opts.getOrElse("u", "200").toInt
    val performanceNodeSize: Int = opts.getOrElse("p", "1").toInt
    val simulationClass: String = opts.getOrElse("s", "com.ing.corebank.rebel.simple_transaction.AllToAllSimulation")


    TestRun(simulationClass, nrOfNodes, nrOfDbNodes, users,
      uniqueId = UUIDGen.getTimeUUID.toString,
      performanceNodeSize = performanceNodeSize,
      performanceThrottle = 10000,
      duration = 2.minutes,
      performanceConfig = ConfigFactory.parseString("rebel.scenario.number-of-accounts = 100000")
//      appConfig = ConfigFactory.parseString("akka.loglevel=DEBUG")
    )
  }

  def main(args: Array[String]): Unit = {
    val program = if (args.contains("clean")) {
      stopAllTasks().runAsync
    } else {
      // TODO start or scale ecs cluster
      // TODO open ports to each other with group security group, now `default` security group which works fine
      val test = testFromArgs(args)

      lift {
        val availableInstances = unlift(AwsLib.queryAvailableInstances())
        assert(availableInstances.lengthCompare(test.instancesRequired) >= 0,
          s"Not enough available instances, only found ${availableInstances.size} instead of ${test.instancesRequired}")
        val reservedInstances: Seq[ContainerInstance] = unlift(AwsLib.reserveContainerInstances(test.instancesRequired, test.uniqueId)).right.get

        val instances: Seq[ContainerInstance] = reservedInstances.take(test.instancesRequired)

        logger.info(test.toString)

        val onlyStart: Boolean = args.contains("onlyStart")

        val experiment = new Experiment(test)
        val experimentTask = if (onlyStart) {
          logger.info("Starting app instances only + waiting")
          experiment.setupAllAppInstances(instances).delayResult(10.days) // keep running
        } else {
          experiment.experimentTask(instances, RebelExperimentPaths.resultsDir)
        }

        unlift(experimentTask.doOnFinish(_ => AwsLib.cleanupAndStop(test.uniqueId)))
      }.runAsync
    }

    Await.result(program, Duration.Inf)
  }
}