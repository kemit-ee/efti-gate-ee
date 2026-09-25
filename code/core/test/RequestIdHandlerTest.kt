import com.sun.net.httpserver.Headers
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import klite.HttpExchange
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals

class RequestIdHandlerTest {
  private val handler = RequestIdHandler()

  @Test fun `uses the incoming x-request-id header when present`() {
    val headers = Headers().apply { set("x-request-id", "existing-id") }

    assertEquals("existing-id", handler(headers))
  }

  @Test fun `generates a random UUID when the header is missing`() {
    val id = handler(Headers())

    UUID.fromString(id)
  }

  @Test fun `generates a different id on every call when the header is missing`() {
    assertEquals(false, handler(Headers()) == handler(Headers()))
  }

  @Test fun `send writes the request id onto the response header`() {
    val exchange = mockk<HttpExchange>()
    every { exchange.header(any(), any()) } returns Unit
    val requestId = UUID.randomUUID()

    handler.send(exchange, requestId)

    verify { exchange.header("x-request-id", requestId.toString()) }
  }
}
