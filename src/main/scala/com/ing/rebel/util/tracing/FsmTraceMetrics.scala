package com.ing.rebel.util.tracing

import akka.persistence.fsm.PersistentFSM.FSMState
import akka.persistence.fsm.PersistentFSMBase
import kamon.Kamon
import kamon.trace.Span

/**
  * Add Kamon tracing of states of FSM
  */
trait FsmTraceMetrics[S <: FSMState, D, E] extends PersistentFSMBase[S, D, E] {
  val traceSegment: Span = Kamon.spanBuilder(s"2pc-${this.getClass.getSimpleName}").start()

  Kamon.counter(s"create-${this.getClass.getSimpleName}").withoutTags().increment()

  // Span for state as child of total lifecycle
  var stateSpan: Span = _

  override def preStart(): Unit = {
    stateSpan = Kamon.spanBuilder(stateName.identifier).asChildOf(traceSegment).start()
    super.preStart()
  }

  onTransition {
    case (from, to) =>
      traceSegment.mark(to.identifier)
      stateSpan.finish()
      stateSpan = Kamon.spanBuilder(to.identifier).asChildOf(traceSegment).start()
  }

  override def postStop(): Unit = {
    // TODO maybe finish with reason on error
    stateSpan.finish()
    traceSegment.finish()
    super.postStop()
  }
}
