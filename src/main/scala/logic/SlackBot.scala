package logic

import akka.actor.ActorSystem
import akka.Done
import akka.event.slf4j.Logger
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.pattern.after
import akka.stream._
import akka.stream.scaladsl._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

import spray.json._

import model._

import com.redis._

object SlackBot extends App {
  implicit val system = ActorSystem()

  import system.dispatcher

  private val log = Logger("SlackBot")
  private val config = ConfigLoader.loadConfig
  private val redisClient = new RedisClient("localhost", 6379)

  private def getWebsocketUrl(token: String): Future[String] = {
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

  private def handleSlackMessage(slackMessage: SlackMessage): Option[Message] =
    slackMessage.text match {
      case setKeyPattern(_, key, value) => Some(handleSetKey(key, value, slackMessage))
      case getKeyPattern(_, key) => Some(handleGetKey(key, slackMessage))
      case _ => None
    }

  private def handleGetKey(key: String, slackMessage: SlackMessage): Message = {
    val channel = slackMessage.channel
    val valueOpt = Try(redisClient.get(calculateRedisKey(key, slackMessage.channel))).getOrElse(None)
    valueOpt.map { value =>
      responseFromString(s"$key: $value", channel)
    }.getOrElse {
      responseFromString(s"Could not find value for key $key in this channel", channel)
    }
  }

  /**
   * Calculate the final redis key from the channel Id and the key.
   */
  private def calculateRedisKey(key: String, channel: String): String = s"$channel-$key"

  private def handleSetKey(key: String, value: String, slackMessage: SlackMessage): Message = {
    val channel = slackMessage.channel
    val isSuccessful = Try(redisClient.set(calculateRedisKey(key, slackMessage.channel), value)).recover{
      case err =>
        log.error(err.getMessage)
        false
    }.getOrElse(false)
    if (isSuccessful){
      responseFromString(s"$key: $value", channel)
    } else {
      responseFromString(s"Could not set $value for key $key", channel)
    }
  }

  private def responseFromString(str: String, channel: String): Message =
    TextMessage(
      SlackMessage("message", str, channel).toJson.toString
    )

  private def connectToSocket(url: String): Future[Done] = {

    val bufferSize = 10
    val elementsToProcess = 5

    val queueDescription: Source[Message, SourceQueueWithComplete[Message]] = Source
      .queue[Message](bufferSize, OverflowStrategy.backpressure)
      .throttle(elementsToProcess, 3.second)
      .map { out =>
        log.debug(s"OUT $out")
        out
      }

    val (queue, source) = queueDescription.preMaterialize

    val sink = Sink.foreach[Message]{ in =>
      log.debug(s"IN $in")
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

    connected.foreach(_ => log.info("Successfully connected to Slack."))
    closed
  }

  private def fetchUrlAndConnect: Future[Done] = (for {
    url <- getWebsocketUrl(config.token)
    _ <- connectToSocket(url)
    _ =  log.info("Slack closed the connection. Trying to reconnect...")
    reconnect <- fetchUrlAndConnect
  } yield reconnect).recoverWith{
    case err =>
      log.error(err.getMessage)
      after(1.second, system.scheduler)(fetchUrlAndConnect)
  }

  fetchUrlAndConnect

}
