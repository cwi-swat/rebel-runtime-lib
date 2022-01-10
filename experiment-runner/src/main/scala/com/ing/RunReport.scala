package com.ing

import ammonite.ops.ImplicitWd._
import ammonite.ops.{%%, FilePath, Path, pwd, _}
import com.ing.RebelExperimentPaths.rootDir
import com.ing.RunReport.logger
import com.typesafe.scalalogging.{Logger, StrictLogging}

import scala.util.Try

/**
  * aws/runMain com.ing.RunReport
  */
object RunReport extends StrictLogging {
  implicit val implicitLogger: Logger = this.logger

  def main(args: Array[String]): Unit = {

    val opts: Map[String, String] = Start.argsToOpts(args)

    val (reportType, htmlName) = opts.getOrElse("r", "variants") match {
      case "variants"      => (Reports.compareBatchesReportFile, "CompareVariants")
      case "variants-ramp" => (Reports.compareBatchesRampReportFile, "CompareVariantsRamp")
      case "batch"         => (Reports.batchReportFile, "BatchReport")
      case "experiment"    => (Reports.experimentReportFile, "ExperimentReport")
    }

    if (opts.isEmpty) {
      logger.info("'r=' for report selection; 'd=' for directory of results; 'all=true' run r= for all directories in d=")
      sys.exit()
    }
    val path = opts("d")
    val resultsDir = Path(path)

    if (opts.get("all").isDefined) {
      val experiments: Seq[Path] = (ls ! resultsDir) |? (_.isDir)
      logger.info(s"Found $experiments")
      experiments.foreach { p =>
        Reports.generateRReport(reportType, p, htmlName).recover { case e => logger.error("Error during report", e) }
        logger.info(s"Generated at $p")
      }
    } else {
      val outputPath = Reports.generateRReport(reportType, resultsDir, htmlName).recover { case e => logger.error("Error during report", e) }
      logger.info(s"Generated $htmlName at $outputPath")
    }

  }

}

object RebelExperimentPaths {
  val rootDir: Path = pwd / up
  val resultsDir: Path = rootDir / "results"
  val grafanaDbFilePath: FilePath = rootDir / "grafana" / "grafana.db"
}

object Reports {
  val experimentReportFile: FilePath = rootDir / "aws" / "reports" / "ExperimentReport.Rmd"
  val batchReportFile: Path = rootDir / "aws" / "reports" / "USL.Rmd"
  val compareBatchesReportFile: Path = rootDir / "aws" / "reports" / "CompareBatches.Rmd"
  val compareBatchesRampReportFile: Path = rootDir / "aws" / "reports" / "CompareBatches-ramp.Rmd"

  def generateRReport(rFile: FilePath, experimentDir: Path, outputHtmlName: String)(implicit logger: Logger): Try[Path] =
    Try {
      logger.info(s"Generating $outputHtmlName report")
      val rResultPath = experimentDir / s"$outputHtmlName.html"
      val result = %%("R", "-e",
        s"""rmarkdown::render('$rFile', output_file='$rResultPath')""",
        "--args", "-f", s"$experimentDir")
      logger.debug(result.toString())
      if (result.exitCode != 0) {
        logger.error(result.toString())
      }
      logger.info(s"Generated {} report on {}", outputHtmlName, rResultPath)
      rResultPath
    }
}