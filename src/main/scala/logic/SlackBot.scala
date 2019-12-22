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

import com.redis._

object SlackBot extends App {
  implicit val system = ActorSystem()
  import system.dispatcher

  private val redisClient = new RedisClient("localhost", 6379)

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
    val returning = slackMessage.text match {
      case setKeyPattern(_, key, value) => Some(handleSetKey(key, value, slackMessage))
      case getKeyPattern(_, key) => Some(handleGetKey(key, slackMessage))
      case _ => None
    }
    println(s"returning: $returning")
    returning
  }

  private def handleGetKey(key: String, slackMessage: SlackMessage) = {
    println(s"handleGetKey(key $key)")
    val valueOpt = Try(redisClient.get(key)).getOrElse(None)
    valueOpt.map { value =>
      println(s"from redis: key $key value $value")
      responseFromString(s"$key: $value", slackMessage)
    }.getOrElse {
      println(s"Could not find value for key $key")
      responseFromString(s"Could not find value for key $key", slackMessage)
    }
  }

  private def handleSetKey(key: String, value: String, slackMessage: SlackMessage) = {
    println(s"handleSetKey(key $key, value $value)")
    val isSuccessful = Try(redisClient.set(key, value)).recover{
      case err =>
        println(err.getMessage)
        false
    }.getOrElse(false)
    println(s"to redis: key $key value $value")
    if (isSuccessful){
      responseFromString(s"$key: $value", slackMessage)
    } else {
      responseFromString(s"Could not set $value for key $key", slackMessage)
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
