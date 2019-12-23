package dependencies

import akka.actor.ActorSystem

import com.redis.RedisClient
import com.softwaremill.macwire._

import logic._
import model.Config

trait AppDependencies {

  implicit val actorSystem: ActorSystem = ActorSystem()
  lazy val config: Config = ConfigLoader.loadConfig

  lazy val keyValueStore: KeyValueStore = wire[KeyValueStore]
  lazy val websocketLogic: WebsocketLogic = wire[WebsocketLogic]
  lazy val messageHandler: MessageHandler = wire[MessageHandler]

}
