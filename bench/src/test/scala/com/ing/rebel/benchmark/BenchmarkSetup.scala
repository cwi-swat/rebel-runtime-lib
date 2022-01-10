package com.ing.rebel.benchmark

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.testkit.TestProbe
import com.ing.corebank.rebel.sharding.{AccountSharding, SimpleSharding, SimpleShardingWithPersistence, TransactionSharding}
import com.ing.rebel.Iban
import com.ing.rebel.benchmark.BenchmarkSetup.nrOfMessages
import com.ing.rebel.benchmark.PersistenceBenchmarksConfig.dispatcherConfig
import com.ing.rebel.boot.BootLib
import com.ing.rebel.messages.{EntityCommandSuccess, TellState}
import com.ing.util.{CassandraStarter, TestActorSystemManager}
import com.typesafe.config.{Config, ConfigFactory}
import org.openjdk.jmh.annotations._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.Try

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
abstract class BenchmarkSetup extends ScalaFutures {
  implicit var system: ActorSystem = _
  implicit var probe: TestProbe = _
  val config: Config
  val extraConfig: Config = ConfigFactory.empty()

  def createSystem(): ActorSystem = {
    val mergedConfig = extraConfig.withFallback(config)
    println(s"Using actor system config: ${mergedConfig.toString}")
    TestActorSystemManager.createSystemWithLevelDb(tempPersistence = true,
      sharedLevelDb = true, mergedConfig)
  }

  override implicit val patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = 30.seconds)

  @Setup
  def setup(): Unit = {
    BootLib.changeSecureRandomSecurityProvider()

    system = createSystem()
    AccountSharding(system).start()
    TransactionSharding(system).start()
    SimpleSharding(system).start()
    SimpleShardingWithPersistence(system).start()

    probe = TestProbe()(system)

    // Make sure sharding is started
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher
    Future.sequence(Seq(
      AccountSharding(system).ask(Iban("NL1"), TellState())(probe.ref, 30.seconds),
      TransactionSharding(system).ask(1, TellState())(probe.ref, 30.seconds),
      SimpleSharding(system).ask("1", TellState())(probe.ref, 30.seconds),
      SimpleShardingWithPersistence(system).ask("1", TellState())(probe.ref, 30.seconds))).futureValue

    system.log.info("Finished starting up system")
  }

  @TearDown
  def shutdown(): Unit = {
    system.terminate()
    Try(Await.ready(system.whenTerminated, 5.minutes))
  }

  def receiveAndAssert(): Int = {
    val receivedMessages = probe.receiveN(nrOfMessages, 20.seconds)
    val result = receivedMessages.count(_.isInstanceOf[EntityCommandSuccess[_]])
    assert(result == nrOfMessages, s"$result was not equal to $nrOfMessages: $receivedMessages")
    result
  }
}

object BenchmarkSetup {
  final val nrOfMessages = 1000
  val messages: Array[Int] = (1 to nrOfMessages).toArray
  val lastMessage: Int = messages.last
}

object PersistenceBenchmarksConfig {
  val dispatcherConfig: Config = ConfigFactory.parseString(
    """
      |akka.loglevel = WARNING
//      |akka.log-config-on-start = "on"
//      |rebel.sync.type = twophasecommit
// reset to library.conf value, because otherwise takes test arguments
      |rebel{
      |  sync {
      |    two-pc {
      |      manager-retry-duration = 900 ms
      |      manager-timeout = 1s
      |      participant-retry-duration = 9 s
      |      participant-timeout = 10 s
      |    }
      |  }
//      |  stash-capacity-mailbox.stash-capacity = 500
      |}
      |akka {
      |  extensions -= "akka.persistence.Persistence"
      |  persistence.journal.auto-start-journals = []
      |}
      |rebel.visualisation.enabled = false
//      |akka {
//      |  actor {
//      |    default-dispatcher {
//      |      # Dispatcher is the name of the event-based dispatcher
//      |      type = Dispatcher
//      |      # What kind of ExecutionService to use
//      |      executor = "fork-join-executor"
//      |      # Configuration for the fork join pool
//      |      fork-join-executor {
//      |        # Min number of threads to cap factor-based parallelism number to
//      |        parallelism-min = 16
//      |        # Parallelism (threads) ... ceil(available processors * factor)
//      |        parallelism-factor = 6
//      |        # Max number of threads to cap factor-based parallelism number to
//      |        parallelism-max = 128
//      |      }
//      |      # Throughput defines the maximum number of messages to be
//      |      # processed per actor before the thread jumps to the next actor.
//      |      # Set to 1 for as fair as possible.
//      |      throughput = 20
//      |    }
//      |  }
//      |}
    """.stripMargin)
}

trait InMemoryPersistenceConfig {
  val config: Config =
    ConfigFactory.parseString(
      """
        |akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
        |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      """.stripMargin).withFallback(dispatcherConfig)
}

trait NoOpPersistenceConfig {
  val config: Config =
    ConfigFactory.parseString(
      """
        |akka.persistence.journal.plugin = "noop-journal"
        |akka.persistence.snapshot-store.plugin = "noop-snapshot-store"
      """.stripMargin).withFallback(dispatcherConfig)
}

trait LevelDbConfig {
  val config: Config = dispatcherConfig
}

trait CassandraConfig extends BenchmarkSetup {
  val config: Config = dispatcherConfig

  override def createSystem(): ActorSystem = TestActorSystemManager.createSystemWithCassandra(extraConfig = dispatcherConfig)

  @Setup
  def startCassandra(): Unit = {
    val outerSystem = system
    println(s"STARTING C* using $system")
    new CassandraStarter() {
      override def system: ActorSystem = outerSystem
    }.startCassandra()

  }

  @TearDown
  def stopCassandra(): Unit = {
    CassandraLauncher.stop()
  }
}