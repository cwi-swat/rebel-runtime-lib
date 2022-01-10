package com.ing.rebel.actors

import akka.actor.ActorLogging
import com.ing.rebel.specification.Specification
import com.ing.rebel.util.Passivation
import com.ing.rebel.{RebelData, RebelState, SpecificationEvent}

/**
  * Abstract trait to generate lower level specifications such as external specifications
  *
  */
trait RebelActor[S <: Specification]
  extends ActorLogging
    with Passivation // not necessary anymore?