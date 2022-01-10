package com.ing.example

import akka.actor._
import cats.data.Validated
import cats.implicits._
import com.github.nscala_time.time.Imports.DateTime
import com.ing
import com.ing.example
import com.ing.rebel.RebelError.{InvariantFailed, PreConditionFailed}
import com.ing.rebel._
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel.specification.{RebelSpecification, Specification}
import com.ing.rebel.sync.twophasecommit.EntityHost
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{Initialize, SyncOperation, TransactionId}
import com.ing.rebel.util.LoggingInterceptor
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import org.joda.time.DateTime
import squants.market.{EUR, Money}

object Account extends Specification {
  override val specificationName: String = "Account"

  case class AccountData(balance: Option[Money])

  type Key = Iban
  override def keyable: RebelKeyable[Key] = implicitly
  override type State = AccountState
  override type Data = AccountData
  override type Event = AccountEvent

  implicit val config: Configuration = Configuration.default

  sealed trait AccountState extends RebelState
  case object New extends AccountState
  case object Opened extends AccountState
  case object Blocked extends AccountState
  case object Closed extends AccountState
  case object Archived extends AccountState

  sealed trait AccountEvent extends SpecificationEvent

  object OpenAccount {
    val minimalDeposit = EUR(50)
  }

  final case class OpenAccount(accountNr: Iban, initialDeposit: Money) extends AccountEvent
  final case class Interest(rate: Double) extends AccountEvent
  final case class Deposit(amount: Money) extends AccountEvent
  final case class Withdraw(amount: Money) extends AccountEvent
  final case object Close extends AccountEvent
  final case object Block extends AccountEvent
  final case object Unblock extends AccountEvent
  final case object Archive extends AccountEvent

  implicit object Logic extends AccountLogic

  val props: Props = Props(new RebelFSMActor[Account.type] with AccountLogic)

  //  import AccountState
  import io.circe.generic.auto._
  import com.ing.rebel._
  import com.ing.rebel.util.CirceSupport._

  implicit val dataEncoder: Encoder[AccountData] = io.circe.generic.extras.semiauto.deriveEncoder[AccountData]
  //  object AccountState {
  implicit val stateEncoder: Encoder[AccountState] = io.circe.generic.extras.semiauto.deriveEnumerationEncoder[AccountState]
  //  }

  implicit val commandEncoder: Encoder[AccountEvent] = io.circe.generic.extras.semiauto.deriveEncoder[AccountEvent]


}

trait AccountLogic extends RebelSpecification[Account.type]  {
  import Account._
  override lazy val initialState: AccountState = New
//  override lazy val initialState: Account.AccountState = New
  override lazy val allStates: Set[AccountState] = Set(New, Opened, Blocked, Closed, Archived)
  override val finalStates: Set[AccountState] = Set(Closed, Archived)

  override def checkPreConditions(data: RData, now: DateTime): PartialFunction[AccountEvent, RebelConditionCheck] = {
    case OpenAccount(accountNr, initialDeposit) =>
      checkPreCondition(initialDeposit >= OpenAccount.minimalDeposit, "initialDeposit >= OpenAccount.minimalDeposit") combine
        checkPreCondition(initialDeposit <= EUR(Integer.MAX_VALUE), "initialDeposit <= EUR(Integer.MAX_VALUE)")
    case Withdraw(amount)                       =>
      checkPreCondition(amount > EUR(0.00), "amount > EUR(0.00)") combine
        checkPreCondition(data.exists(_.balance.exists(_ >= amount)), " data.exists(_.balance.exists(_ >= amount)")
    case Deposit(amount)                        => RebelConditionCheck.success
    case Interest(rate)                         => RebelConditionCheck.success
    case _                                      => RebelConditionCheck.failure(PreConditionFailed("precondition check not implemented."))
  }

  override def nextState: PartialFunction[(AccountState, AccountEvent), AccountState] = {
    case (New, _: OpenAccount)              => Opened
    case (Opened, _: Withdraw | _: Deposit | _: Interest) => Opened
  }

