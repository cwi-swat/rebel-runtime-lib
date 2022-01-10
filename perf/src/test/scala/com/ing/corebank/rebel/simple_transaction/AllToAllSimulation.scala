package com.ing.corebank.rebel.simple_transaction

import com.ing.corebank.rebel.simple_transaction.Distributions.CategorizeTransaction
import com.ing.corebank.rebel.simple_transaction.SimulationBase._
import com.ing.rebel.{Iban, _}
import io.circe.Encoder
import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import org.apache.commons.math3.distribution._
import squants.market.EUR

import scala.collection.immutable
import scala.collection.immutable.{Range, SortedMap}
import scala.util.Random

class AllToAllSimulation extends SimulationBase with OpenAccountsBefore {
  val (dist, categorizeTransaction): (AbstractIntegerDistribution, CategorizeTransaction) = Distributions.getDistribution(scenarioConfig)

  def feeder: Iterator[Map[String, Any]] = Iterator.continually {
    val id = Math.abs(Random.nextInt())
    val from = generateAccountNumber()
    val toTry = generateAccountNumber()
    val to = if (toTry == from) (toTry + 1) % numberOfAccounts else toTry
    val label: String = categorizeTransaction(from, to)
    val book = Encoder[Transaction.Event].apply(Transaction.Book(EUR(1), Iban(s"NL${from.toString}"), Iban(s"NL${to.toString}")))
    Map("id" -> id, "from" -> from, "to" -> to, "book" -> book.noSpaces, "requestName" -> label)
  }

  override lazy val chain: ChainBuilder = feed(feeder).exec(
    http(_ ("requestName").as[String])
      .post(session => s"/Transaction/${session("id").as[String]}/Book")
      .body(StringBody(session => session("book").as[String]))
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

object Distributions {
  /**
    * Create string of max length 60 proportional to total number of accounts
    *
    * @param n length value
    * @return string indication of size
    */
  def createIndicationString(n: Int, maxSize: Int): String = "#" * (n * 60 / maxSize)

  def prettyPrintDistribution(numberOfAccounts: Int, generatedAccountNumbers: SortedMap[Int, Int]): String = {
    val groupSize = numberOfAccounts / 50 // 50 groups
    val labelSize = numberOfAccounts.toString.length
    val labelsAndCounts: List[(String, Int)] = generatedAccountNumbers
      .grouped(groupSize)
      .map(groupedMap =>
        (s"${groupedMap.keys.head.toString.padTo(labelSize, ' ')}- ${groupedMap.keys.last.toString.padTo(labelSize, ' ')}",
          groupedMap.values.sum))
      .toList
    val maxSize = labelsAndCounts.map(_._2).max

    labelsAndCounts.map { case (l, c) =>
      s"$l ${c.toString.padTo(labelSize, ' ')}${Distributions.createIndicationString(c, maxSize)}"
    }.mkString("\n")
  }

  type CategorizeTransaction = (Int, Int) => String

  def getDistribution(scenarioConfig: ScenarioConfig): (AbstractIntegerDistribution, CategorizeTransaction) = {
    scenarioConfig.distribution.getOrElse(throw new RuntimeException("distribution must be set in config for AllToAllSimulation.")) match {
      case Uniform =>
        (new UniformIntegerDistribution(1, scenarioConfig.numberOfAccounts), (_: Int, _: Int) => "uniform-book")
      case Zipf(zipfExponent) =>
        (new ZipfDistribution(scenarioConfig.numberOfAccounts, zipfExponent), (_: Int, _: Int) => "zipf-book")
      case Realistic(businessAccountPercentage, businessTransactionPercentage) =>
        val businessAccounts: Range.Inclusive = 1 to (scenarioConfig.numberOfAccounts * businessAccountPercentage).toInt
        val consumerAccounts: Range.Inclusive = (businessAccounts.last + 1) to scenarioConfig.numberOfAccounts

        val allAccounts: immutable.Seq[(Int, Double)] =
          businessAccounts.map((_, businessTransactionPercentage / businessAccountPercentage)) ++
            consumerAccounts.map((_, (1 - businessTransactionPercentage) / (1 - businessAccountPercentage)))

        val (data, probabilities) = allAccounts.unzip
        val categorize: (Int, Int) => String = {
          (a, b) =>
            val aLabel = if (businessAccounts.contains(a)) "b" else "c"
            val bLabel = if (businessAccounts.contains(b)) "b" else "c"
            s"${aLabel}2$bLabel"
        }
        (new EnumeratedIntegerDistribution(data.toArray, probabilities.toArray), categorize)
    }
  }
}