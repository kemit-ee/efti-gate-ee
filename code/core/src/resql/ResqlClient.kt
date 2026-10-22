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

  /** Best-effort audit row (docs/specs/logging-spec.md §5.2); [details] must hold ids and buckets only, never message content. */
  fun insertAuditLog(action: String, resource: String, resourceId: String?, details: Map<String, String>) {
    val body = mapOf("action" to action, "resource" to resource, "resourceId" to resourceId, "details" to jsonMapper.render(details))
    http.post(baseUrl + "/insert_audit_log", jsonMapper.render(body)) { timeout(resqlTimeout) }.bodyOrThrow()
  }
}

data class AsyncResponseRow(val body: String)
