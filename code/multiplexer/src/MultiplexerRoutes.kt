import io.swagger.v3.oas.annotations.Operation
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

  @Operation(description = "Multiplex a search request to all gates. Returns first response as XML.")
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

  @Operation(description = "Returns rest of the received responses as XMLs with string delimiter '⦀'. Can be polled.")
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
