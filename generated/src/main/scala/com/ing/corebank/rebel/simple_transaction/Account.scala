// Generated @ 05-01-2017 15:18:44
package com.ing.corebank.rebel.simple_transaction

import com.ing.rebel._
import com.github.nscala_time.time.Imports._
import akka.actor._
import akka.pattern.ask
import com.ing.corebank.rebel.simple_transaction._
import com.ing.rebel.RebelSharding.RebelShardingExtension

import scala.reflect.ClassTag
import com.ing.rebel.util.CirceSupport._
import io.circe.Decoder
import io.circe.generic.auto._
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel.specification.{RebelSpecification, Specification}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{SyncOperation, TransactionId}
//import import com.ing.corebank.rebel.actor.AccountActor._


import org.joda.time.{Days, Months, Seconds}
import com.github.nscala_time.time.Imports._
import squants.market._
import collection.Map
import scala.concurrent.Await
import scala.concurrent.duration._
import cats.implicits._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import com.ing.corebank.rebel._
import com.ing.corebank.rebel.sharding._

object Account extends Specification {

  case class Data(
//    accountNumber: Option[Iban] = None,
    balance: Option[Money] = None
  )
  // TODO generate read id/key etc
  type Key = Iban
  sealed trait AccountState extends RebelState
  type State = AccountState
  case object Opened extends AccountState
  case object Init extends AccountState
  case object Closed extends AccountState
  case object Blocked extends AccountState

  sealed trait Event extends SpecificationEvent

  final case class Interest(currentInterest : Double) extends Event

  final case class OpenAccount(initialDeposit : Money) extends Event

  final case class Close() extends Event

  final case class Unblock() extends Event

  final case class Block() extends Event

  final case class Withdraw(amount : Money) extends Event

  final case class Deposit(amount : Money) extends Event


  val label: String = "Account"
  implicit object Logic extends AccountLogic

  import io.circe.{Encoder, Decoder}
  import io.circe.generic.semiauto._
  implicit val decoder: Decoder[Event] = deriveDecoder[Event]
  implicit val encoder: Encoder[Event] = deriveEncoder[Event]

  override def keyable: RebelKeyable[Iban] = implicitly

  override implicit val dataEncoder: Encoder[Data] = deriveEncoder
  override implicit val stateEncoder: Encoder[AccountState] = deriveEncoder
}

trait AccountLogic extends RebelSpecification[Account.type] {
   import Account._
   override def initialState: AccountState = Init
   override def allStates: Set[AccountState] = Set(Blocked, Closed, Init, Opened)
   override def finalStates: Set[AccountState] = Set(Closed)


  private val eur0: Money = EUR(0.00)

  private val eur50: Money = EUR(50.00)

  override def checkPreConditions(data: RData, now: DateTime): PartialFunction[Event, RebelConditionCheck] = {
    case Interest(currentInterest) => {

        checkPreCondition((currentInterest <= 10.percent), "currentInterest <= 10%") combine
        checkPreCondition((currentInterest > 0.percent), "currentInterest > 0%")

      }
      case OpenAccount(initialDeposit) => {

        checkPreCondition((initialDeposit >= eur50), "initialDeposit >= EUR 50.00")

      }
      case Close() => {

            checkPreCondition(({
              require(data.nonEmpty, s"data should be set, was: $data")
              require(data.get.balance.nonEmpty, s"data.get.balance should be set, was: $data.get.balance")
              data.get.balance.get
            } == eur0), "this.balance == EUR 0.00")

      }
      case Unblock() => {
         RebelConditionCheck.success
      }
      case Block() => {
         RebelConditionCheck.success
      }
      case Withdraw(amount) => {

            checkPreCondition((amount > eur0), "amount > EUR 0.00") combine
            checkPreCondition(({
              require(data.nonEmpty, s"data should be set, was: $data")
              require(data.get.balance.nonEmpty, s"data.get.balance should be set, was: $data.get.balance")
              data.get.balance.get
            } - amount >= eur0), "this.balance - amount >= EUR 0.00")

      }
      case Deposit(amount) => {

            checkPreCondition((amount > eur0), "amount > EUR 0.00")

      }
   }

   override def nextState: PartialFunction[(AccountState, Event), AccountState] = {
       case (Opened, _: Withdraw) => Opened
       case (Opened, _: Deposit) => Opened
       case (Opened, _: Interest) => Opened
       case (Opened, _: Block) => Blocked
       case (Opened, _: Close) => Closed
       case (Init, _: OpenAccount) => Opened
       case (Blocked, _: Unblock) => Opened
   }

