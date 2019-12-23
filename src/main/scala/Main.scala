
import akka.Done
import akka.event.slf4j.Logger
import akka.pattern.after

import dependencies.AppDependencies
import utils._

import scala.concurrent.Future
import scala.concurrent.duration._

object Main extends App with AppDependencies {

  import actorSystem.dispatcher

  private val log = Logger("Main")

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def fetchUrlAndConnect: Future[Done] = (for {
    url <- websocketLogic.getWebsocketUrl
    _ <- websocketLogic.connectToSocket(url)
    _ = log.info("Slack closed the connection. Trying to reconnect...")
    reconnect <- fetchUrlAndConnect
  } yield reconnect).recoverWith {
    case err =>
      log.error(err.getMessage)
      after(1.second, actorSystem.scheduler)(fetchUrlAndConnect)
  }

  ignore(fetchUrlAndConnect)
}
