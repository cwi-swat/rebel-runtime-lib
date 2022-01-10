package com.ing.rebel.experiment.aws

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.concurrent.{CompletableFuture, TimeUnit}

import com.ing.util.TaskLimiter
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.monadless.monix.MonadlessTask._
import monix.eval.{MVar, Task}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettySdkHttpClientFactory
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest
import software.amazon.awssdk.services.ec2.EC2AsyncClient
import software.amazon.awssdk.services.ec2.model.{DescribeInstancesRequest, Instance, Reservation}
import software.amazon.awssdk.services.ecs.ECSAsyncClient
import software.amazon.awssdk.services.ecs.model.{Task => EcsTask, _}

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Random

/**
  * Helper object to easily do stuff on AWS
  */
object AwsLib extends LazyLogging {

  val sharedClient: SdkAsyncHttpClient = NettySdkHttpClientFactory.builder()
    .maxConnectionsPerEndpoint(2)
    .build().createHttpClient()

  val ecs: ECSAsyncClient = ECSAsyncClient.builder().overrideConfiguration(cfg => cfg.retryPolicy(r => r.numRetries(10).backoffStrategy())).asyncHttpConfiguration(a => a.httpClient(sharedClient)).build()
  val ec2: EC2AsyncClient = EC2AsyncClient.builder().overrideConfiguration(cfg => cfg.retryPolicy(r => r.numRetries(10).backoffStrategy())).asyncHttpConfiguration(a => a.httpClient(sharedClient)).build()
  // cfg.gzipEnabled(true) looks like a bad idea => very slow... blocking
  val awsLogs: CloudWatchLogsAsyncClient = CloudWatchLogsAsyncClient.builder().overrideConfiguration(cfg => cfg.retryPolicy(r => r.numRetries(10).backoffStrategy())).asyncHttpConfiguration(a => a.httpClient(sharedClient)).build()

  val cluster = "default"
  val awsLogsGroup = "rebel-lib"
  val repository = "270458169468.dkr.ecr.eu-central-1.amazonaws.com"
  val instanceType = "m4.xlarge"
//  val addressableContainerInstanceMemory = 32176 - 100 // 32 Gb for m4.2xlarge
//  val addressableContainerInstanceMemory: Int = 7984 - 100 // 8 Gb for m4.large
  val addressableContainerInstanceMemory: Int = 15580 - 100 // 16 Gb for m5.xlarge

  implicit def completableFutureToScalaFuture[T](cf: CompletableFuture[T]): Future[T] = cf.toScala

  val maxRetries: Int = 10
  val firstDelay: FiniteDuration = 1.second
  val maxDelay: FiniteDuration = 15.seconds

  def retryBackoffAwsSdk[A](source: Task[A], maxRetries: Int, firstDelay: FiniteDuration): Task[A] = {
    source.onErrorHandleWith {
      case ex: Exception
        if {logger.warn(ex.getMessage); ex.getMessage.contains("Rate exceeded")}
        || ex.getMessage.contains("Too many concurrent attempts to create a new revision of the specified family")
        || ex.getMessage.contains("Acquire operation took longer than the configured maximum time")
        || ex.getMessage.contains("Acquire operation took longer then configured maximum time") =>
        if (maxRetries > 0) {
          val delayFactor: Double = 1 + Random.nextDouble()
          val delay = maxDelay.min(firstDelay * delayFactor).asInstanceOf[FiniteDuration].toCoarsest
          logger.warn("Retry triggered with delay {}, because of {}", delay, ex.getMessage)
          // Recursive call, it's OK as Monix is stack-safe
          retryBackoffAwsSdk(source, maxRetries - 1, delay)
            .delayExecution(firstDelay)
        } else {
          Task.raiseError(ex)
        }
      case ex: Exception =>
        logger.error("ERROR Occurred with AWS SDK retry", ex)
        logger.error(ex.getClass.toString)
        logger.error(ex.getMessage)
        logger.error(ex.getStackTrace.toSeq.toString())
        Task.raiseError(ex)
    }
  }

  def retryBackoffAwsSdk[A](source: => Future[A], maxRetries: Int = maxRetries, firstDelay: FiniteDuration = firstDelay): Task[A] = {
    retryBackoffAwsSdk(Task.deferFuture(source), maxRetries, firstDelay)
  }

  def queryContainerInstances(filter: String = ""): Task[ListContainerInstancesResponse] = {
    var request = ListContainerInstancesRequest.builder().cluster(cluster)
    if (filter.nonEmpty) {
      request = request.filter(filter)
    }
    retryBackoffAwsSdk(ecs.listContainerInstances(request.build()))
  }

