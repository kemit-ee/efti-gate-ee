import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.fluent.en_GB.toHaveSize
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.mockk
import klite.HttpExchange
import org.junit.jupiter.api.Test
import pubsub.PubSubRoutes
import pubsub.Topic
import pubsub.TopicRegistry
import java.time.Instant
import java.util.concurrent.TimeUnit

class PubSubRoutesTest {
  val registry = TopicRegistry()
  val exchange = mockk<HttpExchange>(relaxed = true)
  val routes = PubSubRoutes(registry)

  @Test fun publishCreatesTopicImplicitly() {
    val result = routes.publish(mapOf("data" to "hello"), "events")
    expect(result["data"]).toEqual("hello")
    expect(result["id"]).toEqual(1L)
    expect(result["topic"]).toEqual("events")
  }

  @Test fun publishMissingDataThrows() {
    try {
      routes.publish(emptyMap(), "events")
      throw AssertionError("Expected BadRequest")
    } catch (e: klite.StatusCodeException) {
      expect(e.statusCode).toEqual(klite.StatusCode.BadRequest)
    }
  }

  @Test fun publishIncrementsMessageId() {
    val r1 = routes.publish(mapOf("data" to "first"), "events")
    val r2 = routes.publish(mapOf("data" to "second"), "events")
    expect(r1["id"]).toEqual(1L)
    expect(r2["id"]).toEqual(2L)
  }

  @Test fun publishToSameTopicUsesSameInstance() {
    routes.publish(mapOf("data" to "a"), "my-topic")
    routes.publish(mapOf("data" to "b"), "my-topic")
    val topic = registry.getOrCreate("my-topic")
    expect(topic.messageCount()).toEqual(2L)
  }

  @Test fun publishToDifferentTopicsIsIndependent() {
    routes.publish(mapOf("data" to "a"), "topic-1")
    routes.publish(mapOf("data" to "b"), "topic-2")
    expect(registry.getOrCreate("topic-1").messageCount()).toEqual(1L)
    expect(registry.getOrCreate("topic-2").messageCount()).toEqual(1L)
  }

  @Test fun subscriberReceivesPublishedMessage() {
    val topic = Topic("test", Instant.now())
    val queue = topic.subscribe()
    topic.publish("hello")
    val event = queue.poll(1, TimeUnit.SECONDS)
    expect(event?.data).toEqual("hello")
    expect(event?.id).toEqual(1L)
  }

  @Test fun historyAfterReplaysMissedMessages() {
    val topic = Topic("test", Instant.now())
    topic.publish("first")
    topic.publish("second")
    topic.publish("third")
    val replayed = topic.historyAfter(1)
    expect(replayed).toHaveSize(2)
    expect(replayed[0].data).toEqual("second")
    expect(replayed[1].data).toEqual("third")
  }
}
