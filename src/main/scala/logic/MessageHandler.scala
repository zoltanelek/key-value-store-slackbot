package logic

import akka.event.slf4j.Logger
import akka.http.scaladsl.model.ws._

import model._

import scala.util.Try

import spray.json._

class MessageHandler(keyValueStore: KeyValueStore) {

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
    val isSuccessful = keyValueStore.set(key, value, channel)
    if (isSuccessful) {
      textMessageFromString(s"$key: $value", channel)
    } else {
      textMessageFromString(s"Could not set $value for key $key", channel)
    }
  }

  private def handleGetKey(key: String, slackMessage: SlackMessage): Message = {
    val channel = slackMessage.channel
    val valueOpt = keyValueStore.get(key, channel)
    valueOpt.map { value =>
      textMessageFromString(s"$key: $value", channel)
    }.getOrElse {
      textMessageFromString(s"Could not find value for key $key in this channel", channel)
    }
  }

  def textMessageFromString(str: String, channel: String): Message =
    TextMessage(
      SlackMessage("message", str, channel).toJson.toString
    )

}
