package logic

import com.typesafe.config.ConfigFactory
import model.Config

object ConfigLoader {
  def loadConfig: Config = {
    val conf = ConfigFactory.load("application.conf");
    val token = conf.getString("slackbot.token")
    Config(token)
  }
}
