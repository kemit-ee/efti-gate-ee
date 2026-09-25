import klite.*
import klite.sse.getSSE
import java.net.URI
import java.net.http.HttpClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class PubSubClient(
  private val http: HttpClient,
  private val baseUrl: URI? = Config.optional("PUBSUB_URL")?.let { URI(it) }
) {
  private val log = logger()

  fun subscribe(topic: String, consumer: () -> Unit) {
    if (baseUrl == null) return log.warn("PUBSUB_URL not configured, skipping subscription to $topic")
    thread(name = topic, isDaemon = true) {
      while (!Thread.interrupted()) {
        try {
          log.info("Subscribing to pubsub SSE stream for $topic")
          http.getSSE(baseUrl + "/api/v1/subscribe/$topic").forEach {
            log.info("$topic event received, invoking consumer")
            consumer()
          }
        } catch (e: Exception) {
          log.warn("SSE connection error for $topic, reconnecting: ${e.message}")
          sleep(1.seconds)
          consumer()
        }
      }
    }
  }
}