  override def applyPostConditions(data: RData, domainEvent: RDomainEvent): RData =
    domainEvent.specEvent match {
      case OpenAccount(accountNr, initialDeposit) =>
        Initialised(AccountData(balance = Some(initialDeposit)))
      case Withdraw(amount)                       =>
        data.map(rdata => rdata.copy(balance = rdata.balance.map(_ - amount)))
      case Deposit(amount)                        =>
        data.map(rdata => rdata.copy(balance = rdata.balance.map(_ + amount)))
      case Interest(rate ) =>
        data.map(rdata => rdata.copy(balance = rdata.balance.map(b => b + b * rate)))
    }


  override def checkPostConditions(currentData: RData, now: DateTime, nextData: RData): PartialFunction[AccountEvent, RebelConditionCheck] = {
    case OpenAccount(accountNr, initialDeposit) =>
      checkPostCondition(nextData.exists(_.balance.contains(initialDeposit)), "new balance == initialDeposit")
    //        val predicates = List(
    //          ("rdata.balance.contains(initialDeposit)", rdata.balance.contains(initialDeposit)),
    //          ("rdata.accountNumber == accountNr", rdata.accountNumber == accountNr),
    //          ("rdata.currency.contains(OpenAccount.theCurrency)", rdata.currency.contains(OpenAccount.theCurrency))
    //        )

    case Withdraw(amount) =>
      checkPostCondition(nextData.exists(_.balance.exists(oldBalance => currentData.exists(_.balance.exists(oldBalance == _ - amount)))), "new balance == balance - amount")
    //      nextData.map { rdata =>
    //        val predicates: List[(String, Boolean)] = List(
    //          ("rdata.balance.map(_ == currentData.map(_.balance.map(_ - amount)))", rdata.balance.exists(_ == currentData.map(_.balance.map(_ - amount))))
    //        )
    case Deposit(amount) =>

      checkPostCondition(nextData.exists(_.balance.exists(oldBalance => currentData.exists(_.balance.exists(oldBalance == _ + amount)))), "new balance == balance + amount")
    //      nextData.map { rdata =>
    //        val predicates = List(
    //          ("rdata.balance.map(_ == currentData.map(_.balance.map(_ - amount)))", rdata.balance.map(_ == currentData.map(_.balance.map(_ + amount))))
    //        )
    case Interest(rate) =>
      checkPostCondition(nextData.exists(_.balance.exists(oldBalance => currentData.exists(_.balance.exists(b => oldBalance == b + b*rate)))), "new balance == balance + rate * balance")
  }

  override def alwaysCompatibleEvents: PartialFunction[(Event, Event), Boolean] = {
    case (_: Deposit, Block)     => true
    case (_: Deposit, _: Deposit)   => true
    case (_: Deposit, _: Interest)  => true
    case (_: Interest, Block)    => true
    case (_: Interest, _: Deposit)  => true
    case (_: Interest, _: Interest) => true
    case (_: Withdraw, Block)    => true
    case (_: Withdraw, _: Deposit)  => true
    case (_: Withdraw, _: Interest) => true
  }

  override def failFastEvents: PartialFunction[(Event, Event), Boolean] = {
    case (Block, _: OpenAccount)    => true
    case (Close, _: OpenAccount)    => true
    case (Close, Unblock)        => true
    case (Close, _: Withdraw)       => true
    case (_: Deposit, _: OpenAccount)  => true
    case (_: Deposit, Unblock)      => true
    case (_: Interest, _: OpenAccount) => true
    case (_: Interest, Unblock)     => true
    case (_: OpenAccount, Close)    => true
    case (_: OpenAccount, Unblock)  => true
    case (Unblock, _: OpenAccount)  => true
    case (_: Withdraw, _: OpenAccount) => true
    case (_: Withdraw, Unblock)     => true
  }

  override def invariantsHold(data: RData): RebelConditionCheck = {
    // TODO make checkInvariant generic
    if (data.exists { rdata =>
      List(rdata.balance.exists(_ >= EUR(0))
        //      , data.balance.exists(_ < EUR(1000))
      ).forall(identity)
    }) {
      RebelConditionCheck.success
    } else {
      RebelConditionCheck.failure(InvariantFailed("Some invariant failed."))
    }
  }

  override def syncOperations(now: DateTime, transactionId: TransactionId): PartialFunction[(SpecEvent, RData), Set[SyncOperation]] = noEventsWithSyncs
}
