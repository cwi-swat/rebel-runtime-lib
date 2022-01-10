package com.ing.research

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.Timeout
import com.ing.research.Account._
import com.ing.research.Lib._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpecLike, Matchers, ParallelTestExecution}

import scala.concurrent.Future
import scala.language.reflectiveCalls
import scala.reflect.ClassTag

object Lib {
  trait Command
  case class Prepare(command: Command)
  case class PrepareAccepted(id: UUID)
  case object PrepareDeclined

  case class RemovePrepared(id: UUID)
  case class RemovePrepareDone(id: UUID)

  case class Commit(id: UUID)
  case class CommitAccepted(id: UUID)
}

object Account {
  case class Withdraw(amount: Int) extends Command
  case class Deposit(amount: Int) extends Command

  type State = Int
}

class Account extends Actor with ActorLogging {

  var state: State = 100 // balance

  // command id, command value, state after command
  case class TentativeInfo(id:UUID, command: Command, state: State)
  var tentatives: Seq[TentativeInfo] = Seq()

  // gets the latest tentative state
  def tentativeState: State = tentatives.lastOption.map(_.state).getOrElse(state)

  def removeTentative(id: UUID): Unit = {
    // remove from list
    tentatives = tentatives.filterNot(_.id == id)
    // recalculate tentative states
    tentatives = tentatives.foldLeft((Seq[TentativeInfo](), state)) {
      case ((acc, prevState), info) => {
        val nextState = applyPostconditions(prevState, info.command)
        (acc :+ info.copy(state = nextState), nextState)
      }
    }._1
  }

  def checkPreconditions(command: Command): Boolean =
    command match {
      case Withdraw(amount) =>
        amount > 0 &&
          tentativeState - amount >= 0
      case Deposit(amount)  =>
        amount > 0 &&
          tentativeState + amount >= 0
    }

  def applyPostconditions(currentState: State, command: Command) : State =
    command match {
      case Withdraw(amount) =>
        currentState - amount
      case Deposit(amount) =>
        currentState + amount
    }

  override def receive: Receive = {
    case Prepare(c) if checkPreconditions(c) =>
      val id = UUID.randomUUID()
      tentatives :+= TentativeInfo(id, c, applyPostconditions(tentativeState, c))
      log.info(s"New tentative state: `$tentativeState`, rest: `$tentatives`")
      sender() ! PrepareAccepted(id)
    case Prepare(_)                                           =>
      sender() ! PrepareDeclined
    case RemovePrepared(id) =>
      removeTentative(id)
      sender() ! RemovePrepareDone(id)
    case Commit(id)                                           =>
      val TentativeInfo(_, command, newState) = tentatives.find(_.id == id).get
      state = newState
      removeTentative(id)
      sender() ! CommitAccepted(id)
  }
}


class TryOut extends TestKit(ActorSystem())
  with FlatSpecLike with Matchers
  with ScalaFutures with ImplicitSender
  with ParallelTestExecution {

  import akka.pattern._

  import scala.concurrent.duration._

  implicit val timeOut: Timeout = 3.seconds

  val account: TestActorRef[Account] = TestActorRef(Props[Account])

  private def getId[A <: { def id: UUID } : ClassTag](withdraw: => Future[Any]): UUID = {
    withdraw.mapTo[A].futureValue.id
  }

  "account" should "prepare and commit" in {
    // prepare should do nothing
    val id: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(100)))
    account.underlyingActor.state should be(100)

    // commit should allow
    account ! Commit(id)
    expectMsg(CommitAccepted(id))
    account.underlyingActor.state should be(0)
  }



  it should "prepare and decline" in {
    account ! Prepare(Withdraw(100))
    expectMsgType[PrepareAccepted]
    account.underlyingActor.state should be(100)

    account ! Prepare(Withdraw(100))
    expectMsg(PrepareDeclined)
    account.underlyingActor.state should be(100)
  }

  it should "handle multiple prepares and update tentative state correctly" in {
    val id: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(25)))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(75)

    val id2: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(25)))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(50)

    val id3: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(25)))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(25)

    val id4: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(25)))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(0)

    account ! Prepare(Withdraw(25))
    expectMsg(PrepareDeclined)
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(0)
  }

  it should "handle prepare and removal of prepare" in {
    val id: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(25)))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(75)

    getId[RemovePrepareDone](account ? RemovePrepared(id))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(100)
  }

  it should "handle multiple prepares and removal of them" in {
    val id: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(10)))
    val id2: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(25)))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(65)

    getId[RemovePrepareDone](account ? RemovePrepared(id))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(75)

    getId[RemovePrepareDone](account ? RemovePrepared(id2))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(100)
  }

  it should "handle multiple prepares and commits of them" in {
    val id: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(10)))
    val id2: UUID = getId[PrepareAccepted](account ? Prepare(Withdraw(25)))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(65)

    getId[CommitAccepted](account ? Commit(id))
    account.underlyingActor.state should be(90)
    account.underlyingActor.tentativeState should be(65)

    getId[CommitAccepted](account ? Commit(id2))
    account.underlyingActor.state should be(65)
    account.underlyingActor.tentativeState should be(65)
  }

  it should "illustrate the consistency problem where a deposit gets undone" in {
    val T1Id = getId[PrepareAccepted](account ? Prepare(Deposit(10)))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(110)

    val T2Id = getId[PrepareAccepted](account ? Prepare(Withdraw(110)))
    account.underlyingActor.state should be(100)
    account.underlyingActor.tentativeState should be(0)

    getId[RemovePrepareDone](account ? RemovePrepared(T1Id))
    // BOOM
    account.underlyingActor.state should be(100)
    // this should not happen, but what should happen?
    account.underlyingActor.tentativeState should be(-10)

    getId[CommitAccepted](account ? Commit(T2Id))
    // this should not happen
    account.underlyingActor.state should be(-10)
    account.underlyingActor.tentativeState should be(-10)
  }
}