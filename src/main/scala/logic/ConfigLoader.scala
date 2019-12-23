package logic

import com.typesafe.config.ConfigFactory
import model.Config

object ConfigLoader {
  def loadConfig: Config = {
    val conf = ConfigFactory.load("application.conf");
    Config(
      conf.getString("slackbot.token"),
      conf.getInt("slackbot.redisPort"),
      conf.getString("slackbot.redisHost")
    )
  }
}
