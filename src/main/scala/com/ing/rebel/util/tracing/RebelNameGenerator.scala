//package com.ing.rebel.util.tracing
////
////import akka.http.scaladsl.model.HttpRequest
////import kamon.akka.http.AkkaHttp.OperationNameGenerator
////
/////**
////  * Extracts specification name and command from path for better metrics
////  */
////class RebelNameGenerator extends OperationNameGenerator {
////  def generateTraceName(request: HttpRequest): String =
////    request.getUri().path().split('/').toSeq match {
////      case _ :+ name :+ id :+ command => s"$name-$command"
////      case _ => "default"
////  }
////  def generateRequestLevelApiSegmentName(request: HttpRequest): String = "request-level " + generateTraceName(request)
////  def generateHostLevelApiSegmentName(request: HttpRequest): String = "host-level " + generateTraceName(request)
////
////  override def serverOperationName(request: HttpRequest): String = generateTraceName(request)
////
////  override def clientOperationName(request: HttpRequest): String = generateTraceName(request)
////}