package com.ing.corebank.rebel.simple_transaction.simple

import com.ing.corebank.rebel.simple_transaction.Distributions.CategorizeTransaction
import com.ing.corebank.rebel.simple_transaction.{Account, Distributions, OpenAccountsBefore, SimulationBase}
import com.ing.rebel.{Iban, _}
import io.circe.{Encoder, Json}
import io.gatling.core.Predef.{StringBody, feed, _}
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef.http
import org.apache.commons.math3.distribution.AbstractIntegerDistribution
import squants.market.EUR

import scala.collection.immutable.SortedMap
import scala.util.Random

class DepositSimulation extends SimulationBase with OpenAccountsBefore {
  val (dist, categorizeTransaction): (AbstractIntegerDistribution, CategorizeTransaction) = Distributions.getDistribution(scenarioConfig)

  val deposit: Json = Encoder[Account.Event].apply(Account.Deposit(EUR(1)))
  def feeder: Iterator[Map[String, Any]] = Iterator.continually {
    val id = Math.abs(Random.nextInt())
    val account = generateAccountNumber()
    //    val toTry = generateAccountNumber()
    //    val to = if (toTry == account) (toTry + 1) % numberOfAccounts else toTry
    //    val label: String = categorizeTransaction(account, to)

    beforeSystem.log.debug("id {}, account {}, deposit {}", id, account,deposit)

    Map("id" -> id, "account" -> accountName(account), "deposit" -> deposit.noSpaces)
  }

  override lazy val chain: ChainBuilder = feed(feeder).exec(
    http("Deposit")
      .post(session => s"/Account/${session("account").as[String]}/Deposit")
      .body(StringBody(session => session("deposit").as[String]))
      .asJson)

  override lazy val scenarioName: String = "AllToAll"

  val generatedAccountNumbers: collection.mutable.Map[Int, Int] = collection.mutable.TreeMap.empty[Int, Int].withDefaultValue(0)

  def generateAccountNumber(): Int = {
    val nr = dist.sample()
    assert(nr > 0, s"Distribution $dist generated number below 1: $nr")
    assert(nr <= numberOfAccounts, s"Distribution $dist generated number higher than number of accounts ($numberOfAccounts): $nr")
    generatedAccountNumbers(nr) += 1
    nr
  }

  after {
    beforeSystem.log.info("Finished AllToAllSimulation with distribution:")
    beforeSystem.log.info(Distributions.prettyPrintDistribution(numberOfAccounts, SortedMap.empty ++ generatedAccountNumbers))
  }

  start()
}