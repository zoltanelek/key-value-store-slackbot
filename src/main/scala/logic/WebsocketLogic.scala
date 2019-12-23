package logic

import akka.actor.ActorSystem
import akka.Done
import akka.event.slf4j.Logger
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.stream._
import akka.stream.scaladsl._

import scala.concurrent.Future
import scala.concurrent.duration._

import spray.json._

import model._
import utils._

class WebsocketLogic(
  config:         Config,
  messageHandler: MessageHandler,
  actorSystem:    ActorSystem
) {

  import actorSystem.dispatcher
  private implicit val system = actorSystem
  private val log = Logger("WebsocketLogic")

  /**
   * Gets a websocket url from slack's rtm.start API endpoint
   * @return
   */
  def getWebsocketUrl: Future[String] = {
    val queryParams = Query(("token" -> config.token))

    for {
      response <- Http().singleRequest(HttpRequest(HttpMethods.GET, Uri("https://slack.com/api/rtm.start").withQuery(queryParams)))
      responseBody <- response.entity.dataBytes.runReduce(_ ++ _)
      json = responseBody.utf8String.parseJson
      response = json.asJsObject.convertTo[RtmStartResponse]
      webSocketUrl <- response match {
        case RtmStartResponse(true, Some(url), _) => Future.successful(url)
        case _ => Future.failed[String](new IllegalArgumentException(s"Fetching websocket url from slack was unsuccessful. " +
          s"Error message: ${response.error.getOrElse("N/A")}"))
      }
    } yield webSocketUrl
  }

  /**
   * Connects to a websocket url and calls the message handler for every message,
   * then sends the output trough an outgoing queue.
   * @param url The websocket url used for the connection.
   */
  def connectToSocket(url: String): Future[Done] = {

    val bufferSize = 10
    val elementsToProcess = 5

    @SuppressWarnings(Array("org.wartremover.warts.ToString"))
    val queueDescription: Source[Message, SourceQueueWithComplete[Message]] = Source
      .queue[Message](bufferSize, OverflowStrategy.backpressure)
      .throttle(elementsToProcess, 3.second)
      .map { out =>
        log.debug(s"OUT ${out.toString}")
        out
      }

    val (queue, source) = queueDescription.preMaterialize

    @SuppressWarnings(Array("org.wartremover.warts.ToString"))
    val sink = Sink.foreach[Message] { in =>
      log.debug(s"IN ${in.toString}")
      ignore(messageHandler.getResponse(in).map(response =>
        queue.offer(response)))
    }

    val webSocketFlow = Http().webSocketClientFlow(WebSocketRequest(url))

    val (upgradeResponse, closed) =
      source
        .viaMat(webSocketFlow)(Keep.right)
        .toMat(sink)(Keep.both)
        .run()

    val connected = upgradeResponse.flatMap { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Future.successful(Done)
      } else {
        Future.failed[Done](new RuntimeException(s"Connection failed: ${upgrade.response.status.toString}"))
      }
    }

    connected.foreach(_ => log.info("Successfully connected to Slack."))
    closed
  }

}
