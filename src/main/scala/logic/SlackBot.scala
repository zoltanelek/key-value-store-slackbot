package logic

import akka.actor.ActorSystem
import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import spray.json._

import model._

object SlackBot extends App {
  implicit val system = ActorSystem()
  import system.dispatcher

  private def startRealTimeConnection(token: String): Future[String] = {
    val queryParams = Query(("token" -> token))

    for {
      response <- Http().singleRequest(HttpRequest(HttpMethods.GET, Uri("https://slack.com/api/rtm.start").withQuery(queryParams)))
      responseBody <- response.entity.dataBytes.runReduce(_ ++ _)
      json = responseBody.utf8String.parseJson
      response = json.asJsObject.convertTo[RtmStartResponse]
      webSocketUrl <- response match {
        case RtmStartResponse(true, Some(url), _) => Future.successful(url)
        case _ => Future.failed(new IllegalArgumentException(s"Fetching websocket url from slack was unsuccessful. " +
          s"Error message: ${response.error.getOrElse("N/A")}"))
      }
    } yield webSocketUrl
  }

  private def connectToSocket(url: String) = {

    val bufferSize = 10
    val elementsToProcess = 5

    val queueDescription: Source[Message, SourceQueueWithComplete[Message]] = Source
      .queue[Message](bufferSize, OverflowStrategy.backpressure)
      .throttle(elementsToProcess, 3.second)
      .map { out =>
        println(s"OUT $out")
        out
      }

    val (queue, source) = queueDescription.preMaterialize

    val sink = Sink.foreach[Message]{ in =>
      println(s"IN $in")
      queue.offer(in)
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
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }
    }

    connected.onComplete(println)
    closed.foreach(_ => println("closed"))
  }

  val f = for {
      url <- startRealTimeConnection("<>")
      _ = connectToSocket(url)
    } yield(())

  f.onComplete {
      case Success(url) => println(url)
      case Failure(t) => println("An error has occurred: " + t.getMessage)
    }
}
