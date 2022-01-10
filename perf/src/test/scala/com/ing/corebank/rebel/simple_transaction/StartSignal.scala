package com.ing.corebank.rebel.simple_transaction

import java.util.concurrent.CountDownLatch

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.server.Directives._

object StartSignal {
  val defaultSignalPort = 8080

  def route(countDownLatch: CountDownLatch): Route = pathEndOrSingleSlash {
    post {
      complete {
        countDownLatch.countDown()
        HttpResponse(status = StatusCodes.NoContent)
      }
    }
  }
}
