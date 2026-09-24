package edelivery

import klite.*
import klite.http.contentType
import klite.http.post
import klite.http.timeout
import klite.json.JsonMapper
import klite.sse.Event
import klite.sse.getSSE
import java.net.URI
import java.net.http.HttpClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class MultiNodeAsyncResponseProvider(
  private val http: HttpClient,
  private val json: JsonMapper,
  private val pubsubUrl: URI = URI(Config["PUBSUB_URL"]),
  private val internalServiceToken: String = Config.optional("INTERNAL_SERVICE_TOKEN").orEmpty(),
): SingleNodeAsyncResponseProvider() {

  init {
    thread(name = "${this::class.simpleName}-sse", isDaemon = true) {
      while (true) {
        try { subscribeSse() } catch (e: Exception) {
          log.warn("SSE connection error, reconnecting: ${e.message}")
          sleep(1.seconds)
        }
      }
    }
  }

  override fun provideResponse(key: RequestKey, payload: String): Boolean {
    if (super.provideResponse(key, payload)) return true
    publishToPubsub(payload)
    return true
  }

  private fun publishToPubsub(payload: String) {
    log.info("Publishing response to pubsub")
    http.post(pubsubUrl.resolve("/api/v1/publish"), json.render(Event(payload, "async-responses"))) {
      contentType(MimeTypes.json)
      header("X-Internal-Service-Token", internalServiceToken)
    }
  }

  private fun subscribeSse() {
    log.info("Subscribing to pubsub SSE stream")
    http.getSSE(pubsubUrl.resolve("/api/v1/subscribe/async-responses")) {
      timeout(1.hours)
      header("X-Internal-Service-Token", internalServiceToken)
    }.forEach { event ->
      event.data?.toString()?.let { offerToFirstPending(it) }
    }
  }

  private fun offerToFirstPending(body: String) {
    for ((key, queue) in pendingResponses) {
      if (queue.offer(body)) {
        log.info("Delivered pubsub response for $key")
        return
      }
    }
    log.warn("No pending responses for pubsub message, discarding")
  }
}
