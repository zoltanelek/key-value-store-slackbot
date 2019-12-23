package model

final case class Config(
  token:     String,
  redisPort: Int,
  redisHost: String
)
