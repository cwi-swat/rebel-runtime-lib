import org.apache.commons.math3.distribution.ZipfDistribution

import scala.annotation.tailrec
import scala.util.Random

//def generalizedHarmonicNumber(n: Int, m: Int): Double =
//  if(m == 0) {
//    n
//  } else {
//    (1 to n).map(k => 1 / math.pow(k, m)).sum
//  }
//
//generalizedHarmonicNumber(2, 0)
//generalizedHarmonicNumber(1, 100)
//generalizedHarmonicNumber(2, 100)
//generalizedHarmonicNumber(3, 100)
//generalizedHarmonicNumber(4, 100)
//
//// s is curve factor
//def cdf(k: Int, s: Int, N: Int): Double = generalizedHarmonicNumber(k,s) / generalizedHarmonicNumber(N,s)
//
//1 to 10 map (cdf(_, 1, 10))
//
//cdf(9999, 1, 10000)

val N = 10000
val s = 0.4 //factor

val dist = new ZipfDistribution(N, s)

private def createIndicationString(n: Int): String = "#" * (n * 30 / N)

val generatedAccountNumbers: collection.mutable.Map[Int, Int] = collection.mutable.TreeMap.empty[Int, Int].withDefaultValue(0)

dist.sample(100000).toList foreach { nr =>
  generatedAccountNumbers(nr) += 1
}

//val generatedAccountNumbers = dist.sample(100).toList.sorted
//generatedAccountNumbers
//  .map(n => s"$n\t${createIndicationString(n)}").foreach(println)

val groupSize = 50 // 50 groups
val labelSize = N.toString.length
val labelsAndCounts: Iterator[(String, Int)] = generatedAccountNumbers
  .grouped(groupSize)
  .map(groupedMap => (s"${groupedMap.keys.head.toString}-${groupedMap.keys.last.toString}", groupedMap.values.sum))

labelsAndCounts.map { case (l, c) => s"$l $c${createIndicationString(c)}" }.mkString("\n")

//Iterator.continually( cdf(Random.nextDouble(),s,N) ).take(100).toList.sorted

//def sampleDistribution(max: Int): Double = {
//  val lambda = 1 / 40
//  val sample = math.log(1 - Random.nextDouble()) * (-lambda)
//  println(sample)
//  (sample * max)
//}
//
//sampleDistribution(100)