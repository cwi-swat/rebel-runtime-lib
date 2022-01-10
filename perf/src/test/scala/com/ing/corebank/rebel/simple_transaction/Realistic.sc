import java.{lang, util}

import com.ing.corebank.rebel.simple_transaction.Distributions

import scala.collection.immutable.Range
import com.ing.rebel._
import org.apache.commons.math3.distribution.{EnumeratedDistribution, EnumeratedIntegerDistribution}

import scala.collection.JavaConverters._
import scala.collection.immutable

val numberOfAccounts = 10000

val businessAccountPercentage: Double = 0.2

val businessTransactionPercentage: Double = 0.8

val businessAccounts: Range.Inclusive = 1 to (numberOfAccounts * businessAccountPercentage).toInt
val consumerAccounts: Range.Inclusive = (businessAccounts.last + 1) to numberOfAccounts

val allAccounts: immutable.Seq[(Int, Double)] =
  businessAccounts.map((_, businessTransactionPercentage / businessAccountPercentage)) ++
    consumerAccounts.map((_, (1 - businessTransactionPercentage) / (1 - businessAccountPercentage)))

val (data, probabilities) = allAccounts.unzip
val dist = new EnumeratedIntegerDistribution(data.toArray, probabilities.toArray)


val generatedAccountNumbers: collection.mutable.Map[Int, Int] = collection.mutable.TreeMap.empty[Int, Int].withDefaultValue(0)

dist.sample(1000000).toList foreach {
  nr => generatedAccountNumbers(nr.asInstanceOf[Int]) += 1
}

val groupSize = numberOfAccounts / 50 // 50 groups
val labelSize = numberOfAccounts.toString.length
val labelsAndCounts: List[(String, Int)] = generatedAccountNumbers
  .grouped(groupSize)
  .map(groupedMap => (s"${groupedMap.keys.head.toString.padTo(labelSize, ' ')}- ${groupedMap.keys.last.toString.padTo(labelSize, ' ')}", groupedMap.values.sum))
  .toList

val maxSize = labelsAndCounts.map(_._2).max
labelsAndCounts.map { case (l, c) =>
  s"$l ${c.toString.padTo(labelSize, ' ')}${Distributions.createIndicationString(c, maxSize)}"
} foreach println
