package pubsub

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import klite.HttpExchange
import klite.MimeTypes
import klite.StatusCode
import klite.StatusCodeException
import klite.annotations.GET
import klite.annotations.POST
import klite.annotations.PathParam
import klite.sse.Event
import klite.sse.send
import klite.sse.startEventStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class PubSubRoutes(private val registry: TopicRegistry) {

  @Operation(summary = "Publish a message to a topic")
  @ApiResponse(responseCode = "200", description = "Message published", content = [Content(mediaType = MimeTypes.json, schema = Schema(implementation = Message::class))])
  @POST("/publish/:topic") fun publish(body: Map<String, String>, @PathParam topic: String): Map<String, Any?> {
    val t = registry.getOrCreate(topic)
    val data = body["data"] ?: throw StatusCodeException(StatusCode.BadRequest, "Missing 'data' field")
    val event = t.publish(data)
    return mapOf("id" to event.id, "data" to event.data, "timestamp" to event.timestamp.toString(), "topic" to event.topic)
  }

  @Operation(summary = "Subscribe to a topic via SSE", description = "Pass Last-Event-ID header to resume from a specific message ID.")
  @Parameter(description = "Topic name", name = "topic")
  @ApiResponse(responseCode = "200", description = "SSE stream of messages", content = [Content(mediaType = "text/event-stream")])
  @GET("/subscribe/:topic") fun subscribe(@PathParam topic: String, exchange: HttpExchange) {
    val t = registry.getOrCreate(topic)
    val queue = t.subscribe()
    val resumeFrom = exchange.header("Last-Event-ID")?.toLongOrNull()

    try {
      resumeFrom?.let { t.historyAfter(it).forEach { event ->
        exchange.send(Event(data = event.data, name = event.topic, id = event.id))
      } }

      exchange.startEventStream()

      while (true) {
        val event = queue.poll(30, TimeUnit.SECONDS)
        if (event != null) {
          exchange.send(Event(data = event.data, name = event.topic, id = event.id))
        }
      }
    } catch (_: IOException) {
      // Client disconnected
    } finally {
      t.unsubscribe(queue)
    }
  }
}
