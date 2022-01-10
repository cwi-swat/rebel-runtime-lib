package com.ing.rebel.experiment.report


import ammonite.ops
import com.typesafe.scalalogging.LazyLogging
//import io.gatling.app.{RunResult, RunResultProcessor}
//import io.gatling.core.config.GatlingConfiguration

import scala.collection.{immutable, mutable}
import reflect.io._, Path._



object RunCombinedReport extends LazyLogging {

  def main(args: Array[String]): Unit = {
//    val runPath = "/Users/tim/workspace/account-modelling/ing-codegen-akka/results/2017-09-19T16:30:29.022+02:00-InMemVsCassandraVsNoOp/NoOp/31ce99c0-35c7-4913-9e2a-bdd8c1637357-com.ing.corebank.rebel.simple_transaction.simple.OpenAccountSimulation-u200-m4.large-n3-c0-p3"
    val runPath: String = if(args.nonEmpty) args(0) else throw new RuntimeException("First argument should be experiment path")

    createCombinedReport(runPath)
  }

  def createCombinedReport(experimentPath: String): Unit = {
//    val logFiles: immutable.Seq[Path] = experimentPath.walkFilter(p => p.isDirectory || p.name == "simulation.log").toList
//
//    logFiles.foreach(f => logger.info(f.toString))
//
//    val logDir: ops.Path = ops.Path(experimentPath) / "simulationLogs"
//    ops.mkdir ! logDir
//
//    logFiles.zipWithIndex.foreach { case (path, index) => ops.ln(ops.Path(path.toString), logDir / s"$index.log") }

    // configuration.core.directory.results
//    val gatlingConfiguration = GatlingConfiguration.load(mutable.Map("gatling.core.directory.results" -> logDir.toString()))

    // TODO use this to trigger report generation.
//    new RunResultProcessor(gatlingConfiguration).processRunResult(RunResult(logDir.toString(), hasAssertions = true))

    logger.info("NOTHING DONE, removed gatling dependency")
//    logger.info("DONE")
  }
}