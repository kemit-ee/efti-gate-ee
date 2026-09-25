import klite.*
import klite.http.contentType
import klite.http.post
import klite.json.JsonMapper
import klite.sse.Event
import klite.sse.getSSE
import java.net.URI
import java.net.http.HttpClient
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class PubSubClient(
  private val baseUrl: URI? = Config.optional("PUBSUB_URL")?.let { URI(it) },
  private val internalServiceToken: String? = Config.optional("INTERNAL_SERVICE_TOKEN"),
  private val http: HttpClient,
  private val json: JsonMapper,
) {
  private val log = logger()

  fun publish(event: Event) {
    if (baseUrl == null) return log.warn("PUBSUB_URL not configured, skipping publish to ${event.name}")
    http.post(baseUrl + "/api/v1/publish", json.render(event)) {
      internalServiceToken?.let { header("X-Internal-Service-Token", it) }
      contentType(MimeTypes.json)
    }
  }

  fun subscribe(topic: String, consumer: (e: Event?) -> Unit) {
    if (baseUrl == null) return log.warn("PUBSUB_URL not configured, skipping subscription to $topic")
    thread(name = topic, isDaemon = true) {
      while (!Thread.interrupted()) {
        try {
          log.info("Subscribing to pubsub SSE stream for $topic")
          http.getSSE(baseUrl + "/api/v1/subscribe/$topic") { internalServiceToken?.let { header("X-Internal-Service-Token", it) }; this }.forEach {
            log.info("$topic event received, invoking consumer")
            consumer(it)
          }
        } catch (e: Exception) {
          log.warn("SSE connection error for $topic, reconnecting: ${e.message}")
          sleep(1.seconds)
          consumer(null)
        }
      }
    }
  }
}
