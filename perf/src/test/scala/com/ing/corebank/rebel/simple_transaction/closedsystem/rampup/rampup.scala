package com.ing.corebank.rebel.simple_transaction.closedsystem.rampup

import com.github.nscala_time.time.Imports._
import com.ing.corebank.rebel.simple_transaction.SimulationBase
import io.gatling.core.Predef.{scenario, _}
import io.gatling.core.structure.ScenarioBuilder

import scala.concurrent.duration.FiniteDuration

trait ClosedRampUpBase extends SimulationBase {

  override lazy val scn: ScenarioBuilder = scenario(scenarioName).exec(chain)

  import scenarioConfig._

  lazy val levelLasting: FiniteDuration = (maxDuration / numberOfSteps).toCoarsest
  lazy val rampLasting: FiniteDuration = (warmupDuration / numberOfSteps).toCoarsest
  lazy val incrementBy: Double = (users.toDouble - warmupUsers) / numberOfSteps

  // TODO fix
  lazy val expectedRampLevels: Seq[(Int, Double)] = 0.to(numberOfSteps).map(n => (n, warmupUsers + incrementBy * n))
  lazy val expectedRampLevelsTimers: Seq[(Int, DateTime)] = 0.to(numberOfSteps).map{ n =>
    val timePerLevel: FiniteDuration = rampLasting + levelLasting
    val timePassed: FiniteDuration = timePerLevel * n
    import com.github.nscala_time.time.Implicits._
    (n, DateTime.now().withDurationAdded(timePassed.toJodaDuration, 1))
  }

  override def start(): SetUp = {
    beforeSystem.log.info(
      s"""  Setting up scenario $scenarioName (${this.getClass.getName})
         |  setUp(
         |      scn.inject(
         |        incrementUsersPerSec($incrementBy)
         |          .times($numberOfSteps)
         |          .eachLevelLasting($levelLasting)
         |          .separatedByRampsLasting($rampLasting)
         |          .startingFrom($warmupUsers)
         |      )
         |    ).throttle(
         |      jumpToRps($users), // throttle to reduce load/log spam for too high load
         |      holdFor($totalDuration)
         |    ).maxDuration($totalDuration)
         |    Expected Ramp users/second levels $expectedRampLevels
         |    Expected Ramp start timings $expectedRampLevelsTimers
      """.stripMargin)
    setUp(
      scn.inject(
        incrementUsersPerSec(incrementBy)
          .times(numberOfSteps)
          .eachLevelLasting(levelLasting)
          .separatedByRampsLasting(rampLasting)
          .startingFrom(warmupUsers)
      )
    ).throttle(
      jumpToRps(users), // throttle to reduce load/log spam for too high load
      holdFor(totalDuration)
    ).maxDuration(totalDuration)
      .protocols(httpConf).assertions(serviceLevelObjective)
  }
}

class DummySimulation extends com.ing.corebank.rebel.simple_transaction.simple.DummySimulation with ClosedRampUpBase
class SimpleSimulation extends com.ing.corebank.rebel.simple_transaction.simple.SimpleSimulation with ClosedRampUpBase
class SimpleShardingSimulation extends com.ing.corebank.rebel.simple_transaction.simple.SimpleShardingSimulation with ClosedRampUpBase
class SimpleWithPersistenceSimulation extends com.ing.corebank.rebel.simple_transaction.simple.SimpleWithPersistenceSimulation with ClosedRampUpBase
class OpenAccountSimulation extends com.ing.corebank.rebel.simple_transaction.simple.OpenAccountSimulation with ClosedRampUpBase
class DepositSimulation extends com.ing.corebank.rebel.simple_transaction.simple.DepositSimulation with ClosedRampUpBase
class AllToAllSimulation extends com.ing.corebank.rebel.simple_transaction.AllToAllSimulation with ClosedRampUpBase