package edelivery

import PubSubClient
import klite.info
import klite.sse.Event
import klite.warn

class MultiNodeAsyncResponseProvider(
  private val pubSubClient: PubSubClient,
): SingleNodeAsyncResponseProvider() {
  init {
    pubSubClient.subscribe("async-responses") { event ->
      event?.data?.toString()?.let { offerToFirstPending(it) }
    }
  }

  override fun provideResponse(key: RequestKey, payload: String): Boolean {
    if (super.provideResponse(key, payload)) return true
    log.info("Publishing response to pubsub")
    pubSubClient.publish(Event(payload, name = "async-responses"))
    return true
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
