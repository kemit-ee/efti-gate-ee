import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import klite.json.JsonMapper
import klite.sse.Event
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class PubSubClientTest {
  private val internalToken = "token"
  private val baseUrl = URI("http://pubsub:8080")
  private val http = mockk<HttpClient>()
  private val json = JsonMapper()
  private val client = PubSubClient(baseUrl, internalToken, http, json)
  // keep pipe writers alive so the SSE body blocks instead of hitting EOF and reconnecting
  private val openPipes = mutableListOf<PipedOutputStream>()

  private fun stringResponse(status: Int = 201, body: String = ""): HttpResponse<String> = mockk {
    every { statusCode() } returns status
    every { body() } returns body
  }

  /** Open-ended SSE body: delivers [events] then blocks, so the client does not reconnect. */
  private fun sseResponse(vararg events: String): HttpResponse<InputStream> {
    val pipe = PipedInputStream()
    val out = PipedOutputStream(pipe).also { openPipes += it }
    out.write(events.joinToString("\n\n", postfix = "\n\n").toByteArray())
    out.flush()
    return mockk {
      every { statusCode() } returns 200
      every { body() } returns pipe
    }
  }

  @Test fun `publish posts rendered event to the publish endpoint`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse()
    val event = Event("hello", name = "events")

    client.publish(event)

    verify {
      http.send(match<HttpRequest> {
        it.uri() == URI("$baseUrl/api/v1/publish") && it.method() == "POST" &&
          it.headers().firstValue("Content-Type").orElse("") == "application/json"
      }, any<BodyHandler<String>>())
    }
  }

  @Test fun `publish skips when PUBSUB_URL is not configured`() {
    val unconfigured = PubSubClient(null, null, http, json)

    unconfigured.publish(Event("hello", name = "events"))

    verify(exactly = 0) { http.send(any(), any<BodyHandler<String>>()) }
  }

  @Test fun `subscribe delivers SSE events to consumer`() {
    every { http.send(any(), any<BodyHandler<InputStream>>()) } returns sseResponse("data: one", "data: two")
    val received = CopyOnWriteArrayList<Event?>()
    val got = CountDownLatch(2)

    client.subscribe("events") {
      received.add(it)
      got.countDown()
    }

    expect(got.await(5, SECONDS)).toEqual(true)
    expect(received.map { it?.data }).toEqual(listOf("one", "two"))
  }

  @Test fun `subscribe passes event name and id from SSE fields`() {
    every { http.send(any(), any<BodyHandler<InputStream>>()) } returns sseResponse("id: 7\nevent: events\ndata: payload")
    val received = CopyOnWriteArrayList<Event?>()
    val got = CountDownLatch(1)

    client.subscribe("events") {
      received.add(it)
      got.countDown()
    }

    expect(got.await(5, SECONDS)).toEqual(true)
    expect(received.first()).toEqual(Event("payload", name = "events", id = "7"))
  }

  @Test fun `subscribe notifies consumer with null on connection error and reconnects`() {
    every { http.send(any(), any<BodyHandler<InputStream>>()) } throws ConnectException("connection refused") andThen sseResponse("data: after-reconnect")
    val received = CopyOnWriteArrayList<Event?>()
    val got = CountDownLatch(2)

    client.subscribe("events") {
      received.add(it)
      got.countDown()
    }

    expect(got.await(5, SECONDS)).toEqual(true)
    expect(received[0]).toEqual(null)
    expect(received[1]).toEqual(Event("after-reconnect"))
  }

  @Test fun `subscribe skips when PUBSUB_URL is not configured`() {
    val unconfigured = PubSubClient(null, null, http, json)
    val received = CopyOnWriteArrayList<Event?>()

    unconfigured.subscribe("events") { received.add(it) }

    verify(exactly = 0) { http.send(any(), any<BodyHandler<InputStream>>()) }
    expect(received.isEmpty()).toEqual(true)
  }
}
