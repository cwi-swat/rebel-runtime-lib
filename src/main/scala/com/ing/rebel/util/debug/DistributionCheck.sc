import akka.cluster.sharding.ShardRegion.ShardId
import com.ing.rebel.{Iban, RebelSharding}
import com.ing.rebel.RebelSharding.ShardingEnvelope
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId

import scala.collection.immutable.SortedMap
import scala.util.Random

def createIndicationString(n: Int, maxSize: Int): String = "#" * (n * 60 / maxSize)

def prettyPrintDistribution(generatedAccountNumbers: SortedMap[String, Int]): String = {
  val groupSize = 10 //numberOfAccounts / 50 // 50 groups
  val labelSize = 6 // numberOfAccounts.toString.length
  val labelsAndCounts: List[(String, Int)] = generatedAccountNumbers
    .grouped(groupSize)
    .map(groupedMap =>
      (s"${groupedMap.keys.head.toString.padTo(labelSize, ' ')}- ${groupedMap.keys.last.toString.padTo(labelSize, ' ')}",
        groupedMap.values.sum))
    .toList
  val maxSize = labelsAndCounts.map(_._2).max

  labelsAndCounts.map { case (l, c) =>
    s"$l ${c.toString.padTo(labelSize, ' ')}${createIndicationString(c, maxSize)}"
  }.mkString("\n")
}

def createLoad(): List[String] = {
  val prefix: String = new Random().alphanumeric.take(5).mkString
  println(prefix)
  val gen: Iterator[Iban] = Iterator.from(1).map(i => Iban(s"$prefix-NL$i"))

  gen.take(100000).map(i => RebelSharding.defaultShardResolver(RebelSharding.shardCount)(ShardingEnvelope(i, null))).toList
}
val threeGenerators = createLoad() ++ createLoad() ++ createLoad()
println(threeGenerators.size)

val sorted = SortedMap.empty[String, Int] ++ threeGenerators.groupBy(identity).mapValues(_.size)

prettyPrintDistribution(sorted)


// seems to be ok...
// now for 2pc
def createLoad2PC(): List[String] = {
  val prefix: String = new Random().alphanumeric.take(5).mkString
  println(prefix)
  val gen = Iterator.from(1).map(i => TransactionId(UniqueId("t")))

  gen.take(100000).map(i => RebelSharding.defaultShardResolver(RebelSharding.shardCount)(ShardingEnvelope(i, null))).toList
}

val twoPCIds = createLoad2PC() ++ createLoad2PC() ++ createLoad2PC()

val sorted2PC = SortedMap.empty[String, Int] ++ threeGenerators.groupBy(identity).mapValues(_.size)

prettyPrintDistribution(sorted2PC)

// also nicely devided...