package model

import spray.json._

final case class SlackMessage(
  `type`:  String,
  text:    String,
  channel: String
)

object SlackMessage extends DefaultJsonProtocol {
  implicit val slackMessageFormat = jsonFormat3(SlackMessage.apply)
}
