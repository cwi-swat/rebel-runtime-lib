package com.ing.rebel.sync.twophasecommit

import akka.persistence.fsm.{PersistentFSM, PersistentFSMBase}
import akka.persistence.fsm.PersistentFSM.{FSMState, Failure, Reason}
import kamon.Kamon

import scala.concurrent.duration.{Duration, FiniteDuration}

trait ActAfterXTimeouts[S <: FSMState, D, E] {
  this: PersistentFSMBase[S, D, E] =>

  val maximumTimeoutDuration: Duration
  def retryDuration: FiniteDuration// = RebelConfig(context.system).rebelConfig.sync.twoPC.retryDuration

  /**
    * Describe what should happen in state to retry when timeout occurred
    *
    * @param state state
    * @param data  FSM data
    */
  def applyCommand(state: S, data: D): Unit


  /**
    * Action to take when max number of retries occurred
    *
    * @return
    */
  def stateAfterMaxRetries: PartialFunction[(S, D), State]

  /**
    * Called when actor should stop. Modular to make sure that Passivation can also be used when needed.
    * @param reason
    * @return
    */
  def stopNow(reason: Reason): State = stop(reason)

  def stopEvent(): State = {
    log.debug("Stopping")
    stopNow(Failure(s"Too many Timeouts occurred in $stateName, stopping participant/manager ${this.getClass.getSimpleName}"))
  }

  protected var timeoutCounter: Int = 0
  protected var nextTimeoutAt: FiniteDuration = retryDuration
  //noinspection ScalaStyle
  lazy val backOff: Map[Int, FiniteDuration] =
    Vector(1, 1, 2, 3, 5, 8, 13, 21, 34).zipWithIndex.map { case (fib, index) => index -> retryDuration * fib }.toMap.withDefaultValue(retryDuration * 34)
  log.debug("Using backOff map: {}", backOff)

  whenUnhandled {
    case Event(StateTimeout, data) if nextTimeoutAt >= maximumTimeoutDuration =>
      log.debug("Too many Timeouts occurred: {} >= {}", nextTimeoutAt, maximumTimeoutDuration)
      Kamon.counter("2pc.timeouts").withoutTags().increment()
      // After retries do something, or default stop
      stateAfterMaxRetries.applyOrElse((stateName, data), (_: (S, D)) => stopEvent())
    case Event(StateTimeout, data) =>
      // backOff(timeoutCounter)
      Kamon.counter("2pc.retries").withoutTags().increment()
      log.debug("Timeout {} ({}) occurred, re-applying command: {}", timeoutCounter, nextTimeoutAt, stateName)
      timeoutCounter += 1
      val newTimeoutDuration = backOff(timeoutCounter)
      nextTimeoutAt += newTimeoutDuration
      applyCommand(stateName, data)
      log.debug("Setting new timeout to {}", newTimeoutDuration)
      stay().forMax(newTimeoutDuration)
  }
}
