package model

import spray.json._

final case class RtmStartResponse(
  ok:    Boolean,
  url:   Option[String],
  error: Option[String]
)

object RtmStartResponse extends DefaultJsonProtocol {
  implicit val rtmStartResponseFormat: RootJsonFormat[RtmStartResponse] = jsonFormat3(RtmStartResponse.apply)
}
