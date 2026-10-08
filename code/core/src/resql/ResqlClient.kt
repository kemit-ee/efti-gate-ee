package resql

import klite.Config
import klite.http.bodyOrThrow
import klite.http.post
import klite.json.JsonMapper
import klite.json.parse
import klite.plus
import java.net.URI
import java.net.http.HttpClient

class ResqlClient(
  private val baseUrl: URI = URI(Config["RESQL_URL"] + "/efti"),
  private val http: HttpClient,
  private val jsonMapper: JsonMapper,
) {
  fun insertAsyncResponse(requestKey: String, body: String) {
    http.post(baseUrl + "/insert_async_response", jsonMapper.render(mapOf("requestKey" to requestKey, "body" to body))).bodyOrThrow()
  }

  fun claimAsyncResponse(requestKey: String): String? {
    val res = http.post(baseUrl + "/claim_async_response", jsonMapper.render(mapOf("requestKey" to requestKey)))
    return jsonMapper.parse<List<AsyncResponseRow>>(res.bodyOrThrow()).firstOrNull()?.body
  }
}

data class AsyncResponseRow(val body: String)
