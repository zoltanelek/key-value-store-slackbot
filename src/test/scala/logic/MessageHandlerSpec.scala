package logic

import org.scalatest._
import org.scalamock.scalatest.MockFactory

class MessageHandlerSpec extends FlatSpec with Matchers with MockFactory {

  private val channel = "testChannel"
  private val testKey = "testKey1234"
  private val testValue = "testValue4321"
  private val mockedKeyValueStore = stub[KeyValueStore]
  private val messageHandler = new MessageHandler(mockedKeyValueStore)

  "MessageHandler" should "get values already set in key-value store" in {
    (mockedKeyValueStore.get _).when(testKey, channel).returns(Some(testValue))
    assert(
      messageHandler
        .getResponse(messageHandler.textMessageFromString(s"GET KEY $testKey", channel))
        .contains(messageHandler.textMessageFromString(s"$testKey: $testValue", channel))
    )
  }

  it should "get response for unused key in the key-value store" in {
    (mockedKeyValueStore.get _).when(testKey, channel).returns(None)
    assert(
      messageHandler
        .getResponse(messageHandler.textMessageFromString(s"GET KEY $testKey", channel))
        .contains(
          messageHandler.textMessageFromString(s"Could not find value for key $testKey in this channel", channel)
        )
    )
  }

  it should "be able to set a value in the key-value store" in {
    (mockedKeyValueStore.set _).when(testKey, testValue, channel).returns(true)
    assert(
      messageHandler
        .getResponse(messageHandler.textMessageFromString(s"SET KEY $testKey $testValue", channel))
        .contains(messageHandler.textMessageFromString(s"$testKey: $testValue", channel))
    )
  }

  it should "be able to respond if it could not set a value in the key-value store" in {
    (mockedKeyValueStore.set _).when(testKey, testValue, channel).returns(false)
    assert(
      messageHandler
        .getResponse(messageHandler.textMessageFromString(s"SET KEY $testKey $testValue", channel))
        .contains(messageHandler.textMessageFromString(s"Could not set $testValue for key $testKey", channel))
    )
  }

  it should "not respond to an invalid command" in {
    assert(
      messageHandler
        .getResponse(
          messageHandler.textMessageFromString("Hey slackbot, this is your friend the invalid command.", channel)
        ).isEmpty
    )
  }

}
