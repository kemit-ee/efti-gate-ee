import io.mockk.every
import io.mockk.mockk
import klite.http.HttpException
import org.junit.jupiter.api.Test
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HttpResponsesTest {
  private fun response(status: Int, body: String) = mockk<HttpResponse<String>> {
    every { statusCode() } returns status
    every { body() } returns body
  }

  @Test fun `a success status returns the body`() {
    assertEquals("<ok/>", response(200, "<ok/>").bodyOrThrowStatusOnly())
    assertEquals("", response(204, "").bodyOrThrowStatusOnly())
    assertEquals("moved", response(399, "moved").bodyOrThrowStatusOnly())
  }

  @Test fun `an error status throws an HttpException with the status and none of the answer's text`() {
    listOf(400, 404, 500, 503).forEach { status ->
      val e = assertFailsWith<HttpException> { response(status, "SECRET-ERROR-TEXT driver John Smith").bodyOrThrowStatusOnly() }
      assertEquals(status, e.statusCode.value)
      assertFalse("SECRET-ERROR-TEXT" in e.message.orEmpty() || "John" in e.message.orEmpty(), e.message)
      assertFalse("SECRET-ERROR-TEXT" in e.body || "John" in e.body)
    }
  }
}
