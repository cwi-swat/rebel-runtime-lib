package com.ing.rebel.sync

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import com.ing.example
import com.ing.example.Account._
import com.ing.example.{Account, AccountLogic}
import com.ing.rebel.RebelError.PreConditionFailed
import com.ing.rebel.RebelSharding.RebelShardingExtension
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel.messages._
import com.ing.rebel.specification.SpecificationInfo
import com.ing.rebel.sync.twophasecommit.ActorRefContactPoint
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit._
import com.ing.rebel.util.CirceSupport._
import com.ing.rebel.{Iban, Ibans, Initialised, IsInitialized, RebelCheck, RebelConditionCheck, RebelDomainEvent, RebelError, SpecificationEvent}
import com.ing.util._
import com.typesafe.config.ConfigFactory
import io.circe.Decoder
import io.circe.generic.auto._
import org.joda.time.DateTime
import squants.market.{EUR, Money}

import scala.concurrent.duration._
import scala.reflect.ClassTag

class ChangingSyncOpsSpec extends IsolatedCluster(ConfigFactory.parseString(
  """
    |rebel.sync {
    |  two-pc {
    |    retry-duration = 30 s
    |    manager-timeout = 30 s
    |    participant-timeout = 30 s
    |  }
    |  max-transactions-in-progress = 8
    |  command-decider = dynamic
    |}
  """.stripMargin)) with TestBase with MessageLoggerAutoPilot {

  val depositParticipant = TestProbe("slow-participant")
  val deposit2Participant = TestProbe("slow-participant")

  sealed trait TestActorEvents extends SpecificationEvent

  val byDesignFailure: RebelError = PreConditionFailed("By design")

  // Actor using different amounts to trigger different responses for test cases
  class TestActor extends RebelFSMActor[Account.type] with AccountLogic {
    override def syncOperations(now: DateTime, transactionId: TransactionId): PartialFunction[(SpecEvent, RData), Set[SyncOperation]] = {
      //    override def syncOperations(domainEvent: RDomainEvent)(data: RData): RebelCheck[Set[SyncOperation]] = {
      //      log.info("Intercepting syncOperations for test case")
      case (specEvent, data) => specEvent match {
        case OpenAccount(accountNr, initialDeposit) => Set()
        case Deposit(amount)                        =>
          val participantRef = amount match {
            case EUR(1) => depositParticipant
            case EUR(2) => deposit2Participant
          }
          val id: Money = data.map(_.balance).getOrElse(???).getOrElse(???)
          // base participantId on runtime field balance
          Set(SyncAction(ParticipantId(transactionId, id.amount.toString()), ActorRefContactPoint(participantRef.ref), IsInitialized(transactionId)))
        case Withdraw(_)                            => Set()
        case Interest(_)                            => Set()
        case _                                      => throw new MatchError("Should not happen in this testcase")
      }
    }

    //noinspection ScalaStyle
    override def checkPreConditions(data: RData, now: DateTime): PartialFunction[AccountEvent, RebelConditionCheck] = {
      case Deposit(amount) if amount == EUR(4) => RebelConditionCheck.failure(byDesignFailure)
      case c                                   => super.checkPreConditions(data, now)(c)
    }

  }

  class TestActorSharding(system: ActorSystem)(implicit override val specInfo: SpecificationInfo[Account.type])
    extends RebelShardingExtension(system) {
    override val entryProps = Props(new TestActor)

    override type Spec = Account.type
    override val name: String = "TestActor"

    override def specClassTag: ClassTag[example.Account.AccountEvent] = implicitly

    override val eventDecoder: Decoder[example.Account.AccountEvent] = implicitly
  }

  val iban1: Iban = Ibans.testIban1
  val iban2: Iban = Ibans.testIban2
  //  val accounts = AccountSharding(system)
  val testSharding = new TestActorSharding(system)

  //  accounts.start()
  testSharding.start()

  private def setup() = {
    val openAccount = RebelDomainEvent(OpenAccount(iban1, EUR(100)))
    testSharding ! (iban1, RebelCommand(openAccount))
    expectMsg(EntityCommandSuccess(openAccount))
  }

  it should "delay nested syncs when sync operations change" in {
    setup()

    val deposit1 = RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId("deposit1"))
    // First do deposit, which "activates" sync participant based on balance
    testSharding ! (iban1, RebelCommand(deposit1))
    // non responding probe should receive message for participation, effectively blocking the 2pc
    val voteRequest = depositParticipant.expectMsgType[VoteRequest]
    // wait a bit to make sure transaction is blocked
    expectNoMessage(1.second)

    val d2Id = TransactionId("deposit2")
    // another deposit, which should be dependent on first because participantId (based on balance) changes
    val deposit2 = RebelDomainEvent(Deposit(EUR(2)), transactionId = d2Id)
    testSharding ! (iban1, RebelCommand(deposit2))
    // should be delayed because participant is changed, so no message to deposit2Participant yet
    deposit2Participant.expectNoMessage(1.second)
    expectNoMessage(1.second)

    // make deposit one succeed
    depositParticipant.reply(VoteCommit(voteRequest.transactionId, voteRequest.participantId, Set()))
    // ignore retry VoteRequests send in the mean time
    depositParticipant.fishForSpecificMessage() { case m: GlobalCommit => m }
    depositParticipant.reply(GlobalCommitAck(voteRequest.transactionId, voteRequest.participantId))
    expectMsg(EntityCommandSuccess(deposit1))

    // when 1 finished, 2 should be started
    val voteRequest2 = deposit2Participant.expectMsgType[VoteRequest]

    deposit2Participant.reply(VoteCommit(voteRequest2.transactionId, voteRequest2.participantId, Set()))
    // ignore retry VoteRequests send in the mean time
    deposit2Participant.fishForSpecificMessage() { case m: GlobalCommit => m }
    deposit2Participant.reply(GlobalCommitAck(voteRequest2.transactionId, voteRequest2.participantId))
    expectMsg(EntityCommandSuccess(deposit2))
  }
}

