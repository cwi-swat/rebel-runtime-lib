package com.ing.research.rebel

import akka.actor.{ActorIdentity, Identify, Props}
import akka.cluster.Cluster
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.testkit.ImplicitSender
import com.ing.corebank.rebel.sharding.AccountSharding
import com.ing.corebank.rebel.simple_transaction.Account
import com.ing.corebank.rebel.simple_transaction.Account.Init
import com.ing.rebel.messages.{CurrentState, TellState}
import com.ing.rebel.{Iban, Ibans, RebelData, Uninitialised}
import com.ing.util.TestActorSystemManager
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, LoneElement, WordSpecLike}
import io.circe.generic.auto._
import com.ing.corebank.rebel.simple_transaction.Account._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * Hooks up MultiNodeSpec with ScalaTest
  */
trait STMultiNodeSpec extends MultiNodeSpecCallbacks
  with AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  override def beforeAll(): Unit = multiNodeSpecBeforeAll()

  override def afterAll(): Unit = multiNodeSpecAfterAll()
}

object MyMultiNodeConfig extends MultiNodeConfig {
  val controller: RoleName = role("controller")
  val first: RoleName = role("first")
  val second: RoleName = role("second")
  val third: RoleName = role("third")

  commonConfig(TestActorSystemManager.createLevelDbConfig(sharedLevelDb = true, "multiNodeSystem",
    ConfigFactory.parseString(
      """
        |akka.cluster.auto-down-unreachable-after = 0s
        |akka.cluster.sharding {
        |  retry-interval = 1 s
        |  handoff-timeout = 10 s
        |  shard-start-timeout = 5s
        |  entity-restart-backoff = 1s
        |  rebalance-interval = 2 s
        |}
        |// Required because SharedJournal is used, and persistence messages do go over the wire
        |akka.actor.allow-java-serialization = on
//        |akka.actor.serialization-bindings {
//        |  "akka.persistence.Protocol.Message" = kryo
//        |  "akka.persistence.journal.AsyncWriteTarget$ReplayMessages" = kryo
//        |}
      """.stripMargin)))

}

class MultiNodeSpecMultiJvmNode1 extends ShardingAndActorRef
class MultiNodeSpecMultiJvmNode2 extends ShardingAndActorRef
class MultiNodeSpecMultiJvmNode3 extends ShardingAndActorRef
class MultiNodeSpecMultiJvmNode4 extends ShardingAndActorRef

/**
  * Tests to see how ActorRef passing works with sharding
  */
class ShardingAndActorRef extends MultiNodeSpec(MyMultiNodeConfig)
  with STMultiNodeSpec with ImplicitSender with ScalaFutures with LoneElement {

  import MyMultiNodeConfig._

  import scala.concurrent.duration._

  override def initialParticipants: Int = roles.size

  val cluster: Cluster = Cluster(system)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "A Generated Rebel application" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("persistence-started")

      runOn(first, second, third) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity](10.seconds).ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("after-1")
    }

    "join cluster" in within(20.seconds) {
      //      join(controller, controller)
      join(first, first)
      join(second, first)
      join(third, first)

      runOn(first, second, third) {
        awaitAssert {
          cluster.state.members.size should be(initialParticipants - 1) // coordinator does not join
        }
      }
      enterBarrier("after-join")
    }

    "wait for all nodes to enter a barrier" in {
      enterBarrier("startup")
    }

    "start sharding" in {
      runOn(first) {
        AccountSharding(system).start()
      }
      enterBarrier("sharding-on-first")

      runOn(second) {
        AccountSharding(system).start()
      }
      enterBarrier("sharding-on-second")

      runOn(third) {
        AccountSharding(system).startProxy()
      }
      enterBarrier("sharding-proxy-third")
    }

    "receive a message from sharded actor" in {
      runOn(first, second, third) {
        AccountSharding(system).tell(Ibans.testIban1, TellState())

        expectMsg(CurrentState(Init, Uninitialised: RebelData[Account.Data]))

        log.info("SENDER: {}", lastSender)
        //        lastSender.path.root.address shouldBe node(third).root.address
      }
      enterBarrier("sharding-responding")

      runOn(first) {
        lastSender.path.root.address.hasLocalScope shouldBe true
      }
    }

    "move Account on crash" in {
      runOn(controller) {
        testConductor.exit(first, 0).await
      }
      enterBarrier("crash-first")

      runOn(second, third) {
        awaitAssert {
          cluster.state.unreachable.size shouldBe 1 // coordinator does not join
        }
      }
      enterBarrier("first-is-unreachable")

      // rebalancing takes some time
      runOn(second, third) {
        awaitAssert(max = 30.seconds, a = {
          cluster.state.unreachable shouldBe 'empty // coordinator does not join
        })
        log.info("KNOWN CLUSTER NODES: {}", cluster.state.members)
      }
      enterBarrier("recover-after-crash")
      //
      runOn(second, third) {
        AccountSharding(system).tell(Ibans.testIban1, TellState())

        expectMsg(CurrentState(Init, Uninitialised: RebelData[Account.Data]))

        log.info("SENDER after crash: {}", lastSender)

        // should recover on node second
        runOn(second) {
          lastSender.path.root.address.hasLocalScope shouldBe true
        }

        runOn(third) {
          // should now only run on second
          lastSender.path.root should ===(node(second).root)
        }
      }
      enterBarrier("sharding-still-responding")
    }

    enterBarrier("finished")
  }
}