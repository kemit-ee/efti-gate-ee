package edelivery

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import klite.Config
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import io.mockk.verify
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class EDeliveryClientTest {
  val keyManager = mockk<KeyManager>(relaxed = true)
  val onChangeListener = slot<(Party) -> Unit>()
  val partyRegistry = mockk<PartyRegistry>(relaxUnitFun = true) {
    every { onChange(capture(onChangeListener)) } returns Unit
  }
  val turvisOutbound = mockk<TurvisOutbound>(relaxed = true)
  val messageGenerator = mockk<EDeliveryMessageGenerator>(relaxed = true)
  val eDeliveryClient =
    EDeliveryClient(mockk(relaxed = true), keyManager, messageGenerator, partyRegistry, turvisOutbound, msInSec = 10L)

  val http = mockk<HttpClient>(relaxed = true).also {
    eDeliveryClient.javaClass.getDeclaredField("http").apply { isAccessible = true }.set(eDeliveryClient, it)
  }

  private val successResponse = mockk<HttpResponse<String>> {
    every { statusCode() } returns 200
    every { body() } returns "<ok/>"
  }

  private fun bodyOf(request: HttpRequest): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    request.bodyPublisher().get().subscribe(object : Flow.Subscriber<ByteBuffer> {
      override fun onSubscribe(s: Flow.Subscription) = s.request(Long.MAX_VALUE)
      override fun onNext(item: ByteBuffer) { out.write(ByteArray(item.remaining()).also { item.get(it) }) }
      override fun onError(t: Throwable) = throw t
      override fun onComplete() {}
    })
    return out.toByteArray()
  }

  private fun sentBody(): ByteArray {
    val request = slot<HttpRequest>()
    every { http.send(capture(request), any<BodyHandler<String>>()) } returns successResponse
    eDeliveryClient.send(URI("https://example.com/services/msh"), UserMessageParams(RequestKey(PartyId("EU-FR"))), "<payload/>")
    return bodyOf(request.captured)
  }

  private fun indexOf(haystack: ByteArray, needle: ByteArray) = (0..haystack.size - needle.size).first { i -> needle.indices.all { haystack[i + it] == needle[it] } }

  @Test fun `turvis mode puts the signed bytes and the encrypted attachment on the wire untouched`() {
    val signed = "<env:Envelope>\r\n  <x/> \n</env:Envelope> ".toByteArray() + byteArrayOf(0x0b, 0x7f)
    val attachment = ByteArray(40) { (it * 7).toByte() }
    every { turvisOutbound.authoritative } returns true
    every { turvisOutbound.requestMessage(any(), any()) } returns OutboundMessage(signed, attachment, "enc-1", "sig-1")

    val body = sentBody()

    assertEquals(signed.toList(), body.copyOfRange(indexOf(body, signed), indexOf(body, signed) + signed.size).toList())
    assertEquals(attachment.toList(), body.copyOfRange(indexOf(body, attachment), indexOf(body, attachment) + attachment.size).toList())
    verify(exactly = 0) { messageGenerator.requestMessage(any(), any()) }
  }

  @Test fun `legacy and shadow keep signing in process and never call Turvis`() {
    every { turvisOutbound.authoritative } returns false
    every { messageGenerator.requestMessage(any(), any()) } returns Pair("<legacy/>", ByteArray(3) { 9 })

    val body = sentBody()

    assertEquals(true, String(body).contains("<legacy/>"))
    verify(exactly = 0) { turvisOutbound.requestMessage(any(), any(), any()) }
  }

  @Test fun `a failed Turvis outbound is thrown to the caller and nothing is posted`() {
    every { turvisOutbound.authoritative } returns true
    every { turvisOutbound.requestMessage(any(), any()) } throws TurvisUnavailableException("down")

    assertFailsWith<TurvisUnavailableException> {
      eDeliveryClient.send(URI("https://example.com/services/msh"), UserMessageParams(RequestKey(PartyId("EU-FR"))), "<payload/>")
    }

    verify(exactly = 0) { http.send(any(), any<BodyHandler<String>>()) }
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

  @Test fun `rebuilds and closes http client when party registry changes`() {
    onChangeListener.captured(mockk(relaxed = true))

    val newHttp = eDeliveryClient.javaClass.getDeclaredField("http").apply { isAccessible = true }.get(eDeliveryClient)

    assertNotSame(http, newHttp)
    io.mockk.verify { http.shutdown() }
  }
}
