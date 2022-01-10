import ammonite.ops
import ammonite.ops._
import com.typesafe.scalalogging.Logger
import io.gatling.app.{RunResult, RunResultProcessor}
import io.gatling.charts.stats.LogFileReader
import io.gatling.core.config.GatlingConfiguration
import org.slf4j.LoggerFactory

import scala.collection.{immutable, mutable}
import scala.reflect.io.Path
import reflect.io._
import Path._
import scala.util.Try

val logger: Logger = Logger(LoggerFactory.getLogger(getClass.getName))

val runPath = "/Users/tim/workspace/account-modelling/ing-codegen-akka/results/2017-09-19T16:30:29.022+02:00-InMemVsCassandraVsNoOp/NoOp/31ce99c0-35c7-4913-9e2a-bdd8c1637357-com.ing.corebank.rebel.simple_transaction.simple.OpenAccountSimulation-u200-m4.large-n3-c0-p3"

val logFiles: immutable.Seq[Path] = runPath.walkFilter(p => p.isDirectory || p.name == "simulation.log").toList

logFiles.foreach(f => logger.info(f.toString))

val logDir: ops.Path = ops.Path(runPath) / "simulationLogs"
mkdir ! logDir

logFiles.zipWithIndex.foreach { case (path, index) => Try(ln(ops.Path(path.toString()), logDir / s"$index.log")) }

// configuration.core.directory.results
val gatlingConfiguration = GatlingConfiguration.load(mutable.Map("gatling.core.directory.results" -> logDir.toString()))


val reader = new LogFileReader(logDir.toString())(gatlingConfiguration)

reader.inputFiles

reader.resultsHolder.responseTimePercentilesBuffers

reader.resultsHolder.responseTimePercentilesBuffers.mapValues(_.percentiles.mkString("\n, ")).mkString("\n")

logger.info("DONE")