  def describeContainerInstances(): Task[DescribeContainerInstancesResponse] = {
    queryContainerInstances().flatMap {
      instances =>
        val arns = instances.containerInstanceArns()
        assert(!arns.isEmpty, "Make sure there are running contrainer instances")
        retryBackoffAwsSdk(ecs.describeContainerInstances(b => b.cluster(cluster).containerInstances(arns)))
    }
  }

  def describeContainerInstances(containInstanceArns: Seq[String]): Task[DescribeContainerInstancesResponse] = {
    //    assert(containInstanceArns.nonEmpty, "Make sure there are running contrainer instances")
    retryBackoffAwsSdk(ecs.describeContainerInstances(b => b.cluster(cluster).containerInstances(containInstanceArns.asJava)))
  }

  def queryAvailableInstances(): Task[Seq[ContainerInstance]] = lift {
    val unreservedInstances: ListContainerInstancesResponse = unlift(queryContainerInstances(s"attribute:$reservedAttributeName !exists"))
    if (unreservedInstances.containerInstanceArns().isEmpty) {
      Seq()
    } else {
      unlift(describeContainerInstances(unreservedInstances.containerInstanceArns().asScala)).containerInstances().asScala
    }
  }

  // debugging
  //  import monix.execution.Scheduler.Implicits.global
  //  Observable.interval(1.second).flatMap(l => Observable.fromTask(queryAvailableInstances())).foreach(cis => logger.warn("queryAvailableInstances {}", cis.map(_.containerInstanceArn())))

  private val reservedAttributeName = "reserved"

  final class MLock {
    private[this] val mvar = MVar(())

    def acquire: Task[Unit] =
      mvar.take

    def release: Task[Unit] =
      mvar.put(())

    def greenLight[A](fa: Task[A]): Task[A] =
      for {
        _ <- acquire
        _ = logger.debug("ACQUIRED")
        a <- fa.doOnCancel(release)
        _ <- release
        _ = logger.debug("RELEASED")
      } yield a
  }

  val reserveInstancesLock = new MLock

  def reserveContainerInstances(count: Int, uniqueId: String): Task[Either[Int, Seq[ContainerInstance]]] =
  // To make sure only one Task at the same time can do reservations
    reserveInstancesLock.greenLight {
      lift {
        logger.debug("ENTRY reserveContainerInstances for {}", uniqueId)
        logger.debug("Try reserving instances for {}", uniqueId)
        val availableInstances: Seq[ContainerInstance] = unlift(queryAvailableInstances())
        logger.debug("Reserving instances for {}", uniqueId)
        //      val reserving = availableInstances.take.flatMap { availableInstances =>
        if (availableInstances.lengthCompare(count) >= 0) {
          val use: Seq[ContainerInstance] = availableInstances.take(count)
          //  assert(availableInstances.size == count, s"Not enough available instances, only found ${availableInstances.size} instead of $count")
          // One by one, otherwise: InvalidParameterException: attributes can have at most 10 items.
          val seq: Seq[Task[PutAttributesResponse]] = use.map { i =>
            retryBackoffAwsSdk(ecs.putAttributes(PutAttributesRequest.builder().cluster(cluster).attributes(
              Attribute.builder().targetId(i.containerInstanceArn()).name(reservedAttributeName).value(uniqueId).build()
            ).build()))
          }
          unlift(Task.gather(seq))
          logger.info("Reserved for {}\n{}", uniqueId, use.map(_.containerInstanceArn()))

          // assert
          val unreservedInstances: Seq[ContainerInstance] = unlift(queryAvailableInstances())
          val overlap = unreservedInstances.map(_.containerInstanceArn()).intersect(use.map(_.containerInstanceArn()))
          assert(overlap.isEmpty, s"Error. reserved instances still available, overlap: $overlap")
          val allInstances = unlift(describeContainerInstances())
          val usedInstances = allInstances.containerInstances().asScala.filter(ci => use.map(_.containerInstanceArn()).contains(ci.containerInstanceArn()))
          usedInstances.foreach(i => assert(i.attributes().asScala.exists(a => a.name() == reservedAttributeName && a.value() == uniqueId)))

          logger.debug("EXIT reserveContainerInstances for {}", uniqueId)
          Right(use)
        } else {
          logger.info("Reserved none, because only {} available", availableInstances.size)
          logger.debug("EXIT reserveContainerInstances for {}", uniqueId)
          Left(availableInstances.size)
        }
      }
    }

