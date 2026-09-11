package pubsub

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.MimeTypes
import klite.StatusCode.Companion.Created
import klite.annotations.GET
import klite.annotations.POST
import klite.annotations.PathParam
import klite.sse.Event
import klite.sse.send
import klite.sse.startEventStream
import java.io.IOException
import java.util.concurrent.TimeUnit

@Tag(name = "Pub/Sub", description = "Internal transient event delivery over Server-Sent Events.")
class PubSubRoutes(private val registry: TopicRegistry) {

  @Operation(
    summary = "Publish an event",
    description = "Broadcasts the event to all currently connected subscribers of its topic. Events are not persisted or replayed.",
  )
  @RequestBody(
    description = "Event to publish. The name selects the topic; when omitted, the message topic is used.",
    content = [Content(mediaType = MimeTypes.json, schema = Schema(type = "object"))],
  )
  @ApiResponse(responseCode = "201", description = "Event accepted for broadcast")
  @POST("/publish") fun publish(event: Event, e: HttpExchange) {
    val t = registry.getOrCreate(event.name ?: "message")
    t.publish(event)
    e.send(Created)
  }

  @Operation(
    summary = "Subscribe to a topic",
    description = "Opens a long-lived SSE stream. Events published while the connection is open are delivered to the subscriber; missed events are not replayed.",
  )
  @ApiResponse(
    responseCode = "200",
    description = "SSE event stream",
    content = [Content(mediaType = "text/event-stream", schema = Schema(type = "string"))],
  )
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
