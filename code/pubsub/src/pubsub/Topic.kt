package pubsub

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

data class Message(
  val id: Long,
  val data: String,
  val timestamp: Instant,
)

data class MessageEvent(
  val id: Long,
  val data: String,
  val timestamp: Instant,
  val topic: String,
)

class Topic(val name: String, val createdAt: Instant) {
  private val messageId = AtomicLong(0)
  private val messages = ConcurrentLinkedQueue<MessageEvent>()
  private val subscribers = ConcurrentHashMap.newKeySet<LinkedBlockingQueue<MessageEvent>>()

  fun publish(data: String): MessageEvent {
    val event = MessageEvent(
      id = messageId.incrementAndGet(),
      data = data,
      timestamp = Instant.now(),
      topic = name,
    )
    messages.add(event)
    subscribers.forEach { it.offer(event) }
    return event
  }

  fun subscribe(): LinkedBlockingQueue<MessageEvent> {
    val queue = LinkedBlockingQueue<MessageEvent>()
    subscribers.add(queue)
    return queue
  }

  fun unsubscribe(queue: LinkedBlockingQueue<MessageEvent>) {
    subscribers.remove(queue)
  }

  fun messageCount(): Long = messageId.get()

  fun historyAfter(afterId: Long): List<MessageEvent> =
    messages.filter { it.id > afterId }
}

class TopicRegistry {
  private val topics = ConcurrentHashMap<String, Topic>()

  fun getOrCreate(name: String): Topic =
    topics.computeIfAbsent(name) { Topic(name, Instant.now()) }
}
