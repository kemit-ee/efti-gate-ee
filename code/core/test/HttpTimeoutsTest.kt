import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpTimeoutsTest {
  @Test fun `defaults follow the documented budgets`() {
    assertEquals(90, ruuterTimeout.inWholeSeconds)
    assertEquals(30, resqlTimeout.inWholeSeconds)
    assertEquals(10, registryTimeout.inWholeSeconds)
  }

  @Test fun `retries an IOException up to the retry limit and then rethrows`() {
    var calls = 0
    assertFailsWith<IOException> { retryIdempotent("t", retries = 2, backoffMs = 1) { calls++; throw IOException("x") } }
    assertEquals(3, calls)
  }

  @Test fun `a timeout is never retried`() {
    var calls = 0
    assertFailsWith<HttpTimeoutException> { retryIdempotent("t", backoffMs = 1) { calls++; throw HttpTimeoutException("slow") } }
    assertEquals(1, calls)
  }

  @Test fun `a non-IO failure is not retried`() {
    var calls = 0
    assertFailsWith<IllegalStateException> { retryIdempotent("t", backoffMs = 1) { calls++; error("bad") } }
    assertEquals(1, calls)
  }

  @Test fun `returns the first successful result`() {
    var calls = 0
    assertEquals("ok", retryIdempotent("t", backoffMs = 1) { if (calls++ < 1) throw IOException("x") else "ok" })
  }
}
