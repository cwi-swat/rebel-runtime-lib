package com.ing.example

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import com.github.nscala_time.time.Imports._
import com.ing.example.Account._
import cats.implicits._
import com.ing.example.Transaction.{Book, Booked, Data, Fail, Failed, Init, SpecEvent, Start, Validated}
import com.ing.example.sharding._
import com.ing.rebel.RebelError.{GenericRebelError, SyncFailed}
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel._
import com.ing.rebel.messages.RebelCommand
import com.ing.rebel.specification.{RebelSpecification, Specification}
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.{EntityHost, ShardingContactPoint, TwoPhaseCommit}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{Command => _, _}
import io.circe.Encoder
import squants.market.{EUR, Money}
import io.circe.generic.auto._
import com.ing.rebel.util.CirceSupport._

import scala.concurrent.Future
import scala.concurrent.duration._

// TODO update this file to represent generated code more / is a good example on what to generate
object Transaction extends Specification {
  case class Data(amount: Money, from: Iban, to: Iban, createdOn: DateTime, bookedOn: DateTime) {
    override def toString: String = s"$from->$to:$amount"
  }

  override type Key = Int

  sealed trait SpecEvent extends SpecificationEvent
  case class Start(id: Int, amount: Money, from: Iban, to: Iban, bookOn: DateTime) extends SpecEvent
  case object Book extends SpecEvent
  case object Fail extends SpecEvent

  sealed trait State extends RebelState
  case object Init extends State
  case object Validated extends State
  case object Booked extends State
  case object Failed extends State

  implicit object Logic extends TransactionLogic

  def props: Props = Props(new Transaction)

  import io.circe.generic.auto._
  import com.ing.rebel._
  import com.ing.rebel.util.CirceSupport._

  //   val iEncoder: Encoder[Iban] = Iban.encoder
  //   val dtEncoder: Encoder[DateTime] = implicitly[Encoder[DateTime]]
  //   val mEncoder: Encoder[Money] = implicitly[Encoder[Money]]
  //  implicit val dataEncoder: Encoder[Data] = io.circe.generic.extras.semiauto.deriveEncoder[Data]
  implicit val dataEncoder: Encoder[Data] = io.circe.generic.auto.exportEncoder[Data].instance // is this null?
  implicit val stateEncoder: Encoder[State] = io.circe.generic.extras.semiauto.deriveEnumerationEncoder[State]

  override type Event = SpecEvent

  override def keyable: RebelKeyable[Int] = implicitly

  override val specificationName: String = "Transaction"
}

trait TransactionLogic extends RebelSpecification[Transaction.type] {
  override def nextState: PartialFunction[(Transaction.State, Transaction.SpecEvent), Transaction.State] = {
    case (Init, _: Start)  => Validated
    case (Validated, Book) => Booked
    case (Validated, Fail) => Failed
  }

  override lazy val initialState: Transaction.State = Init
  override lazy val allStates: Set[Transaction.State] = Set(Init, Validated, Booked, Failed)
  override val finalStates: Set[Transaction.State] = Set(Booked, Failed)


  //  def preConditions: PreConditions
  override def checkPreConditions(data: RData, now: DateTime): PartialFunction[SpecEvent, RebelConditionCheck] = {
    //    @doc{From account must exist.}
    //    initialized Account[from];
    //    @doc{To account must exist.}
    //    initialized Account[to];
    //
    //    amount > EUR 0.00;
    case Start(id, amount, from, to, bookOn) =>
      checkPreCondition(to != from, "to != from") combine
        checkPreCondition(amount.currency == EUR, "amount.currency == EUR") combine
        checkPreCondition(amount > EUR(0.00), "amount > EUR 0.00")
    //      true

    // implicitly checks for initialized state
    //    @doc{Synchronize with the withdraw event of the from account. Can only succeed if the account is in the 'opened' state}
    //    Account[this.from].withdraw(this.amount);
    //    @doc{Synchronize with the deposit event of the to account. Can only succeed if the account is in the 'opened' state}
    //    Account[this.to].deposit(this.amount);
    case Book => RebelConditionCheck.success
    case Fail => RebelConditionCheck.success
  }

  override def applyPostConditions(currentData: RData, domainEvent: RDomainEvent): RData =
    domainEvent.specEvent match {
      case Start(id, amount, from, to, bookOn) => Initialised(Data(amount = amount, from = from, to = to, createdOn = domainEvent.timestamp, bookedOn = bookOn))
      case Book                                => currentData
      case Fail                                => currentData
    }

  override def checkPostConditions(currentData: RData, now: DateTime, projectedData: RData): PartialFunction[SpecEvent, RebelConditionCheck] = {
    case Start(id, amount, from, to, bookOn) =>
      checkPostCondition(projectedData.get.amount == amount, "new this.amount == amount") combine
        checkPostCondition(projectedData.get.from == from, "new this.from == from") combine
        checkPostCondition(projectedData.get.to == to, "new this.from == from") combine
        checkPostCondition(projectedData.get.createdOn == now, "new this.createdOn == now") combine
        checkPostCondition(projectedData.get.bookedOn == bookOn, "new this.createdOn == now")
    case Book                                => RebelConditionCheck.success // TODO
    case Fail                                => RebelConditionCheck.success // TODO
  }

  override def invariantsHold(data: RData): RebelConditionCheck = RebelConditionCheck.success

  override def syncOperations(now: DateTime, transactionId: TransactionId): PartialFunction[(SpecEvent, RData), Set[SyncOperation]] = {

    //    override def syncOperations(domainEvent: RDomainEvent)(data: RData): RebelCheck[Set[SyncOperation]] =
    //    domainEvent.specEvent
    case (Book, Initialised(tData))               => Set(
      TwoPhaseCommit.SyncAction(ParticipantId(transactionId, tData.from.toString),
        ShardingContactPoint(Account, tData.from),
        RebelDomainEvent(Withdraw(tData.amount))),
      TwoPhaseCommit.SyncAction(ParticipantId(transactionId, tData.to.toString),
        ShardingContactPoint(Account, tData.to),
        RebelDomainEvent(Deposit(tData.amount)))
    )
    case (Start(id, amount, from, to, bookOn), _) =>
      Set(
        //            TwoPhaseCommit.SyncAction.SyncCommand(LockId.uniqueLockId(id.toString), ActorRefContactPoint(selfLock), domainEvent.command),
        TwoPhaseCommit.SyncAction.syncInitialized(
          ParticipantId(transactionId, from.toString), transactionId, ShardingContactPoint(Account, from)),
        TwoPhaseCommit.SyncAction.syncInitialized(
          ParticipantId(transactionId, to.toString), transactionId, ShardingContactPoint(Account, to))
      )
  }

  override def alwaysCompatibleEvents: PartialFunction[(Transaction.SpecEvent, Transaction.SpecEvent), Boolean] = PartialFunction.empty

  override def failFastEvents: PartialFunction[(Transaction.SpecEvent, Transaction.SpecEvent), Boolean] = PartialFunction.empty
}

class Transaction
  extends RebelFSMActor[Transaction.type] with TransactionLogic
