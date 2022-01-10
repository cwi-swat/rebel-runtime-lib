package com.ing.corebank.rebel.simple_transaction

import com.ing.rebel._
import io.circe.Encoder
import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import squants.market.EUR

/**
  * All transactions go to different accounts, so no overlap
  */
class ParallelTransactionsSimulation extends SimulationBase with OpenAccountsBefore {

  override val numberOfAccounts: Int = 10000

  //  var transactionCounter = 0

  private val withNext: Iterator[(String, String)] = accountIds.sliding(2, 2).map { case Seq(from, to) => (from, to) }
  val endlessWithNext: Stream[(String, String)] = withNext.toStream #::: endlessWithNext
  val feeder: Iterator[Map[String, Any]] = endlessWithNext.zipWithIndex.map {
    // should be
    // (1,2) (3,4) etc
    case ((from, to), index) =>
      val id = index.toString
      val book = Encoder[Transaction.Event].apply(Transaction.Book(EUR(1), Iban(from), Iban(to)))
      //      println(start.noSpaces)
      Map("id" -> id, "from" -> from, "to" -> to, "book" -> book.noSpaces)
  }.toIterator

  override val chain: ChainBuilder =
    feed(feeder)
      .exec(http("book").post(session => s"/Transaction/${session("id").as[String]}/Book").body(StringBody(session => session("book").as[String])).asJson)
  override val scenarioName: String = "ParallelAllToAll"

  start()
}