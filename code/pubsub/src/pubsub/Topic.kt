package pubsub

import klite.sse.Event
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

class Topic(val name: String) {
  private val subscribers = ConcurrentHashMap.newKeySet<LinkedBlockingQueue<Event>>()

  fun publish(event: Event) {
    subscribers.forEach { it.offer(event) }
  }

  fun subscribe(): LinkedBlockingQueue<Event> {
    val queue = LinkedBlockingQueue<Event>()
    subscribers.add(queue)
    return queue
  }

  fun unsubscribe(queue: LinkedBlockingQueue<Event>) {
    subscribers.remove(queue)
  }
}

class TopicRegistry {
  private val topics = ConcurrentHashMap<String, Topic>()

  fun getOrCreate(name: String): Topic =
    topics.computeIfAbsent(name) { Topic(name) }
}
