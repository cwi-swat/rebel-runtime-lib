package com.ing.util

import akka.testkit.TestKitBase
import com.ing.rebel.{RebelDomainEvent, SpecificationEvent}
import com.ing.rebel.messages.EntityCommandSuccess
import com.ing.rebel.specification.Specification
import org.scalactic.TripleEquals._

import scala.concurrent.duration.Duration

trait RebelMatchers {
  self: TestKitBase =>
  def expectCommandSuccess(event: RebelDomainEvent[_]): EntityCommandSuccess[_] = {
    expectCommandSuccess(Duration.Undefined, event)
  }

  def expectCommandSuccess(max: Duration, event: RebelDomainEvent[_]): EntityCommandSuccess[_] = {
    expectMsgPF(max, s"Expected EntityCommandSuccess with $event") {
      case m: EntityCommandSuccess[_] if m.event === event => m
    }
  }
}
