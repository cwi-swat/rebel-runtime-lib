import java.io.File

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import purecsv.safe._
import purecsv.safe.converter.StringConverter
import ammonite.ops.ImplicitWd._
import ammonite.ops._

import scala.util.{Success, Try}

implicit val dateTimeStringConverter = new StringConverter[DateTime] {
  override def tryFrom(str: String): Try[DateTime] = {
    Try(new DateTime(str.toLong / 1000000))
  }

  override def to(dateTime: DateTime): String = {
    dateTime.getMillis.toString
  }
}

case class Gatling(
  name: String,
  time: DateTime,
  count: Long,
  max: Long,
  mean: Long,
  min: Long,
  percentiles50: Long,
  percentiles75: Long,
  percentiles95: Long,
  percentiles99: Long,
  request: String,
  simulation: String,
  status: String,
  stdDev: Long)

//val path: String = "/Users/tim/workspace/account-modelling/ing-codegen-akka/results/2017-06-26T16:35:06.587+02:00-com.ing.corebank.rebel.simple_transaction.simple.OpenAccountSimulation-m4.large-n6-c1-batch/metrics/gatling.csv"

val wd: Path = Path("/Users/tim/workspace/account-modelling/ing-codegen-akka/results/")

val paths: Seq[Path] =
  (ls ! wd |? (_.last contains "Batch"))
      .flatMap(ls.rec! _ |? (_.last == "gatling.csv") )

// TODO make paths return the gatling.csv files


def getResults(path: File) = {
  val gatling = CSVReader[Gatling].readCSVFromFile(path, skipHeader = true)

  val metrics: List[Gatling] = gatling.collect { case Success(g) => g }
//  metrics.foreach(println)

  val filtered = metrics
  //  .filter(g => g.count > 450 && g.count < 550)

  filtered.size

  //filtered.foreach(println)


  val timeAndCounts: Map[DateTime, (Long, Long, Long)] = filtered
    .groupBy(_.time)
    .mapValues(gs =>
      (
        gs.find(_.request == "allRequests").map(_.count).getOrElse(0),
        gs.find(g => g.request == "openAccount" && g.status == "ok").map(_.count).getOrElse(0),
        gs.find(g => g.request == "openAccount" && g.status == "ko").map(_.count).getOrElse(0)
      ))


  //timeAndCounts.foreach(println)
  timeAndCounts
}

val aggregated = paths.map(p => getResults(p.toIO))


val tuples: Seq[(DateTime, Long, Long, Long)] = aggregated.flatMap(_.toSeq).map(t => (t._1, t._2._1, t._2._2, t._2._3))
tuples.writeCSVToFileName("/tmp/output.csv")