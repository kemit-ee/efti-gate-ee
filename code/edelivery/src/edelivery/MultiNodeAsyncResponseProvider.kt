package edelivery

import klite.Config
import klite.http.post
import klite.info
import klite.sleep
import klite.sse.Event
import klite.sse.getSSE
import klite.warn
import java.net.URI
import java.net.http.HttpClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class MultiNodeAsyncResponseProvider(
  private val http: HttpClient,
  private val pubsubUrl: URI = URI(Config["PUBSUB_URL"]),
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
    http.post(pubsubUrl.resolve("/api/v1/publish"), Event(payload, "async-responses"))
  }

  private fun subscribeSse() {
    log.info("Subscribing to pubsub SSE stream")
    http.getSSE(pubsubUrl.resolve("/api/v1/subscribe/async-responses")).forEach { event ->
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
