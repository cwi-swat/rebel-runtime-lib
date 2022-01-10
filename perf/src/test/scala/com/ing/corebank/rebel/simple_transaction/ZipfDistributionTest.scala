package com.ing.corebank.rebel.simple_transaction

import org.apache.commons.math3.distribution.ZipfDistribution
import org.scalatest._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ZipfDistributionTest extends FlatSpec with ScalaCheckPropertyChecks {
  "ZipfDistribution" should "be exponential/zipf distribution" in {
    forAll { max: Int =>
      whenever(max > 0) {
        val n = new ZipfDistribution(max, 1).sample()
        assert(n > 0)
        assert(n <= max)
      }
    }
  }
}
