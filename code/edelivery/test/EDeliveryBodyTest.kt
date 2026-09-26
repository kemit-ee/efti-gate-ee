import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import klite.HttpExchange
import klite.MimeTypes
import klite.StatusCode
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class EDeliveryBodyTest {
  private val body = EDeliveryBody()
  private val exchange = mockk<HttpExchange>(relaxed = true)

  @Test fun `renders a String value as raw XML`() {
    body.render(exchange, StatusCode.OK, "<test>data</test>")

    verify { exchange.send(StatusCode.OK, "<test>data</test>", MimeTypes.xml) }
  }

  @Test fun `delegates non-String values to the JSON renderer`() {
    val output = ByteArrayOutputStream()
    every { exchange.startResponse(StatusCode.OK, any(), MimeTypes.json, any()) } returns output

    body.render(exchange, StatusCode.OK, mapOf("k" to "v"))

    kotlin.test.assertEquals("""{"k":"v"}""", output.toString())
  }
}
