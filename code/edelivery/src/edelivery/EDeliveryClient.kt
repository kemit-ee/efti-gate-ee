package edelivery

import klite.*
import klite.http.contentType
import klite.http.httpClient
import klite.http.post
import klite.http.timeout
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers.ofByteArrays
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.UUID.randomUUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.text.RegexOption.DOT_MATCHES_ALL
import kotlin.time.Duration.Companion.seconds

val eDeliveryTimeout = Config.optional("EDELIVERY_TIMEOUT_SECONDS", "60").toInt().seconds

class EDeliveryClient(
  private val asyncResponseProvider: AsyncResponseProvider,
  private val keyManager: KeyManager,
  private val eDeliveryMessageGenerator: EDeliveryMessageGenerator,
  private val msInSec: Long = 1000L
) {
  private val statusRegex = """status\s*=\s*"(\d+)"""".toRegex()
  private val log = logger()
  private var http = buildHttpClient()
  private val messagesSent = AtomicLong().also {
    Metrics.register("edelivery_messages_sent") { it.get() }
  }

  fun extractStatus(response: String): StatusCode {
    val status = statusRegex.from(response)!!.toInt()
    return StatusCode(status)
  }

  private fun buildHttpClient() = httpClient { sslContext(keyManager.buildGatesTrustStore()) }.apply {
    try {
      val impl = javaClass.getDeclaredField("impl").apply { isAccessible = true }.get(this)
      fun <T> Any.get(field: String) = javaClass.getDeclaredField(field).apply { isAccessible = true }.get(impl) as T

      Metrics.register("edelivery_client") { mapOf(
        "pendingRequests" to impl.get<Collection<*>>("pendingRequests").size,
        "openedConnections" to impl.get<Collection<*>>("openedConnections").size,
        "pendingOperationCount" to impl.get<AtomicLong>("pendingOperationCount").get(),
      ) }
    } catch (e: Exception) {
      logger<EDeliveryClient>().warn("Could not register metrics: ${e.message}")
    }
  }

  private val faultReasonRegex = Regex("<\\w+:Text[^>]*>(.*?)</\\w+:Text>", DOT_MATCHES_ALL)
  private val errorDetailRegex = Regex("<\\w+:ErrorDetail[^>]*>(.*?)</\\w+:ErrorDetail>", DOT_MATCHES_ALL)

  private val mimeBoundary = "----=_Part_${randomUUID()}"
  private val mimePart1 = ("--$mimeBoundary\r\n" +
    "Content-Type: ${soap}\r\n" +
    "Content-Transfer-Encoding: binary\r\n\r\n").toByteArray()
  private val mimePart2 = ("\r\n--$mimeBoundary\r\n" +
    "Content-Type: ${MimeTypes.binary}\r\n" +
    "Content-Transfer-Encoding: binary\r\n" +
    "Content-Description: Attachment\r\n" +
    "Content-ID: <message>\r\n\r\n").toByteArray()
  private val mimeEnd = ("\r\n--$mimeBoundary--").toByteArray()

  fun ping(party: Party) {
    val pingMessage = "<hello>world</hello>"
    val requestKey = RequestKey(party.id)
    val params = UserMessageParams(
      requestKey,
      action = "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/test",
      service = "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/service",
      serviceType = null
    )
    val response = send(party.eDeliveryUrl, params, pingMessage)
    val regex = Regex("<[^:]*:?RefToMessageId>(.*?)</[^:]*:?RefToMessageId>")
    val refToMessageId = regex.find(response)?.groupValues?.get(1)
    val returnedRequestId = refToMessageId?.split("@")?.first()
    require(returnedRequestId == requestKey.requestId) { "Returned wrong requestId. Expected ${requestKey.requestId}, got $returnedRequestId. Response content: $response" }
  }

  fun send(endpoint: URI, params: UserMessageParams, payload: String): String {
    log.info("Sending message: $payload")
    val message = eDeliveryMessageGenerator.requestMessage(params, payload)

    val body = ofByteArrays(listOf(
      mimePart1,
      message.first.toByteArray(),
      mimePart2,
      message.second,
      mimeEnd
    ))

    val url = if (endpoint.path.isEmpty()) endpoint.resolve("/services/msh") else endpoint

    val res = sendWithRetry(url, body)

    val resBody = res.body()
    messagesSent.incrementAndGet()
    return if (res.statusCode() in 200..299) resBody else {
      val reason = listOfNotNull(errorDetailRegex.from(resBody)?.trim(), faultReasonRegex.from(resBody)?.trim()).joinToString(" - ")
      throw IOException("eDelivery request failed with status ${res.statusCode()}: " + (reason.takeIf { it.isNotBlank() } ?: resBody))
    }
  }

  fun sendAndReceive(eDeliveryUrl: URI, params: UserMessageParams, payload: String): String {
    asyncResponseProvider.register(params.requestKey)
    send(eDeliveryUrl, params, payload)
    return asyncResponseProvider.waitForResponse(params.requestKey)
  }

  fun sendWithRetry(url: URI, body: HttpRequest.BodyPublisher): HttpResponse<String> {
    var waitSec = 1L
    val maxAttempts = 5

    repeat(maxAttempts) { attempt ->
      try {
        return http.post(url, body) {
          timeout(eDeliveryTimeout)
          contentType("multipart/related; type=\"${soap}\"; boundary=\"$mimeBoundary\"")
        }
      } catch (e: HttpTimeoutException) {
        log.warn("Request timed out on attempt ${attempt + 1} after $eDeliveryTimeout ms")
        throw e
      } catch (e: Exception) {
        log.warn("Attempt ${attempt + 1} failed (${e.message}), retrying after $waitSec sec")
        Thread.sleep(waitSec * msInSec)
        waitSec++
      }
    }

    throw IOException("All $maxAttempts attempts failed without hitting timeout")
  }
}
