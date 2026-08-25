import io.swagger.v3.oas.annotations.Operation
import klite.*
import klite.StatusCode.Companion.GatewayTimeout
import klite.annotations.GET
import klite.annotations.POST
import klite.annotations.PathParam
import klite.http.post
import klite.http.timeout
import java.net.URI
import java.net.http.HttpClient
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit.MINUTES
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MultiplexerRoutes(private val registry: GateRegistry, private val http: HttpClient) {
  private val eDeliveryUrl = URI(Config["EDELIVERY_URL"])
  private val pending = Cache<UUID, PartyResponses>(90.seconds)

  @Operation(description = "Multiplex a search request to all gates. Returns first response as XML.")
  @POST("/first/:searchId") fun multiplex(xml: String, @PathParam searchId: UUID): String {
    val responses = PartyResponses()
    pending[searchId] = responses

    AppScope.async {
      val futures = registry.gates.keys.map { gateId ->
        AppScope.async {
          val response = http.post(eDeliveryUrl + "/api/v1/send/$gateId", xml) {
            header("x-request-id", searchId.toString())
            timeout(1.minutes)
          }
          if (response.statusCode() == 200) responses.xmls.add(response.body())
        }
      }
      futures.forEach { it.get() }
      responses.complete = true
    }

    return responses.xmls.poll(1, MINUTES) ?: throw StatusCodeException(GatewayTimeout)
  }

  @Operation(description = "Returns rest of the received responses as XMLs with string delimiter '⦀'. Can be polled.")
  @GET("/rest/:searchId") fun rest(@PathParam searchId: UUID, e: HttpExchange): String {
    val responses = pending[searchId]

    if (responses == null) {
      e.header("complete", "true")
      return ""
    }

    e.header("complete", responses.complete.toString())
    val xmls = mutableListOf<String>()
    responses.xmls.drainTo(xmls)
    return xmls.joinToString("⦀")
  }
}

class PartyResponses {
  @Volatile var complete = false
  val xmls = ArrayBlockingQueue<String>(64)
}
