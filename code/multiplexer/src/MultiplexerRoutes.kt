import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import klite.*
import klite.StatusCode.Companion.GatewayTimeout
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

  @Operation(summary = "Fan out a search request", description = "Sends the search request to all registered gates and returns the first available response. Poll the rest endpoint while x-poll-more is true.")
  @RequestBody(description = "eFTI search request XML document", content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @ApiResponse(responseCode = "200", description = "The first matching response as XML", headers = [Header(name = pollMoreHeader, description = "Whether additional responses are available from the rest endpoint", schema = Schema(type = "boolean"))], content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @ApiResponse(responseCode = "504", description = "No gate response arrived before the timeout")
  @POST("/first/:searchId") fun multiplex(xml: String, @PathParam searchId: UUID, e: HttpExchange): String {
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
            val xml = response.body()
            if (xml.contains("ParameterIDSetCriteria")) responses.xmls.add(xml)
          }
        }
      }
      futures.forEach { it.get() }
      responses.xmls.add("")
      responses.complete = true
    }

    return responses.xmls.poll(63, SECONDS)?.also { e.sendPollMore(responses) }
      ?: throw StatusCodeException(GatewayTimeout)
  }

  @Operation(summary = "Poll remaining responses", description = "Returns the remaining gate responses as XML documents separated by ⦀. Repeat while x-poll-more is true.")
  @ApiResponse(responseCode = "200", description = "Remaining XML responses separated by ⦀, or an empty body when none remain", headers = [Header(name = pollMoreHeader, description = "Whether more responses may arrive", schema = Schema(type = "boolean"))], content = [Content(mediaType = MimeTypes.xml, schema = Schema(type = "string"))])
  @GET("/rest/:searchId") fun rest(@PathParam searchId: UUID, e: HttpExchange): String {
    val responses = pending[searchId]

    if (responses == null) {
      e.header(pollMoreHeader, "false")
      return ""
    }

    e.sendPollMore(responses)
    val xmls = mutableListOf<String>()
    responses.xmls.drainTo(xmls)
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
