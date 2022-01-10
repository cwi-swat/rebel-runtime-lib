// Generated @ 05-01-2017 15:18:45
package com.ing.corebank.rebel.simple_transaction

import akka.actor.Props
import com.ing.rebel._
import com.ing.rebel._
import com.github.nscala_time.time.Imports._
import com.ing.corebank.rebel.simple_transaction._
import com.ing.rebel.RebelSharding.RebelShardingExtension

import scala.reflect.ClassTag
import com.ing.rebel._
import com.ing.rebel.util.CirceSupport._
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto._
import com.ing.corebank.rebel.simple_transaction.Account.{Deposit, Withdraw}
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel.specification.{RebelSpecification, Specification}
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.{ShardingContactPoint, TwoPhaseCommit}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{ParticipantId, SyncAction, SyncOperation, TransactionId}
import squants.Money
import squants.market.EUR
//import import com.ing.corebank.rebel.actor.TransactionActor._

import org.joda.time.{Days, Months, Seconds}
import com.github.nscala_time.time.Imports._
import collection.Map
import scala.concurrent.Await
import scala.concurrent.duration._
import cats.implicits._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import io.circe.generic.auto._

import com.ing.corebank.rebel._
import com.ing.corebank.rebel.sharding._


object Transaction extends Specification {
  case class Data(
                   //    id: Option[Int] = None,
                   amount: Option[Money] = None,
                   from: Option[Iban] = None,
                   to: Option[Iban] = None
                 )
  // TODO generate read id/key etc
  type Key = Int
  sealed trait State extends RebelState
  case object Booked extends State
  case object Uninit extends State
  case object Failed extends State

  sealed trait Event extends SpecificationEvent

  final case class Book(amount: Money, from: Iban, to: Iban) extends Event

  final case object Fail extends Event

  val label: String = "Transaction"
  implicit object Logic extends TransactionLogic

  def props: Props = Props(new Transaction)

  import io.circe.{Encoder, Decoder}
  import io.circe.generic.semiauto._

  implicit val decoder: Decoder[Event] = deriveDecoder[Event]
  implicit val encoder: Encoder[Event] = deriveEncoder[Event]

  override def keyable: RebelKeyable[Key] = implicitly

  override implicit val dataEncoder: Encoder[Data] = deriveEncoder
  override implicit val stateEncoder: Encoder[State] = deriveEncoder
}

trait TransactionLogic extends RebelSpecification[Transaction.type] {

  import Transaction._

  override def initialState: RState = Uninit

  override def allStates: Set[RState] = Set(Failed, Uninit, Booked)

  override def finalStates: Set[RState] = Set(Booked, Failed)


  override def checkPreConditions(data: RData, now: DateTime): PartialFunction[SpecEvent, RebelConditionCheck] = {
    case Book(amount, from, to) => {
      checkPreCondition((to != from), "to != from") combine
        checkPreCondition((amount.currency == EUR), "amount.currency == EUR") combine
        checkPreCondition((amount > EUR(0.00)), "amount > EUR 0.00")

    }
    case Fail                   => {
      RebelConditionCheck.success
    }
  }

  override def nextState: PartialFunction[(RState, SpecEvent), RState] = {
    case (Uninit, _: Book)      => Booked
    case (Uninit, _: Fail.type) => Failed
  }

  override def applyPostConditions(data: RData, domainEvent: RDomainEvent): RData =
    domainEvent.specEvent match {
      case Book(amount, from, to) =>
        require(data.isEmpty, s"Data should be `Uninitialised`, was: $data")
        Initialised(Data(amount = Some(amount), from = Some(from), to = Some(to)))
      case Fail                   =>
        require(data.nonEmpty, s"Data should be `Initialised`, was: $data")
        data
    }

  override def checkPostConditions(data: RData, now: DateTime, nextData: RData): PartialFunction[SpecEvent, RebelConditionCheck] = {
    case Book(amount, from, to) =>
      checkPostCondition((nextData.get.amount.get == (amount)), "new this.amount == amount") combine
        checkPostCondition((nextData.get.from.get == (from)), "new this.from == from") combine
        checkPostCondition((nextData.get.to.get == (to)), "new this.to == to")
    case Fail                   => {
      RebelConditionCheck.success
    }
  }

  //TODO Generate real implementation
  override def invariantsHold(projectedStateData: RData): RebelConditionCheck = RebelConditionCheck.success

  override def syncOperations(now: DateTime, transactionId: TransactionId): PartialFunction[(SpecEvent, RData), Set[SyncOperation]] = {
    case (Book(amount, from, to), _) =>
      Set(
        SyncAction(ParticipantId(transactionId, from.toString), ShardingContactPoint(Account, from), RebelDomainEvent(Withdraw(amount))),
        SyncAction(ParticipantId(transactionId, to.toString), ShardingContactPoint(Account, to), RebelDomainEvent(Deposit(amount)))
      )
  }

  override def alwaysCompatibleEvents: PartialFunction[(Event, Event), Boolean] = PartialFunction.empty

  override def failFastEvents: PartialFunction[(Event, Event), Boolean] = PartialFunction.empty
}

class Transaction
  extends RebelFSMActor[Transaction.type] with TransactionLogic
  