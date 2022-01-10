package com.ing.corebank.rebel.simple_transaction

import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch

import akka.actor.ActorSystem
import akka.dispatch.Futures
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ContentTypes, HttpMethods, HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import com.ing.corebank.rebel.simple_transaction.SimulationBase.ScenarioConfig
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.{Encoder, Json}
import io.gatling.core.Predef._
import io.gatling.core.scenario.Simulation
import io.gatling.core.structure.{ChainBuilder, PopulationBuilder, ScenarioBuilder}
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder
import squants.market.EUR
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object SimulationBase {

  sealed trait Distribution

  case object Uniform extends Distribution

  case class Realistic(businessAccountPercentage: Double,
                       businessTransactionPercentage: Double
                      ) extends Distribution

  case class Zipf(exponent: Double) extends Distribution

  case class ScenarioConfig(maxDuration: FiniteDuration,
                            repeatNumber: Int,
                            users: Int, // ignored in rampup
                            minUsers: Int,
                            rpsThrottlePerNode: Int,
                            retryCount: Int,
                            numberOfAccounts: Int,
                            distribution: Option[Distribution],
                            warmupDuration: FiniteDuration = 30.seconds,
                            minimalStepDuration: FiniteDuration = 15.seconds,
                            leader: Boolean,
                            otherNodeUris: Seq[String],
                            numberOfSteps: Int = 10)
}

abstract class SimulationBase extends Simulation {
  private val config = ConfigFactory.load()
  val baseUrls: List[String] =
    config.
      //      withFallback(ConfigFactory.parseString("rebel.baseurl=[\"http://localhost:8080\"]")).
      getStringList("rebel.baseurls").asScala.toList
  val baseIp: String = config.
    //    withFallback(ConfigFactory.parseString("rebel.baseip=localhost")).
    getString("rebel.baseip")

  lazy val httpConf: HttpProtocolBuilder = http.baseUrls(baseUrls).shareConnections //, "http://localhost:8083")

  lazy val beforeConfig: Config = ConfigFactory.parseString("akka.remote.netty.tcp.port=0").withFallback(config)
  implicit lazy val beforeSystem: ActorSystem = ActorSystem("gatling-before-system", beforeConfig)
  implicit lazy val materializer: ActorMaterializer = ActorMaterializer()

  before {
    println(s"About to SimulationBase ${this.getClass.getName}!")
    println(s"baseUrls: $baseUrls")
    println(s"baseIp: $baseIp")

    println(s"Using config: ${scenarioConfig.toString}")

    println(s"AvailableProcessors: ${Runtime.getRuntime.availableProcessors()}")
    println(s"maxMemory: ${Runtime.getRuntime.maxMemory()}")
    println(s"totalMemory: ${Runtime.getRuntime.totalMemory()}")
    println(s"freeMemory: ${Runtime.getRuntime.freeMemory()}")
    println(s"HeapMemoryUsage: ${ManagementFactory.getMemoryMXBean.getHeapMemoryUsage}")
    println(s"NonHeapMemoryUsage: ${ManagementFactory.getMemoryMXBean.getNonHeapMemoryUsage}")

    // Can't do this because it makes docker crash
    //    val request: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = Uri(s"$baseUrl/Accounts")))
    //    val response: HttpResponse = Await.result(request, 20.seconds)
    //    println(s"Received warmup response: $response")
  }

  /**
    * Override is subclass to do actions only to be done by single/leader node, before stating performance test on all nodes
    */
  def doLeaderActions(): Unit = {}

