package com.ing.rebel.util

import java.io.{File, PrintWriter}

import akka.actor.{Actor, ActorLogging, ActorRef, DeadLetter, NoSerializationVerificationNeeded, Props}
import akka.contrib.MessageInterceptor
import akka.contrib.pattern.ReceivePipeline
import akka.contrib.pattern.ReceivePipeline.Inner
import akka.event.Logging.LogEvent
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, ResponseEntity}
import akka.http.scaladsl.server.Directives.{complete, get, parameters, path}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.ing.rebel.config.RebelConfig
import com.ing.rebel.util.EndpointMessageLogger.{DiagramHtml, GetDiagramHtml}
import com.ing.rebel.util.MessageLogger.{CommentEvent, DiagramEvent, MessageEvent}
import pureconfig.loadConfig

import scala.concurrent.ExecutionContext
//import org.joda.time.DateTime
import com.github.nscala_time.time
import com.github.nscala_time.time._
import com.github.nscala_time.time.Imports._

  import scala.util.matching.Regex

object MessageLogger {
  def props(fileName: String): Props = Props(new MessageLogger(fileName))

  sealed trait DiagramEvent
  case class MessageEvent(from: String, to: String, message: String) extends DiagramEvent
  case class CommentEvent(from: String, message: String) extends DiagramEvent
  object MessageEvent {
    def apply(from: ActorRef, to: ActorRef, message: String): MessageEvent =
      MessageEvent(from.path.toStringWithoutAddress, to.path.toStringWithoutAddress, message)
  }
  object CommentEvent {
    def apply(ref: ActorRef, message: String): CommentEvent =
      CommentEvent(ref.path.toStringWithoutAddress, message)
  }

  val partialGuidRegex = "[0-9a-f]{4}[0-9a-f]{2}[1-5][0-9a-f]{3}[89ab][0-9a-f]{3}[0-9a-f]{12}"
  def createSequenceDiagramLine(from: String, to: String, msg: String): String = {
    def clean(s: String) = s.replaceAll("[]:-]", "")

    def abbreviate(s: String) = s.replace("/system/sharding", "/s/s").replaceAll(partialGuidRegex,"").replaceAll("RebelDomainEvent", "RDE")

    val f = clean(from)
    val t = clean(to)
    val m = clean(msg)
    abbreviate(s"from('$f').lineTo('$t').withText('$m'),\n")
  }

  final val htmlBegin: String =
    """
      |<!DOCTYPE html>
      |<html>
      |  <head>
      |    <title>Rebel Debug Sequence Diagram</title>
      |    <meta charset="UTF-8" />
      |    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      |    <!-- Dependencies -->
      |    <script src="https://cdnjs.cloudflare.com/ajax/libs/underscore.js/1.7.0/underscore-min.js"></script>
      |    <script src="https://cdnjs.cloudflare.com/ajax/libs/raphael/2.1.2/raphael-min.js"></script>
      |    <script src="https://cdnjs.cloudflare.com/ajax/libs/js-sequence-diagrams/1.0.4/sequence-diagram-min.js"></script>
      |    <!-- Module -->
      |    <script src="https://welovecoding.github.io/diagram-dsl/dist/js/diagram-dsl.min.js"></script>
      |  </head>
      |  <body>
      |    <input type="text" id="filterText" onkeydown="if (event.keyCode == 13)
      |                        document.getElementById('filter').click()"/>
      |    <button id="filter" onclick="doFilter()">Filter</button>
      |    <div id="diagram"></div>
      |    <script>
      |
      |      with (window.Diagram.DSL) {
      |
      |        var diagram = new SequenceDiagram('A Day At The Rebel Lib');
      |
      |
      |        var sequences = [
    """.stripMargin
  final val htmlEnd: String =
    """
      |
      |        ];
      |
      |        diagram.sequences = sequences;
      |        diagram.renderTo(document.getElementById('diagram'));
      |
      |      }
      |
      |    </script>
      |    <script>
      |    function doFilter() {
      |      var filterText = document.getElementById('filterText').value;
      |      var regex = new RegExp(filterText);
      |      console.log("filtering on: " + filterText);
      |      diagram.sequences = sequences.filter(function(a) { return a.command.label.match(regex) != null; });
      |
      |      diagram.renderTo(document.getElementById('diagram'));
      |    }
      |    </script>
      |  </body>
      |</html>
      |
      """.stripMargin
}

class MessageLogger(fileName: String) extends Actor {

  context.system.log.info("Logging messages to {}", fileName)

  //  context.system.eventStream.subscribe(self, classOf[LogEvent])
  context.system.eventStream.subscribe(self, classOf[DiagramEvent])
  context.system.eventStream.subscribe(self, classOf[DeadLetter])

  val file = new PrintWriter(new File(fileName))
  val fileDSL = new PrintWriter(new File(fileName + "_dsl"))

  file.write(MessageLogger.htmlBegin)

  val loggingReceiveRegex: Regex = "received [un]?handled message (\\p{ASCII}+) from (\\p{ASCII}+)".r


