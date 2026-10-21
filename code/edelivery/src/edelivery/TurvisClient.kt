package edelivery

import klite.info
import klite.json.JsonMapper
import klite.json.parse
import klite.logger
import klite.warn
import klite.error
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** An attachment as the contract carries it: the Content-ID and the base64 of the bytes. */
data class TurvisAttachment(val cid: String, val bytesB64: String)

data class TurvisSigner(val certSubjectDn: String, val certSki: String, val certSerial: String, val verifiedIdentity: String)

/** One signature reference: the original URI, digest, algorithm and transforms (SPEC 4.1/4.2), enough to build a receipt. */
data class TurvisSignatureReference(
  val referenceUri: String,
  val subtreeXpath: String?,
  val digestB64: String,
  val algorithm: String,
  val transforms: List<String>,
)

data class TurvisEbmsRouting(
  val messageId: String,
  val conversationId: String?,
  val refToMessageId: String?,
  val fromPartyId: String,
  val toPartyId: String,
  val service: String,
  val action: String,
)

/** Fields are null unless named in the request's `return_fields` (SPEC 4.0). */
data class TurvisDecryptResponse(
  val auditRef: String,
  val decryptedSoapXmlB64: String?,
  val decryptedAttachmentB64: String?,
  /** DIAGNOSTIC ONLY (SPEC 4.4): extracted from bytes whose signature is not verified yet; must never authorise dispatch. */
  val ebmsRouting: TurvisEbmsRouting?,
)

data class TurvisVerifyResponse(
  val auditRef: String,
  val signer: TurvisSigner?,
  val matchedSignedXpaths: List<String>?,
  val matchedSignedCidRefs: List<String>?,
  val signatureDigests: List<TurvisSignatureReference>?,
  /** Authoritative routing: taken from the exact `eb:Messaging` element covered by the verified signature. */
  val ebmsRouting: TurvisEbmsRouting?,
)

data class TurvisEncryptResponse(
  val auditRef: String,
  val encryptedPayloadB64: String,
  val wrappedKeyB64: String?,
  val wrappedKeyXml: String?,
  val encryptedDataXml: String?,
)

data class TurvisSignResponse(
  val auditRef: String,
  /** The signed envelope, to be sent to the peer AS-IS: no re-serialisation, no canonicalisation (SPEC 9.1). */
  val signedXmlB64: String,
  val signatures: List<TurvisSignatureReference>?,
)

enum class TurvisDecryptField(val wire: String) { DECRYPTED_SOAP_XML_B64("decrypted_soap_xml_b64"), DECRYPTED_ATTACHMENT_B64("decrypted_attachment_b64"), EBMS_ROUTING("ebms_routing") }
enum class TurvisVerifyField(val wire: String) { SIGNER("signer"), MATCHED_SIGNED_XPATHS("matched_signed_xpaths"), MATCHED_SIGNED_CID_REFS("matched_signed_cid_refs"), SIGNATURE_DIGESTS("signature_digests"), EBMS_ROUTING("ebms_routing") }
enum class TurvisEncryptField(val wire: String) { WRAPPED_KEY_XML("wrapped_key_xml"), ENCRYPTED_DATA_XML("encrypted_data_xml"), WRAPPED_KEY_B64("wrapped_key_b64") }
enum class TurvisSignField(val wire: String) { SIGNATURES("signatures") }
enum class TurvisEncryptProfile(val wire: String) { WSS_ENCRYPTED("wss-encrypted"), PLAINTEXT("plaintext") }

sealed interface TurvisResult<out T> {
  data class Accept<T>(val value: T) : TurvisResult<T>
  /** A reject is an expected outcome (SPEC 13.2), not an exception; map it with [TurvisRejection.toException] or [TurvisRejection.effectiveReason]. */
  data class Reject(val rejection: TurvisRejection) : TurvisResult<Nothing>
}

fun <T> TurvisResult<T>.orThrow(): T = when (this) {
  is TurvisResult.Accept -> value
  is TurvisResult.Reject -> throw rejection.toException()
}