  def stopTasksOnContainerInstance(instance: ContainerInstance): Task[Iterable[StopTaskResponse]] = retryBackoffAwsSdk {
    logger.debug("Listing tasks on {}", instance)
    ecs.listTasks(b => b.cluster(cluster).containerInstance(instance.containerInstanceArn()))
  }.flatMap {
    lt => stopTasks(lt.taskArns().asScala)
  }

  def stopTasksByExperimentId(experimentId: String): Task[Iterable[StopTaskResponse]] = lift {
    val lt: ListTasksResponse = unlift(retryBackoffAwsSdk(ecs.listTasks(b => b.cluster(cluster).startedBy(experimentId))))
    unlift(stopTasks(lt.taskArns().asScala))
  }

  def stopTasks(taskArns: Iterable[String]): Task[Iterable[StopTaskResponse]] = {
    logger.debug("stopTasks called")
    Task.gather(taskArns.map { task =>
      retryBackoffAwsSdk {
        logger.info(s"Stopping: $task")
        ecs.stopTask(StopTaskRequest.builder().cluster(cluster).task(task).build())
      }
    })
  }

  def deleteAttributes(instanceArns: Seq[String]): Task[Seq[DeleteAttributesResponse]] = {
    Task.gather(instanceArns.map { i =>
      logger.info(s"Clearing $reservedAttributeName from ${i}")
      retryBackoffAwsSdk {
        ecs.deleteAttributes(DeleteAttributesRequest.builder().cluster(cluster).attributes(Attribute.builder().name(reservedAttributeName).targetId(i).build()).build())
      }
    })
  }

  def dereserveContainerInstances(reservationId: String): Task[Seq[DeleteAttributesResponse]] = {
    logger.debug("dereserveContainerInstances called")
    // make sure nothing happens between querying instances and removal
    reserveInstancesLock.greenLight {
      lift {
        val relevantInstances: Seq[String] = unlift(queryContainerInstances(s"attribute:$reservedAttributeName == $reservationId")).containerInstanceArns().asScala
        //        val relevantInstances: Seq[ContainerInstance] = unlift(describeContainerInstances()).containerInstances().asScala.filter(_.attributes().asScala.exists(a => a.name() == reservedAttributeName && a.value() == reservationId))
        unlift(deleteAttributes(relevantInstances))
      }
    }
  }

  //  def removeAllReservedAttributes(): Unit = {
  //    val attributes = queryContainerInstances().containerInstanceArns.asScala.map(cia => Attribute.builder().name(reservedAttributeName).targetId(cia))
  //
  //    val grouped: Iterator[mutable.Buffer[Attribute]] = attributes.grouped(10)
  //    if (attributes.nonEmpty) {
  //      grouped.foreach { attributes =>
  //        logger.info(s"Removing $attributes")
  //        blocking(ecs.deleteAttributes(DeleteAttributesRequest.builder().cluster(cluster).attributes(attributes.asJava)))
  //      }
  //    }
  //  }

  def stopAllTasks(): Task[Unit] =
    lift {
      val tasks = unlift(retryBackoffAwsSdk(ecs.listTasks(ListTasksRequest.builder().cluster(cluster).build())))
      val instances = unlift(describeContainerInstances())
      unlift(stopTasks(tasks.taskArns().asScala))
      unlift(deleteAttributes(instances.containerInstances().asScala.map(_.containerInstanceArn())))
    }

  def cleanupAndStop(reservationId: String): Task[Unit] = {
    logger.info("cleanupAndStop called for {}", reservationId)
    //    val stop: Seq[Task[Iterable[StopTaskResponse]]] = instances.map(stopTasksOnContainerInstance)
    val stop: Task[Iterable[StopTaskResponse]] = stopTasksByExperimentId(reservationId)
    val dereserve: Task[Seq[DeleteAttributesResponse]] = dereserveContainerInstances(reservationId)
    // First stop, then dereserve
    lift {
      unlift(stop)
      unlift(dereserve)
      ()
    }
  }

  def awsLogConfiguration(logStreamPrefix: String): LogConfiguration = {
    LogConfiguration.builder()
      .logDriver("awslogs")
      .options(
        Map("awslogs-group" -> awsLogsGroup,
          "awslogs-region" -> "eu-central-1",
          "awslogs-stream-prefix" -> logStreamPrefix
        ).asJava)
      .build()
  }

  def defaultContainerDefinition(logStreamPrefix: String): ContainerDefinition =
    ContainerDefinition.builder()
      .logConfiguration(awsLogConfiguration(logStreamPrefix))
      .ulimits(Ulimit.builder().name(UlimitName.NOFILE).softLimit(262144).hardLimit(262144).build())
      .mountPoints(MountPoint.builder().containerPath("/dev/random").sourceVolume("devurandom").build()).build()

