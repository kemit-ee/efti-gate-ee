package resql

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import klite.http.HttpException
import klite.json.JsonMapper
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import resqlTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResqlClientTest {
  private val http = mockk<HttpClient>()
  private val json = JsonMapper()
  private val client = ResqlClient(URI("http://resql:8090/efti"), http, json)

  private fun stringResponse(status: Int = 200, body: String = "[]"): HttpResponse<String> = mockk {
    every { statusCode() } returns status
    every { body() } returns body
  }

  @Test fun `insertAsyncResponse posts the request key and body to resql`() {
    val requestSlot = slot<HttpRequest>()
    every { http.send(capture(requestSlot), any<BodyHandler<String>>()) } returns stringResponse()

    client.insertAsyncResponse("req-1", "<xml/>")

    assertEquals(URI("http://resql:8090/efti/insert_async_response"), requestSlot.captured.uri())
    assertEquals("POST", requestSlot.captured.method())
  }

  @Test fun `insertAsyncResponse throws on a non-2xx resql response`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(status = 500, body = "boom")

    assertFailsWith<HttpException> { client.insertAsyncResponse("req-1", "<xml/>") }
  }

  @Test fun `claimAsyncResponse returns the body of the first claimed row`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = """[{"body":"<xml/>"}]""")

    assertEquals("<xml/>", client.claimAsyncResponse("req-1"))
  }

  @Test fun `claimAsyncResponse returns null when nothing was claimed`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = "[]")

    assertNull(client.claimAsyncResponse("req-1"))
  }

  @Test fun `insert and claim requests carry the resql timeout and are never retried`() {
    val requests = mutableListOf<HttpRequest>()
    every { http.send(capture(requests), any<BodyHandler<String>>()) } returns stringResponse()

    client.insertAsyncResponse("req-1", "<xml/>")
    client.claimAsyncResponse("req-1")

    assertEquals(2, requests.size)
    requests.forEach { assertEquals(java.time.Duration.ofSeconds(resqlTimeout.inWholeSeconds), it.timeout().get()) }
  }

  @Test fun `a network failure on claim propagates without a retry`() {
    var calls = 0
    every { http.send(any(), any<BodyHandler<String>>()) } answers { calls++; throw java.io.IOException("reset") }

    kotlin.test.assertFailsWith<java.io.IOException> { client.claimAsyncResponse("req-1") }
    assertEquals(1, calls)
  }

  @Test fun `insertAuditLog posts the declared parameters with details as a JSON string`() {
    val requestSlot = slot<HttpRequest>()
    every { http.send(capture(requestSlot), any<BodyHandler<String>>()) } returns stringResponse()

    client.insertAuditLog("as4.handler_failed", "as4_message", "ref-1", mapOf("reason" to "OTHER"))

    assertEquals(URI("http://resql:8090/efti/insert_audit_log"), requestSlot.captured.uri())
    assertEquals("POST", requestSlot.captured.method())
  }

  @Test fun `findInboundMessage posts to resql and maps the first row`() {
    val requestSlot = slot<HttpRequest>()
    every { http.send(capture(requestSlot), any<BodyHandler<String>>()) } returns stringResponse(body = """[{"receiptB64":"UkVD","payloadSha256":"abc"}]""")

    assertEquals(InboundMessageRow("UkVD", "abc"), client.findInboundMessage("EU-DE", "m-1"))
    assertEquals(URI("http://resql:8090/efti/find_inbound_message"), requestSlot.captured.uri())
  }

  @Test fun `findInboundMessage is null for a new message and throws on a resql error`() {
    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = "[]")
    assertNull(client.findInboundMessage("EU-DE", "m-1"))

    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(status = 500, body = "boom")
    assertFailsWith<HttpException> { client.findInboundMessage("EU-DE", "m-1") }
  }

  @Test fun `insertInboundMessage is true only when resql returned the inserted row`() {
    val requestSlot = slot<HttpRequest>()
    every { http.send(capture(requestSlot), any<BodyHandler<String>>()) } returns stringResponse(body = """[{"rowId":"1"}]""")
    assertEquals(true, client.insertInboundMessage("EU-DE", "m-1", "abc", "UkVD"))
    assertEquals(URI("http://resql:8090/efti/insert_inbound_message"), requestSlot.captured.uri())

    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(body = "[]")
    assertEquals(false, client.insertInboundMessage("EU-DE", "m-1", "abc", "UkVD"))

    every { http.send(any(), any<BodyHandler<String>>()) } returns stringResponse(status = 500, body = "boom")
    assertFailsWith<HttpException> { client.insertInboundMessage("EU-DE", "m-1", "abc", "UkVD") }
  }
}
