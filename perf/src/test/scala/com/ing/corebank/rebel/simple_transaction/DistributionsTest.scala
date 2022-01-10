package com.ing.corebank.rebel.simple_transaction

import com.ing.corebank.rebel.simple_transaction.SimulationBase._
import com.typesafe.config.ConfigFactory
import org.scalatest._
import pureconfig.generic.auto._

class DistributionsTest extends FlatSpec with Matchers with EitherValues with OptionValues {

  "Uniform distribution" should "be parsed" in {
    val parsed = pureconfig.loadConfig[Distribution](ConfigFactory.parseString(
      """
        |distribution {
        |  type = uniform
        |}
      """.stripMargin), "distribution")
    parsed.right.value shouldBe a[Uniform.type]
  }

  "Uniform distribution" should "be parsed directly" in {
    val parsed = pureconfig.loadConfig[Distribution](ConfigFactory.parseString(
      """
        |distribution = uniform
      """.stripMargin), "distribution")
    parsed.right.value shouldBe a[Uniform.type]
  }

  "Zipf distribution" should "be parsed" in {
    val parsed = pureconfig.loadConfig[Distribution](ConfigFactory.parseString(
      """
        |distribution = {
        |  type = zipf
        |  exponent = 0.5
        |}
      """.stripMargin), "distribution")
    parsed.right.value shouldBe Zipf(0.5)
  }

  "Realistic distribution" should "be parsed" in {
    val parsed = pureconfig.loadConfig[Distribution](ConfigFactory.parseString(
      """
        |distribution = {
        |  type = realistic
        |  consumer-business-percentage = 0.2
        |  consumer-business-transaction-percentage = 0.8
        |}
      """.stripMargin), "distribution")
    parsed.right.value shouldBe Realistic(0.2, 0.8)
  }
}
