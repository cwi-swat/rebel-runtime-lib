import com.ing.corebank.rebel.simple_transaction.Distributions
import org.apache.commons.math3.distribution.{BinomialDistribution, HypergeometricDistribution, ZipfDistribution}

val N = 10000
val s = 1 //factor

val dist = new BinomialDistribution(N, .01)
//val dist = new HypergeometricDistribution(N, 100, 100)
val generatedAccountNumbers: collection.mutable.Map[Int, Int] = collection.mutable.TreeMap.empty[Int, Int].withDefaultValue(0)

dist.sample(10000).toList foreach { nr =>
  generatedAccountNumbers(nr) += 1
}

val groupSize = 25 // 50 groups
val labelSize = N.toString.length
val labelsAndCounts: List[(String, Int)] = generatedAccountNumbers
  .grouped(groupSize)
  .map(groupedMap => (s"${groupedMap.keys.head.toString.padTo(labelSize, ' ')}- ${groupedMap.keys.last.toString.padTo(labelSize, ' ')}", groupedMap.values.sum))
  .toList

val maxSize = labelsAndCounts.map(_._2).max
labelsAndCounts.map { case (l, c) =>
  s"$l ${c.toString.padTo(labelSize, ' ')}${Distributions.createIndicationString(c, maxSize)}"
}.mkString("\n")
