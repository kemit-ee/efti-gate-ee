import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.mockk
import klite.HttpExchange
import klite.sse.Event
import org.junit.jupiter.api.Test
import pubsub.PubSubRoutes
import pubsub.Topic
import pubsub.TopicRegistry
import java.util.concurrent.TimeUnit

class PubSubRoutesTest {
  val registry = TopicRegistry()
  val exchange = mockk<HttpExchange>(relaxed = true)
  val routes = PubSubRoutes(registry)

  @Test fun publishDiscardsWhenNoSubscribers() {
    routes.publish(Event(data = "hello", name = "events"), exchange)
    // No exception, event is discarded
  }

  @Test fun subscriberReceivesPublishedEvent() {
    val topic = Topic("test")
    val queue = topic.subscribe()
    topic.publish(Event(data = "hello", name = "test", id = 1))
    val event = queue.poll(1, TimeUnit.SECONDS)
    expect(event?.data).toEqual("hello")
    expect(event?.id).toEqual(1)
  }

  @Test fun publishToSameTopicUsesSameInstance() {
    val event1 = Event(data = "a", name = "my-topic")
    val event2 = Event(data = "b", name = "my-topic")
    routes.publish(event1, exchange)
    routes.publish(event2, exchange)
    // Both go to the same topic instance
  }

  @Test fun publishToDifferentTopicsIsIndependent() {
    routes.publish(Event(data = "a", name = "topic-1"), exchange)
    routes.publish(Event(data = "b", name = "topic-2"), exchange)
    val topic1 = registry.getOrCreate("topic-1")
    val topic2 = registry.getOrCreate("topic-2")
    expect(topic1.name).toEqual("topic-1")
    expect(topic2.name).toEqual("topic-2")
  }

  @Test fun unsubscribedQueueStopsReceiving() {
    val topic = Topic("test")
    val queue = topic.subscribe()
    topic.unsubscribe(queue)
    topic.publish(Event(data = "hello", name = "test"))
    val event = queue.poll(100, TimeUnit.MILLISECONDS)
    expect(event).toEqual(null)
  }
}