    override def applyPostConditions(data: RData, domainEvent: RDomainEvent): RData =
      domainEvent.specEvent match {
        case Interest(currentInterest) =>
          require(data.nonEmpty, s"Data should be `Initialised`, was: $data")
          data.map(_.copy(balance = Some(data.get.balance.get + singleInterest({
                            require(data.nonEmpty, s"data should be set, was: $data")
                            require(data.get.balance.nonEmpty, s"data.get.balance should be set, was: $data.get.balance")
                            data.get.balance.get
                          }, currentInterest))))
        case OpenAccount(initialDeposit) =>
          require(data.isEmpty, s"Data should be `Uninitialised`, was: $data")
          Initialised(Data(balance = Some(initialDeposit)))
        case Close() =>
          require(data.nonEmpty, s"Data should be `Initialised`, was: $data")
          data.map(_.copy())
        case Unblock() =>
          require(data.nonEmpty, s"Data should be `Initialised`, was: $data")
          data.map(_.copy())
        case Block() =>
          require(data.nonEmpty, s"Data should be `Initialised`, was: $data")
          data.map(_.copy())
        case Withdraw(amount) =>
          require(data.nonEmpty, s"Data should be `Initialised`, was: $data")
          data.map(_.copy(balance = Some({
                            require(data.nonEmpty, s"data should be set, was: $data")
                            require(data.get.balance.nonEmpty, s"data.get.balance should be set, was: $data.get.balance")
                            data.get.balance.get
                          } - amount)))
        case Deposit(amount) =>
          require(data.nonEmpty, s"Data should be `Initialised`, was: $data")
          data.map(_.copy(balance = Some(data.get.balance.get + amount)))
      }

   override def checkPostConditions(data: RData, now: DateTime, nextData: RData): PartialFunction[Event, RebelConditionCheck] = {
      case Interest(currentInterest) => {

            checkPostCondition((nextData.get.balance.get == (data.get.balance.get + singleInterest({
              require(data.nonEmpty, s"data should be set, was: $data")
              require(data.get.balance.nonEmpty, s"data.get.balance should be set, was: $data.get.balance")
              data.get.balance.get
            }, currentInterest))), "new this.balance == this.balance + singleInterest(this.balance, currentInterest)")

      }
      case OpenAccount(initialDeposit) => {

            checkPostCondition((nextData.get.balance.get == (initialDeposit)), "new this.balance == initialDeposit")

      }
      case Close() => {
         RebelConditionCheck.success
      }
      case Unblock() => {
         RebelConditionCheck.success
      }
      case Block() => {
         RebelConditionCheck.success
      }
      case Withdraw(amount) => {

            checkPostCondition((nextData.get.balance.get == ({
              require(data.nonEmpty, s"data should be set, was: $data")
              require(data.get.balance.nonEmpty, s"data.get.balance should be set, was: $data.get.balance")
              data.get.balance.get
            } - amount)), "new this.balance == this.balance - amount")

      }
      case Deposit(amount) => {

            checkPostCondition((nextData.get.balance.get == (data.get.balance.get + amount)), "new this.balance == this.balance + amount")

      }
   }

   //TODO Generate real implementation
   override def invariantsHold(projectedStateData: RData): RebelConditionCheck = RebelConditionCheck.success

   def singleInterest(balance : Money, interest : Double) : Money={
     (balance * interest)
   }

  override def syncOperations(now: DateTime, transactionId: TransactionId): PartialFunction[(SpecEvent, RData), Set[SyncOperation]] = noEventsWithSyncs

  override def alwaysCompatibleEvents: PartialFunction[(Event, Event), Boolean] = {
    case (_: Deposit, _: Block)     => true
    case (_: Deposit, _: Deposit)   => true
    case (_: Deposit, _: Interest)  => true
    case (_: Interest, _: Block)    => true
    case (_: Interest, _: Deposit)  => true
    case (_: Interest, _: Interest) => true
    case (_: Withdraw, _: Block)    => true
    case (_: Withdraw, _: Deposit)  => true
    case (_: Withdraw, _: Interest) => true
  }

  override def failFastEvents: PartialFunction[(Event, Event), Boolean] = {
    case (_: Block, _: OpenAccount)    => true
    case (_: Close, _: OpenAccount)    => true
    case (_: Close, _: Unblock)        => true
    case (_: Close, _: Withdraw)       => true
    case (_: Deposit, _: OpenAccount)  => true
    case (_: Deposit, _: Unblock)      => true
    case (_: Interest, _: OpenAccount) => true
    case (_: Interest, _: Unblock)     => true
    case (_: OpenAccount, _: Close)    => true
    case (_: OpenAccount, _: Unblock)  => true
    case (_: Unblock, _: OpenAccount)  => true
    case (_: Withdraw, _: OpenAccount) => true
    case (_: Withdraw, _: Unblock)     => true
  }

}
  