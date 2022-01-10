package com.ing.rebel

import com.google.common.base.Throwables

sealed trait RebelError
object RebelError {

//   TODO: rebelStateLabel could be more strongly typed?
  final case class CommandNotAllowedInState(rebelStateLabel: RebelState, rebelCommand: SpecificationEvent) extends RebelError {
    override def toString: String = {
      s"Command $rebelCommand not allowed when in state $rebelStateLabel"
    }
  }
  final case class PreConditionFailed(e: String) extends RebelError
  final case class PostConditionFailed(e: String) extends RebelError
  final case class InvariantFailed(e: String) extends RebelError
  final case class SyncFailed(e: String) extends RebelError
  // No Exception because we do not want to serialise this
  final case class GenericRebelError(t: String) extends RebelError
  object GenericRebelError {
    def apply(t: Exception): GenericRebelError = new GenericRebelError(s"${t.toString} ${Throwables.getStackTraceAsString(t)}")
  }
  final case class WrappedError(origin: String, error: RebelError) extends RebelError
  final case object StaticDependentError extends RebelError
}