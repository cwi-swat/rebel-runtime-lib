package com.ing.rebel.consistency

import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.ing.rebel.consistency.ConsistencyCheck.ConsistencyResult
import com.ing.rebel.specification.{RebelSpecification, Specification}
import com.ing.rebel.{RebelDomainEvent, RebelState, SpecificationEvent}

/**
  * Abstract class that provides consistency checker if implicit `RebelSpecification[State, Data, Event]` is available.
  */
abstract class ConsistencyChecker[S <: Specification]
(implicit override val specificationLogic: RebelSpecification[S])
  extends App with ConsistencyCheck[S] {

  val persistenceIdFilter: String

  implicit lazy val system = ActorSystem("rebel-consistency-check")

  override lazy val readJournal: CassandraReadJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](
      CassandraReadJournal.Identifier)

  // only existing persistent ids
  lazy val source: Source[String, NotUsed] = readJournal.currentPersistenceIds()

  // materialize stream, consuming events
  implicit lazy val mat = ActorMaterializer()

  import system.dispatcher

  private val consistency: Source[ConsistencyResult[S#State, S#Data], NotUsed] =
    source.filter(_.contains(persistenceIdFilter)).mapAsync(8) {
      id =>
        //      println(id)
        //      readJournal.eventsByPersistenceId(id, 0, Long.MaxValue).runForeach(event => println(s"$event")).map(_ => println(s"finished for $id"))

        // TODO filter by Tag
        //      if (id.contains("Account")) {
        println(s"Checking CONSISTENCY FOR $id")
        checkAccountConsistency(id)
    }
  consistency.map(cr =>
    if (cr.errors.isEmpty) {
      s"${cr.id} (${cr.internalState}) => CONSISTENT"
    } else {
      s"${cr.id} (${cr.internalState}) => INCONSISTENT: ${cr.errors}"
    }).runForeach(println(_))
    .flatMap {
      _ =>
        println("DONE")
        system.terminate()
    }

}
