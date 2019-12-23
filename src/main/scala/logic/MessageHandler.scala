package logic

import akka.event.slf4j.Logger
import akka.http.scaladsl.model.ws._

import model._

import scala.util.Try

import spray.json._

import com.redis._

class MessageHandler(redisClient: RedisClient) {

  private val log = Logger("MessageHandler")

  /**
   * Tries to parse the incoming message and fetches the response for it.
   */
  def getResponse(incoming: Message): Option[Message] = {
    incoming match {
      case textMessage: TextMessage     => handleJson(textMessage.getStrictText.parseJson.asJsObject)
      case binaryMessage: BinaryMessage => handleJson(binaryMessage.getStrictData.utf8String.parseJson.asJsObject)
    }
  }

  private def handleJson(jsObject: JsObject): Option[Message] = {
    jsObject.fields.get("type") match {
      case Some(JsString("message")) => Try(jsObject.convertTo[SlackMessage]).toOption.flatMap(handleSlackMessage)
      case _                         => None
    }
  }

  private val setKeyPattern = """(:?SET KEY)\s(\w+)\s(\w+)""".r
  private val getKeyPattern = """(:?GET KEY)\s(\w+)""".r

  private def handleSlackMessage(slackMessage: SlackMessage): Option[Message] =
    slackMessage.text match {
      case setKeyPattern(_, key, value) => Some(handleSetKey(key, value, slackMessage))
      case getKeyPattern(_, key)        => Some(handleGetKey(key, slackMessage))
      case _                            => None
    }

  private def handleSetKey(key: String, value: String, slackMessage: SlackMessage): Message = {
    val channel = slackMessage.channel
    val isSuccessful = Try(redisClient.set(calculateFinalKey(key, slackMessage.channel), value)).recover {
      case err =>
        log.error(err.getMessage)
        false
    }.getOrElse(false)
    if (isSuccessful) {
      responseFromString(s"$key: $value", channel)
    } else {
      responseFromString(s"Could not set $value for key $key", channel)
    }
  }

  private def responseFromString(str: String, channel: String): Message =
    TextMessage(
      SlackMessage("message", str, channel).toJson.toString
    )

  private def handleGetKey(key: String, slackMessage: SlackMessage): Message = {
    val channel = slackMessage.channel
    val valueOpt = Try(redisClient.get(calculateFinalKey(key, slackMessage.channel))).getOrElse(None)
    valueOpt.map { value =>
      responseFromString(s"$key: $value", channel)
    }.getOrElse {
      responseFromString(s"Could not find value for key $key in this channel", channel)
    }
  }

  /**
   * Calculate the final redis key from the channel Id and the key.
   */
  private def calculateFinalKey(key: String, channel: String): String = s"$channel-$key"
}
