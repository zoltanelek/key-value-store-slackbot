# key-value-store-slackbot
A basic key-value store slackbot written in scala.

## Configuring the bot

### Setting up Redis

Before you run the slack bot, you have to run redis on your machine.
The simplest way to do this, if you already have docker installed is to run:
```shell script
docker start redis
```
This will start a redis server on the port `6379`.
If you don't have docker installed, check out the [Docker Instalation Docs.](https://docs.docker.com/install/)

If you prefer a native redis installation, check [redis.io](https://redis.io/)

### Setting up the config files

By default the bot will check [slackbot.conf](src/main/resources/slackbot.conf) 
for the Redis host and port and the slack token.

To run the bot you need an app token from [Slack](https://api.slack.com/authentication/basics)
and replace the token in the config. Look for `token = "replace-this"`.

## Running the bot

To run the slack bot you can call:
```shell script
sbt run
```
Alternatively if you want to pass your own application and slackbot config, run:
```shell script
sbt -Dconfig.file=~/my/absolute/path/to/application.conf run
```
You can run the tests with:
```shell script
sbt test
```

## Interacting with the bot
The bot can be interacted with in it's own DM chat.
Send:
```
SET KEY foo bar
```
to set the value `bar` in the key-value store for the key `foo`.
The bot will reply with the following pattern:
```
<key>: <value>
```

To get a value send:
```
GET KEY foo
```
to fetch the value for the key `foo`.
