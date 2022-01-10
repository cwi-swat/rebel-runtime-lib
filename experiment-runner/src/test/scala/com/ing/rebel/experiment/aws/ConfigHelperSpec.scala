package com.ing.rebel.experiment.aws

import com.ing.BatchConfig.{AbsoluteSize, ClusterSize, RelativeSize, Variant}
import com.ing.rebel.experiment.TestRun
import com.ing.{Batch, BatchConfig}
import com.ing.rebel.experiment.aws.AwsLib.ConfigHelper
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, LoneElement}
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.auto._

class ConfigHelperSpec extends AnyFlatSpec with Matchers with LoneElement with EitherValues {

  "BatchConfig" should "be parsed correctly" in {
    val config = ConfigFactory.parseString(
      """
        |include "ContentionExponential.conf"
        |
        |batch {
        |  description = "RealisticLoad"
        |  default = {
        |    simulation-classes = ["com.ing.corebank.rebel.simple_transaction.AllToAllSimulation"]
        |  }
        |  n = {
        |    cluster-sizes = [3,6,9]
        |  }
        |  variants = [
        |    {
        |      description = "0"
        |      performance-configs = [{
        |        rebel.scenario.number-of-accounts = 10000
        |        rebel.scenario.distribution = {
        |          type = realistic
        |          consumer-business-percentage = 0.2
        |          consumer-business-transaction-percentage = 0.8
        |        }
        |      }]
        |    }
        |  ]
        |}
        |
      """.stripMargin)
    val batchConfig = BatchConfig.parseBatchConfig(config)
    val variants: Seq[BatchConfig.Variant] = BatchConfig.getVariants(batchConfig)

    // only single variant
    variants should have size 1
    val parameters: BatchConfig.Variant = variants.loneElement

    parameters.performanceConfigs should have size 1

    val tests: Seq[TestRun] = BatchConfig.getTestsFromConfig(batchConfig, variants)

    tests should have size 3
  }

  "db-cluster-size" should "parse correctly" in {
    import pureconfig._
    val conf: Either[ConfigReaderFailures, Variant] = pureconfig.loadConfig[Variant](ConfigFactory.parseString(
      """ cluster-sizes = [3,6,9]
        | description = "desc"
        | user-counts = [100]
        | performance-throttles = [100]
        | performance-configs = []
        | durations = [10s]
        | app-configs = []
        | simulation-classes = ["class"]
        | db-cluster-sizes = [ 1,'2x',3 ]
        | performance-node-sizes = [1]
      """.stripMargin))

    conf shouldBe 'Right
    val parsed = conf.right.value

    parsed.dbClusterSizes should contain allOf(AbsoluteSize(1), RelativeSize(2), AbsoluteSize(3))
  }



}
