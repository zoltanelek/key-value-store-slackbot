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
import scala.util.{Failure, Success, Try}
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

  private def getResponse(incoming: Message): Option[Message] = {
    incoming match {
      case textMessage: TextMessage => handleJson(textMessage.getStrictText.parseJson.asJsObject)
      case binaryMessage: BinaryMessage => handleJson(binaryMessage.getStrictData.utf8String.parseJson.asJsObject)
    }
  }

  private  def handleJson(jsObject: JsObject): Option[Message] = {
    jsObject.fields.get("type") match {
      case Some(JsString("message")) => Try(jsObject.convertTo[SlackMessage]).toOption.flatMap(handleSlackMessage)
      case _ => None
    }
  }

  private val setKeyPattern = """(:?SET KEY)\s(\w+)\s(\w+)""".r
  private val getKeyPattern = """(:?GET KEY)\s(\w+)""".r

  private def handleSlackMessage(slackMessage: SlackMessage): Option[Message] = {
    slackMessage.text match {
      case setKeyPattern(_, key, value) => Some(responseFromString(s"setting key $key to value $value", slackMessage))
      case getKeyPattern(_, key) => Some(responseFromString(s"getting value for key $key", slackMessage))
      case _ => None
    }
  }

  private def responseFromString(str: String, slackMessage: SlackMessage) = {
    TextMessage(
      SlackMessage("message", str, slackMessage.channel).toJson.toString
    )
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
      getResponse(in).map(response =>
        queue.offer(response)
      )
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