  override def receive: Receive = {
    //    case Debug(logSource, logClass, message: String) =>
    //      message match {
    //        case loggingReceiveRegex(msgS, fromS) =>
    //
    //          //noinspection ScalaStyle
    //          val from = fromS.substring(38).replaceAll("[]:-]", "")
    //          //noinspection ScalaStyle
    //          val to = logSource.substring(32).replaceAll("[:-]", "")
    //          val m = msgS.replaceAll("[:-]", "")
    //          writeSequenceDiagram(from, to, m)
    //      }
    case CommentEvent(ref, message)         => writeSequenceDiagram(ref, ref, message) // TODO support the notes in box syntax
    case MessageEvent(from, to, message)    => writeSequenceDiagram(from, to, message)
    case DeadLetter(msg, sender, recipient) => self ! MessageEvent(sender, recipient, s"DeadLetter: ${msg.toString}")
    case _: LogEvent                        =>
  }

  private def writeSequenceDiagram(from: String, to: String, msg: String): Unit = {
    file.write(MessageLogger.createSequenceDiagramLine(from, to, msg))
//    fileDSL.write(abbreviate(s"$f->$t: $m)\n"))
  }

  override def postStop(): Unit = {
    file.write(MessageLogger.htmlEnd)
    file.close()
    fileDSL.close()
    super.postStop()
  }
}

object EndpointMessageLogger {
  def props: Props = Props(new EndpointMessageLogger)

  case class GetDiagramHtml(startTime: DateTime, endTime: DateTime, maxSize: Int)
  case class DiagramHtml(html: String)

  import akka.http.scaladsl._
  import akka.http.scaladsl.server.Directives._
  import akka.actor.ActorRef
  import akka.util.Timeout
  import akka.pattern.ask
  import akka.http.scaladsl.marshalling.ToResponseMarshaller
  import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
  import akka.http.scaladsl.model.StatusCodes.MovedPermanently
  import akka.http.scaladsl.coding.Deflate
  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.server.directives.ParameterDirectives.ParamMagnet
  import akka.pattern.ask

  implicit val dateTimeUnmarshaller: Unmarshaller[String, DateTime] = Unmarshaller.strict(DateTime.parse)

  def route(endpointMessageLoggerRef : ActorRef)
           (implicit timeout: Timeout, sender: ActorRef = ActorRef.noSender, executionContext: ExecutionContext): Route =
    path("diagram") {
      get {
        parameters(Symbol("startTime").as[DateTime], Symbol("endTime").as[DateTime], Symbol("maxSize")  ? Int.MaxValue) {
          (startTime, endTime, maxSize) =>
            complete {
              (endpointMessageLoggerRef ? GetDiagramHtml(startTime, endTime, maxSize))
                .mapTo[DiagramHtml]
                // ContentTypes.`text/html(UTF-8)`),
                .map(diagram => HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, diagram.html)))
            }
        }
      }
    }
}

class EndpointMessageLogger extends Actor with ActorLogging {
  context.system.eventStream.subscribe(self, classOf[DiagramEvent])
  context.system.eventStream.subscribe(self, classOf[DeadLetter])


  var receivedEvents : Seq[(DateTime, String)] = Vector()

  override def receive: Receive = {
    case GetDiagramHtml(startTime, endTime, maxSize) =>
      sender() ! DiagramHtml(filteredResponseWithHtml(startTime, endTime, maxSize))
    case ce@CommentEvent(ref, message) =>
      log.debug("Received {}", ce)
      receivedEvents :+= DateTime.now() -> MessageLogger.createSequenceDiagramLine(ref, ref, message) // TODO support the notes in box syntax
    case me@MessageEvent(from, to, message) =>
      log.debug("Received {}", me)
      receivedEvents :+= DateTime.now() ->  MessageLogger.createSequenceDiagramLine(from, to, message)
    case DeadLetter(msg, sender, recipient) => self ! MessageEvent(sender, recipient, s"DeadLetter: ${msg.toString}")
    case _: LogEvent                        =>
  }

  def filteredResponse(startTime: DateTime, endTime: DateTime, maxSize: Int) : String = {
    log.debug("filteredResponse with {} {} {} {}", startTime, endTime, maxSize, receivedEvents.map(_._1))
    val strings: String = receivedEvents.dropWhile(_._1 <= startTime).takeWhile(_._1 <= endTime).map(_._2).take(maxSize).mkString("\n")
    log.debug("Found messages for query: {}", strings)
    strings
  }

  def filteredResponseWithHtml(startTime: DateTime, endTime: DateTime, maxSize: Int = Int.MaxValue) : String = {
    MessageLogger.htmlBegin ++ filteredResponse(startTime, endTime, maxSize) ++ MessageLogger.htmlEnd
  }

}

trait LoggingInterceptor extends Actor with MessageInterceptor {

  override def onIncomingMessage(msg: Any): Unit = {
    // try filter out some internal akka messages
    if(!msg.isInstanceOf[NoSerializationVerificationNeeded]) {
      publishMessageEvent(MessageEvent(sender(), self, msg.toString))
    }
  }

  val toggle: Boolean = RebelConfig(context.system).rebelConfig.visualisation.enabled

  // call by name, to make sure actorrefs are not evaluated
  def publishMessageEvent(messageEvent: => DiagramEvent): Unit = {
    if(toggle) {
      context.system.eventStream.publish(messageEvent)
    }
  }

  override def postStop(): Unit = {
    if(toggle) {
      publishMessageEvent(CommentEvent(self, "Stopped"))
    }
    super.postStop()
  }
}