import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import klite.*
import klite.annotations.GET
import klite.annotations.POST
import klite.annotations.PathParam
import klite.http.post
import klite.http.timeout
import java.lang.Thread.currentThread
import java.net.URI
import java.net.http.HttpClient
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.time.Duration.Companion.seconds

const val pollMoreHeader = "x-poll-more"

class MultiplexerRoutes(private val registry: MultiplexerGateRegistry, private val http: HttpClient) {
  private val eDeliveryUrl = URI(Config["EDELIVERY_URL"])
  private val pending = Cache<UUID, PartyResponses>(90.seconds)
  // How long GET /rest waits for the first gate response before returning empty. Bounded so a
  // poll never hangs like the old blocking POST /first did; the caller just polls again.
  private val restPollSeconds = Config.optional("MULTIPLEXER_REST_POLL_SECONDS")?.toLong() ?: 30L

  @Operation(summary = "Broadcast a search request", description = "Registers the search and fans it out to every registered gate in the background, then returns immediately. The caller polls the rest endpoint for the gates' responses (local-first, then broadcast).")
  @RequestBody(description = "eFTI search request XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @ApiResponse(responseCode = "204", description = "Search registered; poll the rest endpoint")
  @POST("/search/:searchId") fun startSearch(xml: String, @PathParam searchId: UUID) {
    currentThread().name = searchId.toString()
    val responses = PartyResponses()
    pending[searchId] = responses

    AppScope.async {
      val futures = registry.gates.keys.map { gateId ->
        AppScope.async("+$gateId") {
          val response = http.post(eDeliveryUrl + "/api/v1/send/$gateId", xml) {
            header("x-request-id", searchId.toString())
            timeout(62.seconds)
          }
          if (response.statusCode() == 200) {
            val body = response.body()
            if (body.contains("ParameterIDSetCriteria")) responses.xmls.add(body)
          }
        }
      }
      futures.forEach { it.get() }
      responses.complete = true
    }
  }

  @Operation(summary = "Poll gate responses", description = "Returns the gate responses received so far as XML documents joined by '⦀'. Bounded long-poll: if nothing has arrived yet and gates are still responding, waits up to restPollSeconds (MULTIPLEXER_REST_POLL_SECONDS, default 30) for the first one rather than returning empty. Poll until x-poll-more is false. An unknown searchId (or one already drained past its TTL) returns an empty body + x-poll-more:false.")
  @ApiResponse(responseCode = "200", description = "Gate XML responses separated by ⦀, or an empty body when none have arrived", headers = [Header(name = pollMoreHeader, description = "Whether more responses may still arrive", schema = Schema(type = "boolean"))], content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @GET("/rest/:searchId") fun rest(@PathParam searchId: UUID, e: HttpExchange): String {
    val responses = pending[searchId]

    if (responses == null) {
      e.header(pollMoreHeader, "false")
      return ""
    }

    val xmls = mutableListOf<String>()
    if (responses.xmls.isEmpty() && !responses.complete)
      responses.xmls.poll(restPollSeconds, SECONDS)?.let { xmls.add(it) }
    responses.xmls.drainTo(xmls)
    e.sendPollMore(responses)
    return xmls.filter { it.isNotEmpty() }.joinToString("⦀")
  }

  private fun HttpExchange.sendPollMore(responses: PartyResponses) {
    this.header(pollMoreHeader, (!responses.complete).toString())
  }
}

class PartyResponses {
  @Volatile var complete = false
  val xmls = ArrayBlockingQueue<String>(64)
}
