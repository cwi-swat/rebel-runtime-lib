package com.ing.util

import monix.eval.Task
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._

/** Request limiter for APIs that have quotas per second, minute, hour, etc.
  *
  * {{{
  *   // Rate-limits to 100 requests per second
  *   val limiter = TaskLimiter(TimeUnit.SECONDS, limit = 100)
  *
  *   limiter.request(myTask)
  * }}}
  */
final class TaskLimiter(period: TimeUnit, limit: Int)(logger: Logger) {
  import monix.execution.atomic.Atomic
  import TaskLimiter.State

  private[this] val state =
    Atomic(State(0, period, 0, limit))

  def request[A](task: Task[A]): Task[A] =
    Task.deferAction { ec =>
      val now = ec.currentTimeMillis()
      logger.debug("Requested task on TaskLimiter")
      state.transformAndExtract(_.request(now)) match {
        case None =>
          logger.debug(s"Executing task on TaskLimiter")
          task
        case Some(delay) =>
          // Recursive call, retrying request after delay
          logger.debug(s"Delaying task on TaskLimiter with $delay")
          request(task).delayExecution(delay)
      }
    }
}

object TaskLimiter {
  /** Builder for [[TaskLimiter]]. */
  def apply(period: TimeUnit, limit: Int)(implicit logger: Logger): TaskLimiter =
    new TaskLimiter(period, limit)(logger)

  /** Timestamp specified in milliseconds since epoch,
    * as returned by `System.currentTimeMillis`
    */
  type Timestamp = Long

  /** Internal state of [[TaskLimiter]]. */
  final case class State(window: Long, period: TimeUnit, requested: Int, limit: Int) {
    private def periodMillis =
      TimeUnit.MILLISECONDS.convert(1, period)

    def request(now: Timestamp): (Option[FiniteDuration], State) = {
      val periodMillis = this.periodMillis
      val currentWindow = now / periodMillis

      if (currentWindow != window) {
        (None, copy(window = currentWindow, requested = 1))
      } else if (requested < limit) {
        (None, copy(requested = requested + 1))
      }
      else {
        val nextTS = (currentWindow + 1) * periodMillis
        val sleep = nextTS - now
        (Some(sleep.millis), this)
      }
    }
  }
}