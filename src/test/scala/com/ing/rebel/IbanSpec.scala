package com.ing.rebel

import org.scalatest.{FlatSpec, Matchers}

object Ibans {
  val testIbanString1: String = "NL1" //NL20INGB0001234567"
  val testIban1: Iban = Iban(testIbanString1)

  val testIbanString2: String = "NL2" //= "GB82WEST12345698765432"
  val testIban2: Iban = Iban(testIbanString2, countryCode = "GB")
}

class IbanSpec extends FlatSpec with Matchers {

  "Iban" should "be initialized with valid IBAN string" in {
    val iban = Iban("NL20INGB0001234567")

    iban.iban should be("NL20INGB0001234567")
    iban.countryCode should be("NL")
  }

  ignore should "throw when an invalid IBAN is created" in {
    intercept[IllegalArgumentException] {
      Iban("NL1")
    }
  }

}
