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
import kotlin.text.RegexOption.DOT_MATCHES_ALL
import kotlin.time.Duration.Companion.seconds

val eDeliveryTimeout = Config.optional("EDELIVERY_TIMEOUT_SECONDS", "60").toInt().seconds

class EDeliveryClient(
  private val asyncResponseProvider: AsyncResponseProvider,
  private val keyManager: KeyManager,
  private val eDeliveryMessageGenerator: EDeliveryMessageGenerator,
  partyRegistry: PartyRegistry,
  private val turvisOutbound: TurvisOutbound,
  private val msInSec: Long = 1000L
) {
  private val log = logger()
  @Volatile private var http = buildHttpClient()

  init {
    // gate/platform TLS certs are baked into the trust store at build time, so a cert rotation
    // or newly-registered gate needs a rebuilt HttpClient, not just a KeyManager cache clear.
    partyRegistry.onChange { rebuildHttpClient() }
  }

  private fun rebuildHttpClient() {
    val old = http
    http = buildHttpClient()
    old.shutdown()
  }

  private fun buildHttpClient() = httpClient { sslContext(keyManager.buildGatesTrustStore()) }

  /**
   * The fault text of a peer's answer, kept only where it is a code of a closed shape: one of our reason codes (`SIG_CRYPTO_FAIL`, ...) or an
   * ebMS error code (`EBMS:0004`). Free text, from a Domibus peer or a hostile one, becomes `unrecognised`.
   */
  internal fun safeFaultReason(answer: String?): String {
    val found = listOfNotNull(errorDetailRegex.from(answer.orEmpty())?.trim(), faultReasonRegex.from(answer.orEmpty())?.trim()).filter { SAFE_REASON.matches(it) }
    return found.joinToString(" - ").ifEmpty { "unrecognised" }
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

  fun send(endpoint: URI, params: UserMessageParams, payload: String): String {
    log.info("Sending message to $endpoint: conversationId=${params.requestKey} payload_size_bytes=${payload.toByteArray().size}")
    // In turvis mode Turvis encrypts and signs and the signed bytes go out untouched; otherwise the in-process path does (legacy, shadow).
    val (soapBytes, attachment) = if (turvisOutbound.authoritative) {
      turvisOutbound.requestMessage(params, payload).let { it.signedSoap to it.encryptedAttachment }
    } else {
      eDeliveryMessageGenerator.requestMessage(params, payload).let { it.first.toByteArray() to it.second }
    }

    val body = ofByteArrays(listOf(
      mimePart1,
      soapBytes,
      mimePart2,
      attachment,
      mimeEnd
    ))

    val url = if (endpoint.path.isEmpty()) endpoint.resolve("/services/msh") else endpoint

    val res = sendWithRetry(url, body)

    val resBody = res.body()
    return if (res.statusCode() in 200..299) {
      EDeliveryMetrics.outboundSuccess()
      log.info("eDelivery accepted message to $endpoint: status=${res.statusCode()}, conversationId=${params.requestKey}")
      resBody
    } else {
      EDeliveryMetrics.outboundFailure()
      // The answer is a peer's, unverified: it may carry anything (our own message echoed, markup, line breaks). Only a bounded code reaches
      // the log and the exception; the answer itself is represented by its size and fingerprint.
      val reason = safeFaultReason(resBody)
      val answerBytes = resBody.orEmpty().toByteArray()
      log.warn("eDelivery SOAP fault from $endpoint: status=${res.statusCode()}, conversationId=${params.requestKey}, reason=$reason, " +
        "body_sha256=${answerBytes.sha256Hex()}, body_size_bytes=${answerBytes.size}")
      throw IOException("eDelivery request failed with status ${res.statusCode()}: $reason")
    }
  }

  fun sendAndReceive(eDeliveryUrl: URI, params: UserMessageParams, payload: String): String {
    asyncResponseProvider.register(params.requestKey)
    var sent = false
    try {
      send(eDeliveryUrl, params, payload)
      sent = true
    } finally {
      if (!sent) asyncResponseProvider.unregister(params.requestKey) // nobody will wait for it: do not leak the waiter
    }
    return asyncResponseProvider.waitForResponse(params.requestKey)
  }

  fun sendWithRetry(url: URI, body: HttpRequest.BodyPublisher): HttpResponse<String> {
    var waitSec = 1L
    val maxAttempts = 5

    repeat(maxAttempts) { attempt ->
      try {
        return http.post(url, body) {
          timeout(eDeliveryTimeout)
          contentType("multipart/related; type=\"$soap\"; boundary=\"$mimeBoundary\"")
        }
      } catch (e: HttpTimeoutException) {
        log.warn("Request timed out on attempt ${attempt + 1} after $eDeliveryTimeout ms")
        throw e
      } catch (e: Exception) {
        log.warn("Attempt ${attempt + 1} failed (${e.message}), retrying after $waitSec sec") // pii-ok: transport error, no payload
        Thread.sleep(waitSec * msInSec)
        waitSec++
      }
    }

    throw IOException("All $maxAttempts attempts failed without hitting timeout")
  }
}

/** A reason code of ours (`SIG_CRYPTO_FAIL`) or an ebMS error code (`EBMS:0004`): the only peer-supplied fault text that is logged. */
private val SAFE_REASON = Regex("(?:[A-Z][A-Z0-9_]{2,63}|EBMS:\\d{4})")
