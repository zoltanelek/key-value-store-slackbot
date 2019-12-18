package logic

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._

import scala.concurrent.Future
import scala.util.{Failure, Success}

import spray.json._

import model._

object SlackBot extends App {
  implicit val system = ActorSystem()
  import system.dispatcher

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

  getWebsocketUrl("token") onComplete {
    case Success(url) => println(url)
    case Failure(t) => println("An error has occurred: " + t.getMessage)
  }

}
