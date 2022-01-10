package com.ing.rebel.consistency

import akka.NotUsed
import akka.actor.Props
import akka.persistence.PersistentActor
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
import akka.persistence.query.{EventEnvelope, PersistenceQuery, Sequence}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.ing.corebank.rebel.simple_transaction.{Account, AccountLogic}
import com.ing.corebank.rebel.simple_transaction.Account._
import com.ing.corebank.rebel.simple_transaction.actor.AccountActor
import com.ing.rebel.RebelError.PreConditionFailed
import com.ing.rebel._
import com.ing.rebel.consistency.ConsistencyCheck.{ConsistencyResult, InternalState}
import com.ing.rebel.messages.{EntityCommandSuccess, ProcessEvent}
import com.ing.rebel.specification.RebelSpecification
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.util.{CassandraClusterTestKit, CassandraLifecycle, RebelMatchers}
import org.joda.time.DateTime
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.LoneElement
import org.scalatest.matchers.should.Matchers
import squants.market.EUR


class ConsistencyCheckSpec extends CassandraClusterTestKit
  with CassandraLifecycle with Matchers with ScalaFutures with LoneElement with RebelMatchers {
  override def systemName: String = "EventLoggerSpec"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))

  def ConsistencyCheckFromList(seqOfEvents: List[EventEnvelope]) = new ConsistencyCheck[Account.type] {
    override val readJournal: CurrentEventsByPersistenceIdQuery = new CurrentEventsByPersistenceIdQuery {
      override def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] =
        Source(seqOfEvents)
    }
    override implicit val mat: ActorMaterializer = ActorMaterializer()

    override val specificationLogic: RebelSpecification[Account.type] = new AccountLogic {}
  }

  def ConsistencyCheckFromCassandra() = new ConsistencyCheck[Account.type] {
    override val readJournal: CassandraReadJournal =
      PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    override implicit val mat: ActorMaterializer = ActorMaterializer()
    override val specificationLogic: RebelSpecification[Account.type] = Account.Logic
  }

  "ConsistencyCheck" should "work with custom Source" in {
    val consistencyCheck = ConsistencyCheckFromList(List(
      EventEnvelope(Sequence(1), "MyAccount", 1, RebelDomainEvent(Account.OpenAccount(EUR(100)), TransactionId("1"))),
      EventEnvelope(Sequence(2), "MyAccount", 2, RebelDomainEvent(Account.Withdraw(EUR(50)), TransactionId("2"))),
      EventEnvelope(Sequence(3), "MyAccount", 3, RebelDomainEvent(Account.Withdraw(EUR(50)), TransactionId("3")))
    ))

    consistencyCheck.checkAccountConsistency("MyAccount").futureValue should
      be(ConsistencyResult("MyAccount", Seq(), InternalState(Account.Opened, Initialised(Account.Data(Some(EUR(0)))))))
  }

  it should "detect inconsistency with custom Source" in {
    val consistencyCheck = ConsistencyCheckFromList(List(
      EventEnvelope(Sequence(1), "MyAccount", 1, RebelDomainEvent(Account.OpenAccount(EUR(100)), TransactionId("1"))),
      EventEnvelope(Sequence(2), "MyAccount", 2, RebelDomainEvent(Account.Withdraw(EUR(50)), TransactionId("2"))),
      EventEnvelope(Sequence(3), "MyAccount", 3, RebelDomainEvent(Account.Withdraw(EUR(80)), TransactionId("3")))
    ))

    val result = consistencyCheck.checkAccountConsistency("MyAccount").futureValue

    result.internalState shouldBe InternalState(Account.Opened, Initialised(Account.Data(Some(EUR(-30)))))
    val error = result.errors.loneElement
    error.command should be(Withdraw(EUR(80)))
    error.messages.loneElement should be(PreConditionFailed("this.balance - amount >= EUR 0.00"))
  }

  it should "work with cassandra" in {
    val accountActor = system.actorOf(AccountActor.props, "MyAccount")

    val open = RebelDomainEvent(OpenAccount(EUR(100)))
    accountActor ! ProcessEvent(open)
    val withdraw1 = RebelDomainEvent(Account.Withdraw(EUR(50)))
    accountActor ! ProcessEvent(withdraw1)
    val withdraw2 = RebelDomainEvent(Account.Withdraw(EUR(50)))
    accountActor ! ProcessEvent(withdraw2)
    expectCommandSuccess(open)
    expectCommandSuccess(withdraw1)
    expectCommandSuccess(withdraw2)

    val cassandra = ConsistencyCheckFromCassandra()

    import cassandra._
    cassandra.readJournal.persistenceIds().runForeach(println(_))

    cassandra.checkAccountConsistency(accountActor.path.toStringWithoutAddress).futureValue should
      be(ConsistencyResult(accountActor.path.toStringWithoutAddress, Seq(), InternalState(Account.Opened, Initialised(Account.Data(Some(EUR(0)))))))

  }

  it should "work with cassandra and detect inconsistencies" in {
    // Write events directly to Cassandra using custom PersistentActor to circumvent the pre/postconditions check in AccountActor
    val accountActor = system.actorOf(Props(new PersistentActor {
      override def receiveRecover: Receive = {
        case _ =>
      }

      override def receiveCommand: Receive = {
        case _ =>
          persistAll(List(
            RebelDomainEvent(Account.OpenAccount(EUR(100)),TransactionId("1")),
            RebelDomainEvent(Account.Withdraw(EUR(50)),TransactionId("2")),
            RebelDomainEvent(Account.Withdraw(EUR(80)),TransactionId("3")))
          )(_ => sender() ! "LO")
      }

      override def persistenceId = "MyPersistenceId"
    }))

    accountActor ! "YO"
    expectMsg("LO")

    val result = ConsistencyCheckFromCassandra().checkAccountConsistency("MyPersistenceId").futureValue
    result.internalState shouldBe InternalState(Account.Opened, Initialised(Account.Data(Some(EUR(-30)))))
    val error = result.errors.loneElement
    error.command should be(Withdraw(EUR(80)))
    error.messages.loneElement should be(PreConditionFailed("this.balance - amount >= EUR 0.00"))
  }
}
