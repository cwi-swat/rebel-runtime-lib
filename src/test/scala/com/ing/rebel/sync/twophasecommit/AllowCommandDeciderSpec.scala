package com.ing.rebel.sync.twophasecommit

import cats.data.NonEmptyChain
import cats.scalatest.ValidatedMatchers
import com.ing.example.Account
import com.ing.example.Account._
import com.ing.rebel.Ibans._
import com.ing.rebel.RebelError.GenericRebelError
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider.Accept._
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider.Reject._
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider.Delay._
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider._
import com.ing.rebel.sync.pathsensitive.{AllowCommandDecider, DynamicPsacCommandDecider, StaticCommandDecider}
import com.ing.rebel.{Ibans, Initialised, RebelConditionCheck, RebelData, RebelDomainEvent, RebelErrors}
import com.ing.util.TestBase
import org.scalacheck.Arbitrary
import squants.market.EUR

//noinspection ScalaStyle
class AllowCommandDeciderSpec extends TestBase with ValidatedMatchers {

  val psacDecider: AllowCommandDecider[Account.type] = new DynamicPsacCommandDecider(5, Account.Logic)

  // Hacky way to make sure the "old" checking of the whole tree is not hit, although a bit whitebox
  val onlyStaticDecider: StaticCommandDecider[Account.type] = new StaticCommandDecider(5, Account.Logic)(
    (currentState: Account.Logic.RState, currentData: Account.Logic.RData, relevantTransactions: Seq[Account.Logic.RDomainEvent], incomingEvent: Account.Logic.RDomainEvent) => fail("Should not use the PSAC possible outcomes tree and precondition checks"))

  "allowCommand" should "Accept when all possible states allow (success preconditions), using static information only" in {
    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(), RebelDomainEvent(Deposit(EUR(10)))) shouldBe DynamicAccept

    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(RebelDomainEvent(Deposit(EUR(20)))), RebelDomainEvent(Deposit(EUR(10)))) shouldBe StaticAccept

    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(
      RebelDomainEvent(Deposit(EUR(20))),
      RebelDomainEvent(Deposit(EUR(30)))
    ), RebelDomainEvent(Deposit(EUR(10)))) shouldBe StaticAccept
  }

  it should "reject when all possible states disallow (fail preconditions), using static information only" in {
    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(), RebelDomainEvent(Withdraw(EUR(1000)))) shouldBe a[Reject]
  }

  it should "reject when all possible states disallow (fail preconditions)" in {
    // TODO check if DynamicReject
    psacDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(RebelDomainEvent(Deposit(EUR(20)))), RebelDomainEvent(Withdraw(EUR(1000)))) shouldBe a[Reject]
    psacDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(
      RebelDomainEvent(Deposit(EUR(20))),
      RebelDomainEvent(Deposit(EUR(30)))
        // TODO check if DynamicReject
    ), RebelDomainEvent(Withdraw(EUR(1000)))) shouldBe a[Reject]
  }

  it should "decline when some, but not all, possible states disallow (fail preconditions)" in {
    psacDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(RebelDomainEvent(Deposit(EUR(100)))), RebelDomainEvent(Withdraw(EUR(150)))) shouldBe a[Delay]
  }

  it should "fail fast on always incompatible states" in {
    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(RebelDomainEvent(Unblock)), RebelDomainEvent(OpenAccount(testIban1, EUR(1000)))) shouldBe StaticReject
  }
}