  before {
    // If other nodes are known, create accounts and signal them, other wise start end point
    if (scenarioConfig.leader) {
      beforeSystem.log.info("Leader with other nodes {}, leader actions first", scenarioConfig.otherNodeUris)
      doLeaderActions()

      beforeSystem.log.info("Waiting for 10 seconds to let other performance nodes start")
      Thread.sleep(10000)
      // Send start signal to other nodes
      val requests: Seq[Future[HttpResponse]] = scenarioConfig.otherNodeUris.map {
        nodeIp =>
          beforeSystem.log.info("Sending start signal to {}", nodeIp)
          Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = s"http://$nodeIp")).recoverWith { case e =>
            beforeSystem.log.warning("Error while signalling other {}, retrying in 15s: {}", nodeIp, e.toString)
            Thread.sleep(15000)
            Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = s"http://$nodeIp"))
          }(beforeSystem.dispatcher)
      }
      import beforeSystem.dispatcher
      Await.result(Future.sequence(requests), 30.seconds)
    } else {
      val cdl = new CountDownLatch(1)
      beforeSystem.log.info("Starting webserver on 0.0.0.0:8080, to wait on signal from leader performance node")
      val serverFuture = Http().bindAndHandle(StartSignal.route(cdl), "0.0.0.0", StartSignal.defaultSignalPort)
      // Block until http receives post
      val server = Await.result(serverFuture, 10.seconds)
      cdl.await()
      beforeSystem.log.info("Received signal, continuing")
      server.unbind()
      // continue
    }
  }

  val chain: ChainBuilder

  def scenarioName: String

  lazy val scn: ScenarioBuilder =
    scenario(scenarioName).repeat(scenarioConfig.repeatNumber)(tryMax(scenarioConfig.retryCount)(chain))

  lazy val warmUp: ScenarioBuilder = scenario("warmup").exec(chain)


  val scenarioConfig: ScenarioConfig = {
    val config = pureconfig.loadConfig[ScenarioConfig]("rebel.scenario")
    if (config.isLeft) {
      println(s"ERROR with config: $config")
    }
    println(s"CONFIG: $config")
    config.right.get
  }

  lazy val rampUpTime: FiniteDuration = scenarioConfig.maxDuration / 12

  private val numberOfAppNodes: Int = baseUrls.size
  //  val loadPerNode: Int = scenarioConfig.users
  //  lazy val throttleLevelForLoadPerNode: Int = scenarioConfig.rpsThrottlePerNode // * numberOfAppNodes

  //  lazy val usersForLoadPerNode: Int = scenarioConfig.users

  //  val usersTimesNumberOfNodes: Int = scenarioConfig.users * numberOfAppNodes

  val totalDuration: FiniteDuration = 3 * scenarioConfig.warmupDuration + scenarioConfig.maxDuration

  val serviceLevelObjective: Seq[Assertion] = Seq(
    global.successfulRequests.percent.gte(95),
    global.responseTime.max.lte(2000),
    global.responseTime.percentile1.lte(100),
    global.responseTime.percentile2.lte(250),
    global.responseTime.percentile3.lte(1000))

  val warmupUsers: Int = 50

  def start(): SetUp = {
    import scenarioConfig._
    beforeSystem.log.info(
      s"""
         | Setting up scenario $scenarioName (${this.getClass.getSimpleName})
         | Warmup with constantUsersPerSec(10) during $warmupDuration
         | nothingFor(2 * $warmupDuration),
         | ).throttle(
         |      jumpToRps($rpsThrottlePerNode),
         |      holdFor($totalDuration)
         |    ).maxDuration($totalDuration)
      """.stripMargin)
    setUp(
      warmUp.inject(
        // at least as many requests as there are shards, to make sure they are all started
        //        rampUsers(scenarioConfig.users * (warmUpTime.toSeconds.toInt / 2)) over warmUpTime
        constantUsersPerSec(warmupUsers) during warmupDuration
      ),

      scn.inject(
        // wait for warmup
        nothingFor(warmupDuration),
        rampUsers(users) during (rampUpTime + warmupDuration)
      )
    ).throttle(
      jumpToRps(rpsThrottlePerNode),
      holdFor(totalDuration)
    ).maxDuration(totalDuration)
      .protocols(httpConf).assertions(serviceLevelObjective)
  }
}

trait SimulationBaseNoRepeat extends SimulationBase {
  override lazy val scn: ScenarioBuilder = scenario(scenarioName).exec(chain)

  lazy val numberOfSteps: Long = Math.round(scenarioConfig.maxDuration / scenarioConfig.minimalStepDuration)
  lazy val stepDuration: FiniteDuration = scenarioConfig.maxDuration / numberOfSteps
  lazy val stepUserSize = (scenarioConfig.rpsThrottlePerNode - scenarioConfig.minUsers) / numberOfSteps
  private lazy val steps = (scenarioConfig.minUsers.toLong to scenarioConfig.rpsThrottlePerNode).by(stepUserSize).zipWithIndex
  lazy val scenarios: Seq[PopulationBuilder] = steps.map {
    case (n, i) => scenario(s"$scenarioName-$n").exec(chain).inject(
      nothingFor(i * stepDuration),
      //        + scenarioConfig.warmupDuration),
      //    rampUsersPerSec(0).to(users) during rampUpTime,
      constantUsersPerSec(n) during stepDuration
    )
  }

  override def start(): SetUp = {
    import scenarioConfig._
    beforeSystem.log.info(
      s""" Setting up scenario $scenarioName
//         | $warmUp
         | $scenarios
//         | Warmup with rampUsersPerSec(1).to(50) during $warmupDuration
//         |  nothingFor(2 * $warmupDuration),
//         |  rampUsersPerSec(0).to($users) during $rampUpTime,
//         |  constantUsersPerSec($users) during $maxDuration
         | Steps (n, i) : $steps
         | nothingFor(i * $stepDuration)
         | constantUsersPerSec(n) during $stepDuration
         | ).throttle(
         |      holdFor($totalDuration)
         |    ).maxDuration($totalDuration)
      """.stripMargin)
    setUp(
      scenarios.toList
      //   scn.inject(
      //        incrementUsersPerSec(scenarioConfig.rpsThrottlePerNode / numberOfSteps)
      //          .times(numberOfSteps.toInt)
      //          .eachLevelLasting(stepDuration)
      //          .separatedByRampsLasting(stepDuration)
      //          .startingFrom(minUsers)
      //      )
    ).maxDuration(totalDuration)
      .protocols(httpConf).assertions(serviceLevelObjective)
  }
}

trait SimulationBaseRampUp extends SimulationBase {

