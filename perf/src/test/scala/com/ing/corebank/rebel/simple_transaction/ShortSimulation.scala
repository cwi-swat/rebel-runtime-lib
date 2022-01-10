package com.ing.corebank.rebel.simple_transaction

import com.ing.corebank.rebel.simple_transaction.simple.OpenAccountSimulation
import io.gatling.core.Predef.{atOnceUsers, global, _}

import scala.concurrent.duration._

class ShortSimulation extends OpenAccountSimulation {
  override def start(): SetUp = {
    setUp(
      scn.inject(
        atOnceUsers(100)
      )
    ).maxDuration(10.seconds)
      .protocols(httpConf).assertions(
      global.successfulRequests.percent.gte(95)
    )
  }
}
