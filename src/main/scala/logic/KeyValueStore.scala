package logic

import akka.event.slf4j.Logger
import com.redis.RedisClient
import model.Config

import scala.util.Try

class KeyValueStore(config: Config) {

  private val log = Logger("KeyValueStore")
  private lazy val redisClient = new RedisClient(config.redisHost, config.redisPort)

  /**
   * Calculate the final redis key from the channel Id and the key.
   */
  def calculateFinalKey(key: String, channel: String): String = s"$channel-$key"

  def get(key: String, channel: String): Option[String] =
    Try(redisClient.get(calculateFinalKey(key, channel))).recover {
      case err =>
        log.error(err.getMessage)
        None
    }.toOption.flatten

  def set(key: String, value: String, channel: String): Boolean =
    Try(redisClient.set(calculateFinalKey(key, channel), value)).recover {
      case err =>
        log.error(err.getMessage)
        false
    }.getOrElse(false)
}