  import scenarioConfig._
  //  lazy val duration: FiniteDuration = 2 * warmupDuration + maxDuration

  override def start(): SetUp = {

    setUp(
      scn.inject(
        atOnceUsers(users)
      )
    ).throttle(
      reachRps(rpsThrottlePerNode) in totalDuration
    ).maxDuration(totalDuration)
      .protocols(httpConf).assertions(
      serviceLevelObjective
    )
  }
}

trait SimulationBaseOpenRampUp extends SimulationBase {

  import scenarioConfig._

  override lazy val scn: ScenarioBuilder = scenario(scenarioName).exec(chain)

  override def start(): SetUp = {
    beforeSystem.log.info(
      s""" Setting up scenario $scenarioName (open with throttle)
         | setUp(
         |   scn.inject(
         |     constantUsersPerSec($users) during $totalDuration
         |   )
         | ).throttle(
         |   reachRps($rpsThrottlePerNode) in $totalDuration
         | )
      """.stripMargin)
    setUp(
      scn.inject(
        constantUsersPerSec(users) during totalDuration
      )
    ).throttle(
      reachRps(rpsThrottlePerNode) in totalDuration
    ).maxDuration(totalDuration)
      .protocols(httpConf).assertions(serviceLevelObjective)
  }
}

trait SimulationBaseOpen extends SimulationBaseOpenRampUp {

  import scenarioConfig._

  override def start(): SetUp = {
    beforeSystem.log.info(
      s""" Setting up scenario $scenarioName (open with throttle)
         | setUp(
         |   warmUp.inject(
         |     constantUsersPerSec($warmupUsers) during $warmupDuration
         |   ),
         |   scn.inject(
         |     nothingFor($warmupDuration),
         |     constantUsersPerSec($users) during $totalDuration
         |   )
         | ).throttle(
         |      jumpToRps($warmupUsers), holdFor($warmupDuration),
         |      reachRps($rpsThrottlePerNode) in $rampUpTime,
         |      holdFor($maxDuration)
         | )
      """.stripMargin)
    setUp(
      warmUp.inject(
        // at least as many requests as there are shards, to make sure they are all started
        //        rampUsers(scenarioConfig.users * (warmUpTime.toSeconds.toInt / 2)) over warmUpTime
        constantUsersPerSec(warmupUsers) during warmupDuration
      ),
      scn.inject(
        nothingFor(warmupDuration),
        constantUsersPerSec(users) during totalDuration
      )
    ).throttle(
      jumpToRps(warmupUsers), holdFor(warmupDuration),
      reachRps(rpsThrottlePerNode) in rampUpTime,
      holdFor(maxDuration)
    ).maxDuration(totalDuration)
      .protocols(httpConf).assertions(serviceLevelObjective)
  }
}

trait OpenAccountsBefore {
  this: SimulationBase =>
  //  val config: Config = ConfigFactory.load().withFallback(ConfigFactory.parseString("akka.remote.netty.tcp.port=0"))

  //  override def baseUrls: List[String]

  //  def baseIp: String

  val numberOfAccounts: Int = scenarioConfig.numberOfAccounts

  def accountName(id:Int) = s"NL$id"

  def accountIds: immutable.Seq[String] = 1 to numberOfAccounts map (i => accountName(i))

  override def doLeaderActions(): Unit = openAccounts()

  private def openAccounts(): Unit = {
    lazy val ipApiConnectionFlow: Flow[(HttpRequest, String), (HttpResponse, String), Http.HostConnectionPool] =
      Http().newHostConnectionPool[String](host = baseIp, port = 8080).filter(_._1.isSuccess).map(r => (r._1.get, r._2))

    //    def apiRequest(request: HttpRequest): Future[HttpResponse] = Source.from.single(request).via(ipApiConnectionFlow).runWith(Sink.)

    val json: Json = Encoder[Account.Event] apply Account.OpenAccount(EUR(Integer.MAX_VALUE))
    val requests: immutable.Seq[(HttpRequest, String)] = accountIds map {
      id =>
        beforeSystem.log.debug(s"Creating account $id")
        (RequestBuilding.Post(s"/Account/$id/OpenAccount")
          .withEntity(ContentTypes.`application/json`, json.noSpaces), id)
    }

    beforeSystem.log.info(s"Creating $numberOfAccounts accounts")

    val successCountFuture: Future[Int] =
      Source(requests).async.via(ipApiConnectionFlow).map {
        case (response, id) =>
          if (response.status.isFailure()) {
            beforeSystem.log.error("Account {} failed to create with response: {}", id, response.toString())
          }
          beforeSystem.log.info(s"Created account $id")
          response.discardEntityBytes()
          response
      }.filter(_.status.isSuccess()).map(_ => 1).runFold(0)(_ + _)
    val waitDuration = Math.max(numberOfAccounts / 50, 120).seconds
    beforeSystem.log.info("Waiting on account for {}", waitDuration)
    val successCount = Await.result(successCountFuture, waitDuration)

    println(s"Opened $numberOfAccounts accounts, success: $successCount")
  }
}