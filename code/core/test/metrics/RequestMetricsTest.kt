package metrics

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import klite.HttpExchange
import klite.Metrics
import klite.RequestMethod
import klite.StatusCode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RequestMetricsTest {
  private val requestMetrics = RequestMetrics()

  @Suppress("UNCHECKED_CAST")
  private fun requestsByKey() = Metrics.data["httpRequestsTotal"] as Map<String, Long>

  @Test fun `counts a successful request by method and status class`() {
    val exchange = mockk<HttpExchange>()
    val completeHandler = slot<Runnable>()
    every { exchange.onComplete(capture(completeHandler)) } returns Unit
    every { exchange.method } returns RequestMethod.GET
    every { exchange.statusCode } returns StatusCode.OK

    requestMetrics(exchange) { "ok" }
    completeHandler.captured.run()

    assertEquals(1L, requestsByKey()["get_2xx"])
    assertEquals(0L, Metrics.data["httpRequestErrorsTotal"])
  }

  @Test fun `counts an error response and increments the error total`() {
    val exchange = mockk<HttpExchange>()
    val completeHandler = slot<Runnable>()
    every { exchange.onComplete(capture(completeHandler)) } returns Unit
    every { exchange.method } returns RequestMethod.POST
    every { exchange.statusCode } returns StatusCode.InternalServerError

    requestMetrics(exchange) { null }
    completeHandler.captured.run()

    assertEquals(1L, requestsByKey()["post_5xx"])
    assertEquals(1L, Metrics.data["httpRequestErrorsTotal"])
  }

  @Test fun `treats a missing status code as 0xx and not an error`() {
    val exchange = mockk<HttpExchange>()
    val completeHandler = slot<Runnable>()
    every { exchange.onComplete(capture(completeHandler)) } returns Unit
    every { exchange.method } returns RequestMethod.GET
    every { exchange.statusCode } returns null

    requestMetrics(exchange) { null }
    completeHandler.captured.run()

    assertEquals(1L, requestsByKey()["get_0xx"])
  }

  @Test fun `returns the handler's result unchanged`() {
    val exchange = mockk<HttpExchange>()
    every { exchange.onComplete(any()) } returns Unit

    val result = requestMetrics(exchange) { "payload" }

    assertEquals("payload", result)
  }

  @Test fun `duration average is 0 before any request has completed`() {
    assertEquals(0.0, RequestMetrics().let { Metrics.data["httpRequestDurationMsAvg"] })
  }
}