/**
 * HTTP client for the four Turvis operations (docs/specs/turvis/SPEC.md sections 4 and 13): decrypt, verify,
 * encrypt, sign. It sends the as-received bytes base64-encoded, declares `return_fields` per call, and
 * returns only what was asked for.
 *
 * Contract points enforced here:
 * - the dedicated credential (`TURVIS_INTERNAL_TOKEN`) in `X-Internal-Service-Token`, `key_handle` = own gate id;
 * - 10 s per call; at most 2 retries (100 ms, then 300 ms) and only where SPEC 13.3 allows it: a 503 reject
 *   carrying KEY_UNAVAILABLE or TRUST_STORE_UNAVAILABLE, and, for the idempotent endpoints (verify, decrypt,
 *   sign, encrypt in the plaintext profile), a network error or timeout. Encrypt in `wss-encrypted` is never
 *   retried after a network error or timeout (the first call may have produced a key). Every other reject,
 *   including TURVIS_INTERNAL, is final;
 * - a 200 is accepted only if it has `audit_ref`, every requested field and nothing that was not requested
 *   (response-stuffing defence, SPEC 4.0); anything else is a [TurvisProtocolException];
 * - logs carry endpoint, status, reason code and `audit_ref`, never a body, token or payload.
 *
 * Not wired into any call site yet (#305a): the integration mode defaults to legacy.
 */
