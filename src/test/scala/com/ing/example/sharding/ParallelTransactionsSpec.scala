package com.ing.example.sharding

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import com.ing.example
import com.ing.example.{Account, AccountLogic, ClusterSpec}
import com.ing.example.Account._
import com.ing.rebel.RebelError.PreConditionFailed
import com.ing.rebel.RebelSharding.RebelShardingExtension
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel.messages._
import com.ing.rebel.specification.SpecificationInfo
import com.ing.rebel.sync.twophasecommit.ActorRefContactPoint
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit._
import com.ing.rebel.util.MessageLogger
import com.ing.rebel.{Iban, Ibans, Initialised, IsInitialized, RebelCheck, RebelConditionCheck, RebelDomainEvent, RebelError, SpecificationEvent}
import com.ing.util._
import com.typesafe.config.ConfigFactory
import io.circe.generic.auto._
import org.joda.time.DateTime
import squants.market.EUR
import io.circe.generic.auto._
import com.ing.rebel.util.CirceSupport._
import io.circe.Decoder

import scala.concurrent.duration._
import scala.reflect.ClassTag

class ParallelTransactionsSpec extends IsolatedCluster(ConfigFactory.parseString(
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

  val slowParticipant = TestProbe("slow-participant")

  sealed trait TestActorEvents extends SpecificationEvent

  val byDesignFailure: RebelError = PreConditionFailed("By design")

  // Actor using different amounts to trigger different responses for test cases
  class TestActor extends RebelFSMActor[Account.type] with AccountLogic {
    override def syncOperations(now: DateTime, transactionId: TransactionId): PartialFunction[(SpecEvent, RData), Set[SyncOperation]] = {
//    override def syncOperations(domainEvent: RDomainEvent)(data: RData): RebelCheck[Set[SyncOperation]] = {
//      log.info("Intercepting syncOperations for test case")
      case (specEvent, data) => specEvent match {
        case OpenAccount(accountNr, initialDeposit) => Set()
        case Deposit(amount)                        => if (amount == EUR(1)) {
          // on first deposit (identifiable by amount), block using slow participant
          Set(SyncAction(ParticipantId(transactionId, "slowParticipant"), ActorRefContactPoint(slowParticipant.ref), IsInitialized(transactionId)))
        } else {
          Set()
        }
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
  val accounts = AccountSharding(system)
  val testSharding = new TestActorSharding(system)

  accounts.start()
  testSharding.start()


  "Transactions" should "run in parallel when preconditions don't clash" in {
    system.actorOf(MessageLogger.props("target/parallel.html"))
    addTimer()

    setup()

    val deposit1 = RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId("deposit1"))
    testSharding ! (iban1, RebelCommand(deposit1))
    // non responding probe should receive message for participation, effectively blocking the 2pc
    slowParticipant.expectMsgType[VoteRequest]
    // wait a bit to make sure transaction is blocked
    expectNoMessage()

    val deposit2 = RebelDomainEvent(Deposit(EUR(2)), transactionId = TransactionId("deposit2"))
    testSharding ! (iban1, RebelCommand(deposit2))
    expectMsg(EntityCommandSuccess(deposit2))

    val deposit3 = RebelDomainEvent(Deposit(EUR(3)), transactionId = TransactionId("deposit3"))
    testSharding ! (iban1, RebelCommand(deposit3))
    expectMsg(EntityCommandSuccess(deposit3))
  }

  private def setup() = {
    val openAccount = RebelDomainEvent(OpenAccount(iban1, EUR(100)))
    testSharding ! (iban1, RebelCommand(openAccount))
    expectMsg(EntityCommandSuccess(openAccount))
  }

  it should "fail fast when parallel transaction comes in and preconditions fail everywhere" in {
    system.actorOf(MessageLogger.props("target/parallel-fail.html"))
    addTimer()

    setup()

    val deposit1 = RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId("deposit1"))
    testSharding ! (iban1, RebelCommand(deposit1))
    // non responding probe should receive message for participation, effectively blocking the 2pc
    slowParticipant.expectMsgType[VoteRequest]
    // wait a bit to make sure transaction is blocked
    expectNoMessage()

    // Deposit(4) fails by design
    val deposit4 = RebelDomainEvent(Deposit(EUR(4)), transactionId = TransactionId("deposit4"))
    testSharding ! (iban1, RebelCommand(deposit4))
    // fail fast
    expectMsgType[EntityCommandFailed[Account.type]]
      .event.specEvent shouldBe a[Deposit]
    //    failed.rebelErrors.toList should contain (WrappedError(byDesignFailure))
  }

  it should "delay when parallel transaction comes in and some preconditions fail" in {
    system.actorOf(MessageLogger.props("target/parallel-delay.html"))
    addTimer()

    setup()

    val deposit1 = RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId("deposit1"))
    // EUR(1) triggers slowParticipant as sync participant
    testSharding ! (iban1, RebelCommand(deposit1))
    // non responding probe should receive message for participation, effectively blocking the 2pc
    val voteRequest = slowParticipant.expectMsgType[VoteRequest]
    // wait a bit to make sure transaction is blocked
    expectNoMessage(1.second)

    val withdrawal = RebelDomainEvent(Withdraw(EUR(101)), transactionId = TransactionId("withdrawal"))
    testSharding ! (iban1, RebelCommand(withdrawal))
    // should be delayed thus no direct response
    expectNoMessage(1.second)

    // make deposit one succeed
    slowParticipant.reply(VoteCommit(voteRequest.transactionId, voteRequest.participantId, Set()))
    // ignore retry VoteRequests send in the mean time
    slowParticipant.fishForSpecificMessage(){case m: GlobalCommit => m}
    slowParticipant.reply(GlobalCommitAck(voteRequest.transactionId, voteRequest.participantId))
    expectMsg(EntityCommandSuccess(deposit1))

    expectMsg(EntityCommandSuccess(withdrawal))
  }

  it should "process parallel events in the correct order" in {
    system.actorOf(MessageLogger.props("target/parallel-order.html"))
    addTimer()
    // open with 100 euro
    setup()

    val deposit1 = RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId("deposit1"))
    testSharding ! (iban1, RebelCommand(deposit1))
    // non responding probe should receive message for participation, effectively blocking the 2pc
    val voteRequest = slowParticipant.expectMsgType[VoteRequest]
    // wait a bit to make sure transaction is blocked
    expectNoMessage()

    val interest = RebelDomainEvent(Interest(0.03), transactionId = TransactionId("interest"))
    testSharding ! (iban1, RebelCommand(interest))
    expectMsg(EntityCommandSuccess(interest))

    testSharding ! (iban1, TellState(interest.transactionId))
    // should be delayed since update can only happen after deposit
    expectNoMessage()

    // make deposit one succeed
    slowParticipant.reply(VoteCommit(voteRequest.transactionId, voteRequest.participantId, Set()))
    // ignore retry VoteRequests send in the mean time
    slowParticipant.fishForSpecificMessage(){case m: GlobalCommit => m}
    slowParticipant.reply(GlobalCommitAck(voteRequest.transactionId, voteRequest.participantId))
    expectMsg(EntityCommandSuccess(deposit1))

    expectMsg(CurrentState(Opened, Initialised(AccountData(Some(EUR(104.03))))))
  }

  it should "process multiple parallel events in the correct order" in {
    // open with 100 euro
    setup()

    val deposit1 = RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId("deposit1"))
    testSharding ! (iban1, RebelCommand(deposit1))
    // non responding probe should receive message for participation, effectively blocking the 2pc
    val voteRequest = slowParticipant.expectMsgType[VoteRequest]
    // wait a bit to make sure transaction is blocked
    expectNoMessage()

    val numberOfDeposits = 7 // should be smaller than max number of parallel events
    (1 to numberOfDeposits) foreach { i =>
      val deposit = RebelDomainEvent(Deposit(EUR(2)), transactionId = TransactionId(s"deposit-$i"))
      testSharding ! (iban1, RebelCommand(deposit))
      expectMsg(EntityCommandSuccess(deposit))

//      testSharding ! (iban1, TellState(interest.transactionId))
      // should be delayed since update can only happen after deposit
//      expectNoMessage()
    }
    // make deposit one succeed
    slowParticipant.reply(VoteCommit(voteRequest.transactionId, voteRequest.participantId, Set()))
    // ignore retry VoteRequests send in the mean time
    slowParticipant.fishForSpecificMessage(){case m: GlobalCommit => m}
    slowParticipant.reply(GlobalCommitAck(voteRequest.transactionId, voteRequest.participantId))
    expectMsg(EntityCommandSuccess(deposit1))

    testSharding ! (iban1, TellState(TransactionId(s"deposit-$numberOfDeposits")))
    expectMsg(CurrentState(Opened, Initialised(AccountData(Some(EUR(101+2*numberOfDeposits))))))
  }
}