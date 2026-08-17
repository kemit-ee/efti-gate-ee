package edelivery

import core.EDeliveryClient
import core.KeyManager
import core.PartyRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import klite.Config
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EDeliveryClientTest {
  init {
    Config["KEYSTORE_DIR"] = "../gate/certs"
  }

  val partyRegistry = mockk<PartyRegistry>(relaxUnitFun = true) {
    every { onChange(any()) } just Runs
  }
  val keyManager = mockk<KeyManager>(relaxed = true)
  val eDeliveryClient =
    EDeliveryClient(mockk(relaxed = true), keyManager, mockk(relaxed = true), partyRegistry, msInSec = 10L)

  val http = mockk<HttpClient>().also {
    eDeliveryClient.javaClass.getDeclaredField("http").apply { isAccessible = true }.set(eDeliveryClient, it)
  }

  private val successResponse = mockk<HttpResponse<String>> {
    every { statusCode() } returns 200
    every { body() } returns "<ok/>"
  }

  @Test fun `sendWithRetry 3 fails and success`() {
    var callCount = 0

    every { http.send(any(), any<BodyHandler<String>>()) } answers {
      if (++callCount < 4) throw IOException("Connection refused")
      else successResponse
    }

    val result = eDeliveryClient.sendWithRetry(URI("https://example.com/services/msh"), mockk(relaxed = true))

    assertEquals(4, callCount)
    assertEquals(successResponse, result)
  }

  @Test fun `sendWithRetry throws timeout`() {
    var callCount = 0

    every { http.send(any(), any<BodyHandler<String>>()) } answers {
      callCount++
      throw HttpTimeoutException("Request timed out")
    }

    val exception = assertFailsWith<HttpTimeoutException> {
      eDeliveryClient.sendWithRetry(URI("https://example.com/services/msh"), mockk(relaxed = true))
    }

    assertEquals(1, callCount)
    assertEquals("Request timed out", exception.message)
  }

  @Test fun `sendWithRetry all requests fail`() {
    var callCount = 0

    every { http.send(any(), any<BodyHandler<String>>()) } answers {
      callCount++
      throw IOException("Connection refused")
    }

    val exception = assertFailsWith<IOException> {
      eDeliveryClient.sendWithRetry(URI("https://example.com/services/msh"), mockk(relaxed = true))
    }

    assertEquals(5, callCount)
    assertEquals("All 5 attempts failed without hitting timeout", exception.message)
  }
}