class TurvisClient(
  private val config: TurvisConfig,
  private val http: HttpClient,
  jsonMapper: JsonMapper,
  private val sleeper: (Long) -> Unit = Thread::sleep,
  private val newTraceId: () -> String = ::randomTraceId,
  private val timeout: Duration = Duration.ofSeconds(10),
) {
  private val log = logger()

  // The contract gives empty strings a meaning (an empty XML fragment in the plaintext profile, an empty
  // ref_to_message_id, an empty attachment) and signed bytes must come back exactly as sent, so the shared
  // mapper's trimToNull (which turns "" into null and trims whitespace) must not apply to Turvis payloads.
  private val jsonMapper = jsonMapper.copy(trimToNull = false)

  fun decrypt(
    soapXmlB64: String,
    encryptedAttachmentB64: String? = null,
    returnFields: Set<TurvisDecryptField>,
    traceId: String = newTraceId(),
  ): TurvisResult<TurvisDecryptResponse> {
    val request = buildMap<String, Any?> {
      put("soap_xml_b64", soapXmlB64)
      encryptedAttachmentB64?.let { put("encrypted_attachment_b64", it) }
      put("key_handle", config.keyHandle)
      put("return_fields", returnFields.map { it.wire })
      put("trace_id", traceId)
    }
    return call(Endpoint.DECRYPT, request, returnFields.map { it.wire }, idempotent = true) { m ->
      TurvisDecryptResponse(
        m.string("audit_ref")!!,
        m.string("decrypted_soap_xml_b64", nonEmpty = true),
        m.string("decrypted_attachment_b64"),
        m.obj("ebms_routing")?.let(::routing),
      )
    }
  }

  fun verify(
    soapXmlB64: String,
    attachments: List<TurvisAttachment>,
    requiredSignedXpaths: List<String>,
    requiredSignedCidRefs: List<String>,
    returnFields: Set<TurvisVerifyField>,
    claimedSignerId: String? = null,
    trustStoreVersion: String? = null,
    traceId: String = newTraceId(),
  ): TurvisResult<TurvisVerifyResponse> {
    val request = buildMap<String, Any?> {
      put("soap_xml_b64", soapXmlB64)
      put("attachments", attachments.map { mapOf("cid" to it.cid, "bytes_b64" to it.bytesB64) })
      put("required_signed_xpaths", requiredSignedXpaths)
      put("required_signed_cid_refs", requiredSignedCidRefs)
      put("return_fields", returnFields.map { it.wire })
      claimedSignerId?.let { put("claimed_signer_id", it) }
      trustStoreVersion?.let { put("trust_store_version", it) }
      put("trace_id", traceId)
    }
    return call(Endpoint.VERIFY, request, returnFields.map { it.wire }, idempotent = true) { m ->
      if (m.string("verdict") != "accept") throw TurvisProtocolException("Turvis verifications answered 200 without verdict=accept")
      TurvisVerifyResponse(
        m.string("audit_ref")!!,
        m.obj("signer")?.let {
          // verified_identity is the authoritative sender (SPEC 13.9), so an empty value is a protocol error, not an identity.
          TurvisSigner(
            it.string("cert_subject_dn", true, nonEmpty = true)!!, it.string("cert_ski", true, nonEmpty = true)!!,
            it.string("cert_serial", true, nonEmpty = true)!!, it.string("verified_identity", true, nonEmpty = true)!!,
          )
        },
        m.strings("matched_signed_xpaths"),
        m.strings("matched_signed_cid_refs"),
        m.objects("signature_digests")?.map(::verifyReference),
        m.obj("ebms_routing")?.let(::routing),
      )
    }
  }

  fun encrypt(
    plaintextBytesB64: String,
    receiverPartyId: String,
    profile: TurvisEncryptProfile,
    returnFields: Set<TurvisEncryptField>,
    traceId: String = newTraceId(),
  ): TurvisResult<TurvisEncryptResponse> {
    val request = buildMap<String, Any?> {
      put("plaintext_bytes_b64", plaintextBytesB64)
      put("receiver_party_id", receiverPartyId)
      put("profile", profile.wire)
      put("return_fields", returnFields.map { it.wire })
      put("trace_id", traceId)
    }
    return call(Endpoint.ENCRYPT, request, returnFields.map { it.wire }, idempotent = profile == TurvisEncryptProfile.PLAINTEXT) { m ->
      TurvisEncryptResponse(
        m.string("audit_ref")!!,
        m.string("encrypted_payload_b64", true, nonEmpty = true)!!,
        m.string("wrapped_key_b64"),
        m.string("wrapped_key_xml"),
        m.string("encrypted_data_xml"),
      )
    }
  }

  fun sign(
    soapXmlB64: String,
    signXpaths: List<String>,
    signCidRefs: List<String>,
    returnFields: Set<TurvisSignField>,
    attachmentBytes: List<TurvisAttachment> = emptyList(),
    traceId: String = newTraceId(),
  ): TurvisResult<TurvisSignResponse> {
    val request = buildMap<String, Any?> {
      put("soap_xml_b64", soapXmlB64)
      put("sign_xpaths", signXpaths)
      put("sign_cid_refs", signCidRefs)
      put("key_handle", config.keyHandle)
      put("return_fields", returnFields.map { it.wire })
      if (attachmentBytes.isNotEmpty()) put("attachment_bytes_b64", attachmentBytes.map { mapOf("cid" to it.cid, "bytes_b64" to it.bytesB64) })
      put("trace_id", traceId)
    }
    return call(Endpoint.SIGN, request, returnFields.map { it.wire }, idempotent = true) { m ->
      TurvisSignResponse(m.string("audit_ref")!!, m.string("signed_xml_b64", true, nonEmpty = true)!!, m.objects("signatures")?.map(::signReference))
    }
  }

  private enum class Endpoint(val metricName: String, val path: String, val alwaysReturned: Set<String>) {
    DECRYPT("decryptions", "/turvis/v1/decryptions/xmlenc", setOf("audit_ref")),
    VERIFY("verifications", "/turvis/v1/verifications/xmldsig", setOf("verdict", "audit_ref")),
    ENCRYPT("encryptions", "/turvis/v1/encryptions/xmlenc", setOf("encrypted_payload_b64", "audit_ref")),
    SIGN("signatures", "/turvis/v1/signatures/xmldsig", setOf("signed_xml_b64", "audit_ref")),
  }

  private sealed interface Attempt {
    data class Answered(val response: HttpResponse<String>) : Attempt
    data class Failed(val error: IOException) : Attempt
  }

  private fun <T> call(endpoint: Endpoint, request: Map<String, Any?>, requested: List<String>, idempotent: Boolean, parse: (Map<String, Any?>) -> T): TurvisResult<T> {
    val body = jsonMapper.render(request)
    var attempt = 0
    while (true) {
      attempt++
      val outcome = send(endpoint, body)
      val retryable = attempt <= RETRY_BACKOFF_MS.size && when (outcome) {
        is Attempt.Failed -> idempotent
        is Attempt.Answered -> outcome.response.statusCode() == 503 && rejection(endpoint, outcome.response)?.reason?.transient == true
      }
      if (retryable) {
        EDeliveryMetrics.turvisRetry(endpoint.metricName)
        log.warn("Turvis ${endpoint.metricName}: attempt $attempt failed, retrying") // pii-ok: no payload, endpoint and attempt number only
        sleeper(RETRY_BACKOFF_MS[attempt - 1])
        continue
      }
      return when (outcome) {
        is Attempt.Failed -> {
          EDeliveryMetrics.turvisCall(endpoint.metricName, "error")
          log.warn("Turvis ${endpoint.metricName}: unreachable after $attempt attempt(s)") // pii-ok: no payload
          throw TurvisUnavailableException("Turvis ${endpoint.metricName} unreachable after $attempt attempt(s)", outcome.error)
        }
        is Attempt.Answered -> finish(endpoint, outcome.response, requested, parse)
      }
    }
  }

  /**
   * One HTTP exchange bounded by [timeout] **as a whole**, response body included: `HttpRequest.timeout` only
   * covers the wait for the response headers, so a Turvis that sends headers and then stalls would hold the
   * thread forever. The deadline is on the future; on expiry it is cancelled (which aborts the exchange) and
   * counted as a timeout, i.e. an IOException like [HttpTimeoutException].
   */
  private fun send(endpoint: Endpoint, body: String): Attempt {
    val req = HttpRequest.newBuilder(config.baseUrl.resolveTurvis(endpoint.path))
      .timeout(timeout)
      .header("Content-Type", "application/json")
      .header("X-Internal-Service-Token", config.internalToken)
      .POST(BodyPublishers.ofString(body))
      .build()
    val future = http.sendAsync(req, BodyHandlers.ofString())
    return try {
      Attempt.Answered(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS))
    } catch (e: TimeoutException) {
      future.cancel(true)
      Attempt.Failed(HttpTimeoutException("no complete response from Turvis within $timeout"))
    } catch (e: ExecutionException) {
      val cause = (e.cause as? CompletionException)?.cause ?: e.cause
      if (cause is IOException) Attempt.Failed(cause) else throw cause ?: e
    } catch (e: InterruptedException) {
      future.cancel(true)
      Thread.currentThread().interrupt()
      throw e
    }
  }

  private fun <T> finish(endpoint: Endpoint, response: HttpResponse<String>, requested: List<String>, parse: (Map<String, Any?>) -> T): TurvisResult<T> {
    val status = response.statusCode()
    if (status == 401 || status == 403) {
      EDeliveryMetrics.turvisCall(endpoint.metricName, "error")
      log.error("Turvis ${endpoint.metricName}: credential refused (HTTP $status), check TURVIS_INTERNAL_TOKEN") // pii-ok: no payload
      throw TurvisAuthException(status, endpoint.metricName)
    }
    val rejection = rejection(endpoint, response)
    if (status == 200) {
      val map = parseObject(response.body()) ?: protocolError(endpoint, "answered 200 with a body that is not a JSON object")
      return try {
        val value = validated(endpoint, map, requested, parse)
        EDeliveryMetrics.turvisCall(endpoint.metricName, "accept")
        log.info("Turvis ${endpoint.metricName}: accept audit_ref=${map["audit_ref"]}")
        TurvisResult.Accept(value)
      } catch (e: TurvisProtocolException) {
        EDeliveryMetrics.turvisCall(endpoint.metricName, "error")
        log.warn("Turvis ${endpoint.metricName}: invalid 200 response: ${e.message}") // pii-ok: message names a field, never a value
        throw e
      }
    }
    if (status in 201..299) protocolError(endpoint, "answered HTTP $status, the contract only has 200")
    if (rejection == null) protocolError(endpoint, "answered HTTP $status without a reason_code")
    EDeliveryMetrics.turvisCall(endpoint.metricName, "reject")
    // Bounded cardinality: only the codes of the contract get their own counter, anything else is one bucket.
    val known = rejection.reason?.code ?: "unrecognised"
    EDeliveryMetrics.turvisReject(endpoint.metricName, known)
    val line = "Turvis ${endpoint.metricName}: reject reason_code=$known http=$status audit_ref=${rejection.auditRef}"
    if (rejection.reason?.alert != false) log.error("$line (ALERT)") else log.warn(line) // pii-ok: reason code and audit_ref only
    return TurvisResult.Reject(rejection)
  }

  private fun protocolError(endpoint: Endpoint, what: String): Nothing {
    EDeliveryMetrics.turvisCall(endpoint.metricName, "error")
    log.warn("Turvis ${endpoint.metricName}: $what") // pii-ok: no payload
    throw TurvisProtocolException("Turvis ${endpoint.metricName} $what")
  }

  private fun <T> validated(endpoint: Endpoint, map: Map<String, Any?>, requested: List<String>, parse: (Map<String, Any?>) -> T): T {
    val allowed = endpoint.alwaysReturned + requested
    map.keys.firstOrNull { it !in allowed }?.let { throw TurvisProtocolException("response contains the unrequested field '$it'") }
    // Present is not enough: a requested field that is null is as missing as an absent one.
    (endpoint.alwaysReturned + requested).firstOrNull { map[it] == null }?.let { throw TurvisProtocolException("response lacks the field '$it' (absent or null)") }
    if (!UUID_FORMAT.matches(map.string("audit_ref")!!)) throw TurvisProtocolException("audit_ref is not a UUID")
    return parse(map)
  }

  /** A reject body: a JSON object with a `reason_code` (RFC 7807 problem+json with the Turvis extensions). */
  private fun rejection(endpoint: Endpoint, response: HttpResponse<String>): TurvisRejection? {
    if (response.statusCode() == 200) return null
    val map = parseObject(response.body()) ?: return null
    // A well-formed code this build does not know is still a reject (fail closed); anything else is not a reject body.
    val code = map.string("reason_code")?.takeIf { REASON_CODE.matches(it) } ?: return null
    // audit_ref goes into logs and the exception message: only a UUID is kept, so no peer-controlled text reaches a log line.
    val auditRef = (map["audit_ref"] as? String)?.takeIf { UUID_FORMAT.matches(it) }
    return TurvisRejection(endpoint.metricName, response.statusCode(), code, auditRef, map["detail"] as? String)
  }

  private fun parseObject(body: String?): Map<String, Any?>? =
    try { body?.takeIf { it.isNotBlank() }?.let { jsonMapper.parse<Map<String, Any?>>(it) } } catch (e: Exception) { null }

  /** The five fields the contract requires must be present and non-empty; conversation and ref-to may be empty. */
  private fun routing(m: Map<String, Any?>) = TurvisEbmsRouting(
    m.string("message_id", true, nonEmpty = true)!!, m.string("conversation_id"), m.string("ref_to_message_id"),
    m.string("from_party_id", true, nonEmpty = true)!!, m.string("to_party_id", true, nonEmpty = true)!!,
    m.string("service", true, nonEmpty = true)!!, m.string("action", true, nonEmpty = true)!!,
  )

  /**
   * A verify reference feeds the receipt, so it is strict: URI, digest and transforms are required, and the
   * algorithm must be SHA-256 (SPEC 4.1). A sign reference is metadata only (SPEC 4.2 requires nothing).
   */
  private fun verifyReference(m: Map<String, Any?>): TurvisSignatureReference {
    val algorithm = m.string("algorithm", true, nonEmpty = true)!!
    if (algorithm != "SHA-256") throw TurvisProtocolException("signature digest algorithm is '$algorithm', the contract only allows SHA-256")
    return TurvisSignatureReference(
      m.string("reference_uri", true, nonEmpty = true)!!, m.string("subtree_xpath"), m.string("digest_b64", true, nonEmpty = true)!!, algorithm,
      m.strings("transforms") ?: throw TurvisProtocolException("response lacks the field 'transforms'"),
    )
  }

  private fun signReference(m: Map<String, Any?>) = TurvisSignatureReference(
    m.string("reference_uri") ?: "", m.string("subtree_xpath"), m.string("digest_b64") ?: "", m.string("algorithm") ?: "", m.strings("transforms") ?: emptyList(),
  )

  private fun Map<String, Any?>.string(key: String, required: Boolean = false, nonEmpty: Boolean = false): String? {
    val v = this[key]
    if (v == null) { if (required) throw TurvisProtocolException("response lacks the field '$key'"); return null }
    val s = v as? String ?: throw TurvisProtocolException("field '$key' is not a string")
    if (nonEmpty && s.isEmpty()) throw TurvisProtocolException("field '$key' is empty")
    return s
  }

  private fun Map<String, Any?>.strings(key: String): List<String>? {
    val v = this[key] ?: return null
    return (v as? List<*>)?.map { it as? String ?: throw TurvisProtocolException("field '$key' holds a non-string") } ?: throw TurvisProtocolException("field '$key' is not an array")
  }

  @Suppress("UNCHECKED_CAST")
  private fun Map<String, Any?>.obj(key: String): Map<String, Any?>? {
    val v = this[key] ?: return null
    return v as? Map<String, Any?> ?: throw TurvisProtocolException("field '$key' is not an object")
  }

  @Suppress("UNCHECKED_CAST")
  private fun Map<String, Any?>.objects(key: String): List<Map<String, Any?>>? {
    val v = this[key] ?: return null
    return (v as? List<*>)?.map { it as? Map<String, Any?> ?: throw TurvisProtocolException("field '$key' holds a non-object") } ?: throw TurvisProtocolException("field '$key' is not an array")
  }

  private fun URI.resolveTurvis(path: String): URI = URI(toString().trimEnd('/') + path)

  companion object {
    /** SPEC 13.3: at most 2 retries, 100 ms then 300 ms. */
    val RETRY_BACKOFF_MS = listOf(100L, 300L)
    private val REASON_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val UUID_FORMAT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
  }
}

private val secureRandom = SecureRandom()

/** 32 lower-case hex characters, the `trace_id` format of the contract (a W3C trace id). */
fun randomTraceId(): String = ByteArray(16).also(secureRandom::nextBytes).joinToString("") { "%02x".format(it) }
