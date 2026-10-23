package resql

import klite.Config
import klite.http.bodyOrThrow
import klite.http.post
import klite.http.timeout
import klite.json.JsonMapper
import klite.json.parse
import klite.plus
import java.net.URI
import java.net.http.HttpClient
import resqlTimeout

class ResqlClient(
  private val baseUrl: URI = URI(Config["RESQL_URL"] + "/efti"),
  private val http: HttpClient,
  private val jsonMapper: JsonMapper,
) {
  fun insertAsyncResponse(requestKey: String, body: String) {
    http.post(baseUrl + "/insert_async_response", jsonMapper.render(mapOf("requestKey" to requestKey, "body" to body))) { timeout(resqlTimeout) }.bodyOrThrow()
  }

  fun claimAsyncResponse(requestKey: String): String? {
    val res = http.post(baseUrl + "/claim_async_response", jsonMapper.render(mapOf("requestKey" to requestKey))) { timeout(resqlTimeout) }
    return jsonMapper.parse<List<AsyncResponseRow>>(res.bodyOrThrow()).firstOrNull()?.body
  }

  /** The stored receipt and payload hash of an inbound AS4 message already accepted from this verified sender (#336), or null for a new one. */
  fun findInboundMessage(senderId: String, messageId: String): InboundMessageRow? {
    val res = http.post(baseUrl + "/find_inbound_message", jsonMapper.render(mapOf("senderId" to senderId, "messageId" to messageId))) { timeout(resqlTimeout) }
    return jsonMapper.parse<List<InboundMessageRow>>(res.bodyOrThrow()).firstOrNull()
  }

  /** Records an accepted inbound message; true if this call recorded the pair, false if it was already there (a repeat or a concurrent winner). */
  fun insertInboundMessage(senderId: String, messageId: String, payloadSha256: String, receiptB64: String): Boolean {
    val body = mapOf("senderId" to senderId, "messageId" to messageId, "payloadSha256" to payloadSha256, "receiptB64" to receiptB64)
    val res = http.post(baseUrl + "/insert_inbound_message", jsonMapper.render(body)) { timeout(resqlTimeout) }
    return jsonMapper.parse<List<Map<String, Any?>>>(res.bodyOrThrow()).isNotEmpty()
  }

  /** Best-effort audit row (docs/specs/logging-spec.md §5.2); [details] must hold ids and buckets only, never message content. */
  fun insertAuditLog(action: String, resource: String, resourceId: String?, details: Map<String, String>) {
    val body = mapOf("action" to action, "resource" to resource, "resourceId" to resourceId, "details" to jsonMapper.render(details))
    http.post(baseUrl + "/insert_audit_log", jsonMapper.render(body)) { timeout(resqlTimeout) }.bodyOrThrow()
  }
}

data class AsyncResponseRow(val body: String)

data class InboundMessageRow(val receiptB64: String, val payloadSha256: String)
