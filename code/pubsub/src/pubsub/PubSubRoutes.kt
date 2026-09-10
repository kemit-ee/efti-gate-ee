package pubsub

import klite.HttpExchange
import klite.StatusCode.Companion.Created
import klite.annotations.GET
import klite.annotations.POST
import klite.annotations.PathParam
import klite.sse.Event
import klite.sse.send
import klite.sse.startEventStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class PubSubRoutes(private val registry: TopicRegistry) {

  @POST("/publish") fun publish(event: Event, e: HttpExchange) {
    val t = registry.getOrCreate(event.name ?: "message")
    t.publish(event)
    e.send(Created)
  }

  @GET("/subscribe/:topic") fun subscribe(@PathParam topic: String, exchange: HttpExchange) {
    val t = registry.getOrCreate(topic)
    val queue = t.subscribe()

    try {
      exchange.startEventStream()

      while (true) {
        val event = queue.poll(30, TimeUnit.SECONDS)
        if (event != null) {
          exchange.send(event)
        }
      }
    } catch (_: IOException) {
      // Client disconnected
    } finally {
      t.unsubscribe(queue)
    }
  }
}
