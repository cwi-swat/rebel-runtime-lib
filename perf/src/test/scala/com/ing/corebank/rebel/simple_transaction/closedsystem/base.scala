package com.ing.corebank.rebel.simple_transaction.closedsystem

import com.ing.corebank.rebel.simple_transaction.SimulationBase
import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder

/**
  * Use gatling 3 new concurrent users feature to make sure not too many users are created
  */
trait ClosedBase extends SimulationBase {

  override lazy val scn: ScenarioBuilder = scenario(scenarioName).exec(chain)

  override def start(): SetUp = {
    import scenarioConfig._
    beforeSystem.log.info(
      s"""  Setting up scenario $scenarioName (${this.getClass.getName})
         |  setUp(
         |      warmUp.inject(constantUsersPerSec(10) during $warmupDuration),
         |      scn.inject(
         |        constantConcurrentUsers(0).during($warmupDuration),
         |        rampConcurrentUsers(0).to($users).during($rampUpTime),
         |        constantConcurrentUsers($users).during($maxDuration)
         |      )
         |    ).throttle(
         |      // make sure not too many requests
         |      jumpToRps($rpsThrottlePerNode),
         |      holdFor($totalDuration))
         |      .maxDuration($totalDuration)
      """.stripMargin)
    setUp(
      warmUp.inject(constantUsersPerSec(warmupUsers) during warmupDuration),
      scn.inject(
        // wait on warmup
        constantConcurrentUsers(0).during(warmupDuration),
        // rampup
        rampConcurrentUsers(0).to(users).during(rampUpTime),
        // actual load
        constantConcurrentUsers(users).during(maxDuration)
      )
    ).throttle(
      // make sure not too many requests
      jumpToRps(rpsThrottlePerNode),
      holdFor(totalDuration))
      .maxDuration(totalDuration)
      .protocols(httpConf).assertions(serviceLevelObjective)
  }
}


class DummySimulation extends com.ing.corebank.rebel.simple_transaction.simple.DummySimulation with ClosedBase
class SimpleSimulation extends com.ing.corebank.rebel.simple_transaction.simple.SimpleSimulation with ClosedBase
class SimpleShardingSimulation extends com.ing.corebank.rebel.simple_transaction.simple.SimpleShardingSimulation with ClosedBase
class SimpleWithPersistenceSimulation extends com.ing.corebank.rebel.simple_transaction.simple.SimpleWithPersistenceSimulation with ClosedBase
class OpenAccountSimulation extends com.ing.corebank.rebel.simple_transaction.simple.OpenAccountSimulation with ClosedBase
class AllToAllSimulation extends com.ing.corebank.rebel.simple_transaction.AllToAllSimulation with ClosedBase