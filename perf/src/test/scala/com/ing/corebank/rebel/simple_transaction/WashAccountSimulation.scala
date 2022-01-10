package com.ing.corebank.rebel.simple_transaction

import com.ing.rebel._
import io.circe.Encoder
import io.gatling.core.Predef._
import io.gatling.core.structure.{ChainBuilder, ScenarioBuilder}
import io.gatling.http.Predef._
import squants.market.EUR

import scala.util.Random

class WashAccountSimulation extends SimulationBase with OpenAccountsBefore  {

  val feeder: Iterator[Map[String, Any]] = Iterator.continually {
    val id = Math.abs(Random.nextInt())
    val to = Random.nextInt(numberOfAccounts) + 1
    val book = Encoder[Transaction.Event].apply(Transaction.Book(EUR(1), Iban(s"NL1", "NL"), Iban(s"NL${to.toString}", "NL")))
    Map("id" -> id, "from" -> 1, "to" -> to, "book" -> book.noSpaces)
  }

  override val chain: ChainBuilder =
    feed(feeder)
      .exec(http("book").post(session => s"/Transaction/${session("id").as[String]}/Book").body(StringBody(session => session("book").as[String])).asJson)


  override val scenarioName: String = "WashAccount"

  start()
}