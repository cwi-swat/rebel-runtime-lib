package com.ing.rebel.sync

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import akka.testkit.TestProbe
import com.ing.example.{Account, AccountLogic}
import com.ing.example.Account.{Deposit, OpenAccount}
import com.ing.example.sharding.AccountSharding
import com.ing.rebel.{Ibans, RebelDomainEvent}
import com.ing.rebel.messages._
import com.ing.rebel._
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.util.{IsolatedCluster, MessageLoggerAutoPilot, TestBase}
import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import squants.market.EUR

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

/**
  * Many sequential PSAC requests would lead to time outs. The EntityHost would still be busy with the incoming actions,
  * and running the PSAC allow command decider. In the mean time the first started actions would time out.
  */
class PsacBenchBugReproduceSpec extends IsolatedCluster(ConfigFactory.parseString(
  """
    |rebel.sync {
    |  two-pc {
    |      manager-retry-duration = 900 ms
    |      manager-timeout = 1s
    |      participant-retry-duration = 9 s
    |      participant-timeout = 10 s
    |  }
    |  max-transactions-in-progress = 100
    |  command-decider = dynamic
    |}
  """.stripMargin)) with TestBase with MessageLoggerAutoPilot {

  implicit val probe: TestProbe = TestProbe()(system)
  final val operationsPerInvocation = 20
  SlowAccountSharding(system).start()

  "Multiple deposits" should "finish without error" in {
    startAccount()

    val deposits = deposit()

    deposits foreach (_ shouldBe an[EntityCommandSuccess[_]])
  }

  /**
    * Account that is delayed to simulate heavy calculations for new state calculations. Should be expensive for PSAC.
    */
  trait SlowAccountLogic extends AccountLogic {
    //noinspection ScalaStyle
    override def applyPostConditions(data: RData, domainEvent: RDomainEvent): RData = {
//      Thread.sleep(1000)
      super.applyPostConditions(data, domainEvent)
    }
  }

  class SlowAccountSharding(system: ExtendedActorSystem) extends AccountSharding(system) {
    override val entryProps = Props(new RebelFSMActor[Account.type] with SlowAccountLogic)
  }
  object SlowAccountSharding extends ExtensionId[SlowAccountSharding] with ExtensionIdProvider {
    override def createExtension(system: ExtendedActorSystem): SlowAccountSharding = new SlowAccountSharding(system)

    override def lookup(): ExtensionId[_ <: Extension] = SlowAccountSharding
  }

  def startAccount(): Unit = {
    val openAccount = OpenAccount(Ibans.testIban1, EUR(Integer.MAX_VALUE))
    SlowAccountSharding(system).tell(Ibans.testIban1, RebelCommand(RebelDomainEvent(openAccount)))(probe.ref)
    //      SlowAccountSharding(system).tell(iban2, RebelCommand(RebelDomainEvent(openAccount)))(probe.ref)

    // Apparently also failure can occur, maybe due to threads running this at the same time
    val success = probe.expectMsgType[EntityCommandSuccess[_]]

    SlowAccountSharding(system).tell(Ibans.testIban1, TellState(success.event.transactionId))(probe.ref)
    probe.expectMsg(CurrentState(Account.Opened, Initialised(Account.AccountData(Some(EUR(Integer.MAX_VALUE))))))

    system.log.info("Finished starting 1 Account")
  }

//  var count = 0

  def deposit(): Seq[Any] = {
//    count += 1 // not used here,  but only in micro bench
    val result: Seq[Future[Any]] = (1 to operationsPerInvocation).map { i =>
      system.log.info(s"Asking transaction $i")
      tryAsk(RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId(s"t-${i.toString}")), s"$i")
    }
    Await.result(Future.sequence(result), 60.seconds)
  }

  implicit lazy val dispatcher: ExecutionContextExecutor = system.dispatcher

  // keep on repeating until one success is found
  def tryAsk(event: Account.RDomainEvent, infoString: String, retryCount: Int = 0): Future[EntityCommandSuccess[_]] = {
    // use new transaction id to make sure that participant starts fresh participant actors
    val newId = TransactionId(s"t-$infoString-$retryCount")
    if(system.log.isWarningEnabled && retryCount > 0) {
      system.log.warning(s"(Re)Asking transaction {}, count {}", infoString, retryCount)
    }
    SlowAccountSharding(system)
      .ask(Ibans.testIban1, RebelCommand(event.copy(transactionId = newId)))(probe.ref, 60.seconds)
      //      .toMap[EntityCommandSuccess[_]]
      .flatMap {
      case EntityTooBusy                =>
        // When does this occur?
        system.log.error("EntityTooBusy, trying again")
        tryAsk(event, infoString, retryCount + 1)
      case fail: EntityCommandFailed[_] =>
        // happens when TransactionManager times out
        system.log.error("EntityCommandFailed, trying again {}", fail)
        tryAsk(event, infoString, retryCount + 1)
      //        throw new RuntimeException(s"EntityCommandFailed should not happen $e")
      case done: EntityCommandSuccess[_] => Future.successful(done)
    }(dispatcher)
  }
}