  def delayUntilInState(taskArn: String, waitOnStatus: String = "RUNNING", delay: FiniteDuration = 500.milliseconds): Task[EcsTask] = {
    // TODO make sure this actually retries, only retries on Exception
    // TODO fix this, make everything nonblocking...
    //    circuitBreaker.protect(Task(blockingDo).delayExecution(500.millis)).restartUntil(_.getLastStatus == waitOnStatus)
    val check: Task[EcsTask] = retryBackoffAwsSdk(
      ecs.describeTasks(DescribeTasksRequest.builder().cluster(cluster).tasks(taskArn).build()))
      .map(_.tasks().get(0))

    def predicate: EcsTask => Boolean = _.lastStatus() == waitOnStatus

    restartUntilRandomentialBackoff(check, predicate, delay, taskArn + waitOnStatus).onErrorRestart(25)
  }

  def restartUntilRandomentialBackoff[A](task: Task[A], predicate: A => Boolean, delay: FiniteDuration, info: String = ""): Task[A] = {
    val delayFactor: Double = 1d + Random.nextDouble()
    val newDelay = maxDelay.min(delay * delayFactor).asInstanceOf[FiniteDuration].toCoarsest
    logger.debug("Retry State check, delay ({}) {}", info, newDelay)
    if (newDelay == maxDelay) {
      logger.info("Retry State check, maxdelay ({}) {}", info, newDelay)
    }
    task.delayExecution(delay).flatMap(a => if (predicate(a)) Task.now(a) else restartUntilRandomentialBackoff(task, predicate, newDelay, info))
  }

  def getHostPort(task: EcsTask, containerPort: Int): Int = {
    val container: Container = task.containers().asScala.find(_.taskArn() == task.taskArn()).get
    try {
      container.networkBindings().asScala.find(_.containerPort() == containerPort).get.hostPort()
    } catch {
      case e: Throwable =>
        logger.debug("HOST connection used: Could not find mapped container port, falling back to hostport {} for {}", containerPort, container.toString)
        //        fall back to host port (because of networking mode HOST)
        //        throw e
        containerPort
    }
  }

  def getEc2Instance(runningTask: EcsTask): Task[Instance] = getEc2Instance(runningTask.containerInstanceArn())

  def getEc2Instance(containerInstanceArn: String): Task[Instance] = for {
    containerInstances <- retryBackoffAwsSdk(ecs.describeContainerInstances(DescribeContainerInstancesRequest.builder().cluster(cluster)
      .containerInstances(containerInstanceArn).build()))
    _ = assert(containerInstances.containerInstances().size() == 1, s"When selecting with $containerInstanceArn, expected only one result")
    instance: ContainerInstance = containerInstances.containerInstances().get(0)
    ec2Instances <- retryBackoffAwsSdk(ec2.describeInstances(DescribeInstancesRequest.builder().instanceIds(instance.ec2InstanceId()).build()))
    reservation: Reservation = ec2Instances.reservations().get(0)
  } yield reservation.instances().get(0)

