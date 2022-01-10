package com.ing.rebel.util

import java.security.{SecureRandom, Security}
import java.util.UUID

import org.scalatest.{FlatSpec, Matchers}

class XoShiRo256StarStarRandomProviderTest extends FlatSpec with Matchers {

  "SecureRandom" should "use XoShiRo256StarStarRandom" in {
    Security.insertProviderAt(XoShiRo256StarStarRandomProvider, 1)

    new SecureRandom().getAlgorithm shouldBe "XoShiRo256StarStarRandom"
  }

  it should "generate numbers" in {
    Security.insertProviderAt(XoShiRo256StarStarRandomProvider, 1)

    new SecureRandom().getAlgorithm shouldBe "XoShiRo256StarStarRandom"
    new SecureRandom().nextInt()
  }

  it should "generate UUIDs" in {
    Security.insertProviderAt(XoShiRo256StarStarRandomProvider, 1)

    new SecureRandom().getAlgorithm shouldBe "XoShiRo256StarStarRandom"
    UUID.randomUUID() shouldNot be (UUID.fromString("00000000-0000-0000-0000-000000000000"))
  }
}