class AllowCommandDecisionSemigroupSpec extends TestBase with ValidatedMatchers {
  /**
    * /**
    * * Ways to combine `AllowCommandDecision`. combines error/delay messages
    * * Accept + Reject => Reject
    * * Accept + Delay => Delay
    * * TODO check if this is correctly implemented
    **/
    * implicit val allowCommandDecisionSemigroup: Semigroup[AllowCommandDecision] = Semigroup.instance {
    * case (a1: Accept, a2: Accept)                   => a2
    * case (r1: DynamicReject, r2: DynamicReject)     => DynamicReject(r1.errors combine r2.errors)
    * case (r1: DynamicReject, r2: Reject)            => r1 // TODO information is lost, r2
    * case (r1: DynamicReject, _: Accept)             => r1 // TODO information is lost, r2
    * case (r1: DynamicReject, _: Delay)              => r1 // TODO information is lost, r2
    * case (r1: Reject, r2: DynamicReject)            => r2 // TODO information is lost, r1
    * case (r1: Reject, r2: Reject)                   => r1
    * case (r1: Reject, _: Accept)                    => r1
    * case (r1: Reject, _: Delay)                     => r1
    * case (r1: FunctionalDelay, r2: FunctionalDelay) => FunctionalDelay(r1.errors combine r2.errors)
    * case (r1: FunctionalDelay, r2: Delay)           => r1 // TODO information is lost, r2
    * case (r1: FunctionalDelay, _: Accept)           => r1 // TODO information is lost, r2
    * case (r1: Delay, r2: FunctionalDelay)           => r2 // TODO information is lost, r1
    * case (r1: Delay, r2: Delay)                     => r1
    * case (r1: Delay, r2: Accept)                    => r1
    * }
    */

  val reject1 = StaticReject
  val reject2 = StaticReject

  val rebelError = GenericRebelError("test error")
  val dynamicReject = DynamicReject(RebelErrors.of(rebelError))
  val functionalDelay = FunctionalDelay(RebelErrors.of(rebelError))

  behavior of "allowCommandDecisionSemigroup"

  it should "correctly merge Rejects => Reject" in {
    (NonEmptyChain(StaticReject, StaticReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Reject]
    (NonEmptyChain(dynamicReject, StaticReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Reject]
    (NonEmptyChain(dynamicReject, dynamicReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Reject]
    (NonEmptyChain(StaticReject, dynamicReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Reject]
  }

  it should "correctly merge Accepts => Accept" in {
    (NonEmptyChain(StaticAccept, StaticAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Accept]
    (NonEmptyChain(DynamicAccept, StaticAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Accept]
    (NonEmptyChain(DynamicAccept, DynamicAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Accept]
    (NonEmptyChain(StaticAccept, DynamicAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Accept]
  }

  it should "correctly merge Delay => Delay" in {
    (NonEmptyChain(functionalDelay, TwoPLLockDelay, MaxInProgressReachedDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(TwoPLLockDelay, MaxInProgressReachedDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(functionalDelay, TwoPLLockDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    //    (NonEmptyChain(StaticAccept, DynamicAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    // TODO add more combinations?
  }

  it should "correctly merge Accept and Reject => Delay" in {
    (NonEmptyChain(StaticReject, StaticAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(StaticAccept, StaticReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(dynamicReject, DynamicAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(DynamicAccept, dynamicReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
  }

  it should "correctly merge Accept and Delay => Delay" in {
    (NonEmptyChain(functionalDelay, StaticAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(StaticAccept, functionalDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(functionalDelay, DynamicAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(DynamicAccept, functionalDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]

    (NonEmptyChain(TwoPLLockDelay, StaticAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(StaticAccept, TwoPLLockDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(TwoPLLockDelay, DynamicAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(DynamicAccept, TwoPLLockDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]

    (NonEmptyChain(MaxInProgressReachedDelay, StaticAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(StaticAccept, MaxInProgressReachedDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(MaxInProgressReachedDelay, DynamicAccept): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(DynamicAccept, MaxInProgressReachedDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
  }

  it should "correctly merge Reject and Delay => Delay" in {
    (NonEmptyChain(functionalDelay, StaticReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(StaticReject, functionalDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(functionalDelay, dynamicReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(dynamicReject, functionalDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]

    (NonEmptyChain(TwoPLLockDelay, StaticReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(StaticReject, TwoPLLockDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(TwoPLLockDelay, dynamicReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(dynamicReject, TwoPLLockDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]

    (NonEmptyChain(MaxInProgressReachedDelay, StaticReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(StaticReject, MaxInProgressReachedDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(MaxInProgressReachedDelay, dynamicReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
    (NonEmptyChain(dynamicReject, MaxInProgressReachedDelay): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
  }

  it should "correctly merge Delay and Delay => Delay" in {
    (NonEmptyChain(functionalDelay, StaticReject): NonEmptyChain[AllowCommandDecision]).reduce shouldBe a[Delay]
  }
}