  def startTaskAsync(uniqueId: String, family: String, containerDefinition: ContainerDefinition,
                     containerInstanceArns: Seq[String], volumes: Seq[Volume] = Seq()): Task[(EcsTask, Task[EcsTask])] = {
    // add urandom to make sure things like uuid creation do not block
    val vols = volumes :+ Volume.builder().name("devurandom").host(HostVolumeProperties.builder().sourcePath("/dev/urandom").build()).build()
    lift {
      val taskDefinitionResult = unlift(retryBackoffAwsSdk(ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
        // start  network mode HOST to make sure the sysctl -w net.core.somaxconn=1024 gets inherited from host
        .networkMode(NetworkMode.HOST)
        .family(family)
        .containerDefinitions(containerDefinition)
        .volumes(vols: _*).build())))

      logger.debug("starting: {}", taskDefinitionResult)


      // assert if really available for this experiment
      {
        val instances = unlift(describeContainerInstances())
        val relevantInstances: Seq[ContainerInstance] = instances.containerInstances().asScala.filter(ci => containerInstanceArns.contains(ci.containerInstanceArn()))
        relevantInstances.foreach {
          ci =>
            val maybeAttribute: Attribute = ci.attributes().asScala.find(a => a.name() == reservedAttributeName).get
            assert(maybeAttribute.value() == uniqueId, s"CI $ci registered to other experiment $maybeAttribute for $uniqueId, $family")
            // skip check for performance nodes, since they are running on metric node.
            if (family != "performance") {
              if (ci.runningTasksCount() == 0) {
                lift {
                  val check: ListTasksResponse = unlift(retryBackoffAwsSdk(ecs.listTasks(c => c.cluster(cluster).containerInstance(ci.containerInstanceArn()))))
                  logger.error(s"CI $ci already running containers for {} {} {}", uniqueId, family, check.taskArns())

                  val tasks = unlift(retryBackoffAwsSdk(ecs.describeTasks(c => c.cluster(cluster).tasks(check.taskArns()))))
                  logger.error("TASKS: {}", tasks)
                }
              }
            }
        }
      }

      val startTaskResult = unlift(retryBackoffAwsSdk(ecs.startTask(StartTaskRequest.builder()
        .cluster(cluster)
        .taskDefinition(taskDefinitionResult.taskDefinition().taskDefinitionArn())
        .containerInstances(containerInstanceArns: _*)
        .startedBy(uniqueId).build()))
        // allow retry on failure
        .map(result =>
        if (!result.failures().isEmpty) {
          logger.error("Error starting, retrying once {}, {}, {}", uniqueId, family, result.failures())
          throw new RuntimeException(s"Error starting task, retrying once $uniqueId, $family, ${result.failures()}")
        } else {
          result
        }
      ).onErrorRestart(1)
      )

      logger.debug(s"starting: $startTaskResult")

      assert(startTaskResult.tasks().size() == 1, s"Expected started for task (${taskDefinitionResult.taskDefinition().taskDefinitionArn()}) tasks `${startTaskResult.tasks()}` to have size 1: $startTaskResult")

      val startedTask = startTaskResult.tasks().get(0)
      (startedTask, delayUntilInState(startedTask.taskArn()))
    }
  }

  val logRateLimiter: TaskLimiter = TaskLimiter(TimeUnit.SECONDS, limit = 20)(logger)

  def writeAndGetLogEventsPath(prefixName: String, containerName: String, ecsTaskId: String, outputDirectory: Path, filePrefix: String): Task[Path] = {
    val id: String = ecsTaskId.split("/").apply(1) // second part should be task id

    val logStreamName = s"$prefixName/$containerName/$id"
    logger.info(s"Getting log streams for: $logStreamName")

    val path = Paths.get(s"$outputDirectory/$filePrefix-${logStreamName.replace('/', '-')}.log")
    Files.createDirectories(path.getParent)

    def writeAllEvents(token: Option[String], path: Path): Task[Unit] = {
      logger.debug("Requesting AwsLogs for {} with token {}", logStreamName, token)
      val eventsTask = logRateLimiter.request(retryBackoffAwsSdk(awsLogs.getLogEvents(GetLogEventsRequest.builder().logGroupName(awsLogsGroup)
        .logStreamName(logStreamName).startFromHead(true).nextToken(token.orNull).build())))
      eventsTask.flatMap { eventsResponse =>

        logger.debug("Got AwsLogs response for {}, forward {}, backward {}",
          logStreamName, eventsResponse.nextForwardToken(), eventsResponse.nextBackwardToken())
        // write directly to file to make sure memory does not fill up

          Files.write(path, eventsResponse.events().asScala.map(e => s"${e.timestamp()}, ${e.ingestionTime()}, ${e.message()}").asJava,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND)

//        logger.info(eventsResponse.nextForwardToken)
        val lastToken = token.map(_.substring(2))
        logger.debug("Comparing {} / {}", eventsResponse.nextBackwardToken().substring(2), lastToken)
        if (!lastToken.contains(eventsResponse.nextBackwardToken().substring(2))) {
          writeAllEvents(Some(eventsResponse.nextForwardToken()), path)
        } else {
          Task.now(())
        }
      }
    }

    //noinspection ScalaStyle
    writeAllEvents(None, path).map(_ => path).timeout(5.minutes)
  }

  object ConfigHelper {
    def configToMap(config: Config): Map[String, String] = {
      config.entrySet().asScala.map(e => e.getKey -> e.getValue.unwrapped().toString).toMap
    }

    def configToJavaOpts(extraConfig: Map[String, String]): String = {
      // poor mans sanitation of spaces
      val sanitisedConfig: Map[String, String] = extraConfig.map {
        case (k, v) => (k.replace(" ", ""), v.replace(" ", ""))
      }
      sanitisedConfig.map { case (key, value) => s"-D$key=$value" }.mkString(" ")
    }
  }

}
