package edelivery

/**
 * The reason codes of the Turvis contract (docs/specs/turvis/SPEC.md section 5) with the HTTP status, the ebMS
 * fault code the peer is told on the inbound path, and the exception class of section 13.2a.
 *
 * [ebmsCode] is null for reasons that are not the peer's doing (internal, outbound-only, transient): on the
 * inbound path they are answered with HTTP 503, without an ebMS fault (SPEC 13.2).
 * [transient] reasons are the only ones the client retries (SPEC 13.3). [alert] reasons are bugs on our side
 * or Turvis's: the client logs them at error level.
 */
enum class TurvisReason(
  val code: String,
  val httpStatus: Int,
  val ebmsCode: String? = null,
  val ebmsShortDescription: String? = null,
  val transient: Boolean = false,
  val alert: Boolean = false,
  private val create: (TurvisRejection) -> TurvisRejectException,
) {
  XML_PARSE_FAIL("XML_PARSE_FAIL", 422, "EBMS:0004", "InvalidHeader", create = ::MalformedEnvelopeException),
  XML_ATTACK_DETECTED("XML_ATTACK_DETECTED", 422, "EBMS:0004", "InvalidHeader", create = ::HostileInputException),
  SIG_MISSING("SIG_MISSING", 422, "EBMS:0101", "SignatureFailure", create = ::SignatureMissingException),
  SIG_CRYPTO_FAIL("SIG_CRYPTO_FAIL", 422, "EBMS:0101", "SignatureFailure", create = ::SignatureInvalidException),
  SIG_POLICY_FAIL("SIG_POLICY_FAIL", 422, "EBMS:0101", "SignatureFailure", create = ::SignaturePolicyException),
  SIG_ALGO_UNSUPPORTED("SIG_ALGO_UNSUPPORTED", 422, "EBMS:0101", "SignatureFailure", create = ::SignatureAlgoException),
  SIGNER_UNTRUSTED("SIGNER_UNTRUSTED", 422, "EBMS:0101", "SignatureFailure", create = ::SignerUntrustedException),
  SIGNER_IDENTITY_MISMATCH("SIGNER_IDENTITY_MISMATCH", 422, "EBMS:0101", "SignatureFailure", create = ::SignerIdentityMismatchException),
  SIG_BUILD_FAIL("SIG_BUILD_FAIL", 422, create = ::OutboundSignBuildException),
  KEY_HANDLE_UNKNOWN("KEY_HANDLE_UNKNOWN", 422, create = ::KeyHandleUnknownException),
  KEY_UNAVAILABLE("KEY_UNAVAILABLE", 503, transient = true, create = ::KeyUnavailableException),
  WRAPPED_KEY_MALFORMED("WRAPPED_KEY_MALFORMED", 422, "EBMS:0102", "DecryptionFailure", create = ::WrappedKeyException),
  SKI_MISMATCH("SKI_MISMATCH", 422, "EBMS:0102", "DecryptionFailure", create = ::RecipientMismatchException),
  AES_UNWRAP_FAIL("AES_UNWRAP_FAIL", 422, "EBMS:0102", "DecryptionFailure", create = ::KeyUnwrapException),
  PAYLOAD_DECRYPT_FAIL("PAYLOAD_DECRYPT_FAIL", 422, "EBMS:0102", "DecryptionFailure", create = ::PayloadDecryptException),
  RECEIVER_UNKNOWN("RECEIVER_UNKNOWN", 422, create = ::ReceiverUnknownException),
  RECEIVER_CERT_EXPIRED("RECEIVER_CERT_EXPIRED", 422, create = ::ReceiverCertExpiredException),
  RECEIVER_CERT_UNTRUSTED("RECEIVER_CERT_UNTRUSTED", 422, create = ::ReceiverCertUntrustedException),
  PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE", 413, "EBMS:0003", "ValueInconsistent", create = ::TurvisPayloadTooLargeException),
  UNKNOWN_RETURN_FIELD("UNKNOWN_RETURN_FIELD", 422, alert = true, create = ::UnknownReturnFieldException),
  ALGO_UNSUPPORTED("ALGO_UNSUPPORTED", 422, "EBMS:0003", "ValueInconsistent", create = ::AlgoUnsupportedException),
  TRUST_STORE_UNAVAILABLE("TRUST_STORE_UNAVAILABLE", 503, transient = true, create = ::TrustStoreUnavailableException),
  TURVIS_INTERNAL("TURVIS_INTERNAL", 500, alert = true, create = ::TurvisInternalException);

  /** True when the inbound path answers with an ebMS fault; false means HTTP 503 and no fault (SPEC 13.2). */
  val answeredWithFault get() = ebmsCode != null

  fun exception(rejection: TurvisRejection): TurvisRejectException = create(rejection)

  companion object {
    private val byCode = entries.associateBy { it.code }
    fun of(code: String?): TurvisReason? = code?.let { byCode[it] }
  }
}

/**
 * A reject response of Turvis (HTTP 4xx/5xx with `reason_code`): an expected, handled outcome, not an error of
 * the call itself. [reasonCode] is the wire value; [reason] is null for a code this build does not know.
 */
data class TurvisRejection(
  val endpoint: String,
  val httpStatus: Int,
  val reasonCode: String,
  val auditRef: String?,
  val detail: String?,
) {
  /** Without [detail]: Turvis's text must not reach a log line by logging the rejection. */
  override fun toString() = "TurvisRejection(endpoint=$endpoint, httpStatus=$httpStatus, reasonCode=$reasonCode, auditRef=$auditRef)"

  val reason: TurvisReason? get() = TurvisReason.of(reasonCode)

  /**
   * The reason to map to a fault. An unknown code fails closed as SIG_CRYPTO_FAIL (SPEC section 5: "Unknown
   * codes = treat as SIG_CRYPTO_FAIL equivalent (reject)").
   */
  val effectiveReason: TurvisReason get() = reason ?: TurvisReason.SIG_CRYPTO_FAIL

  fun toException(): TurvisRejectException = reason?.exception(this) ?: UnrecognisedRejectException(this)
}

/** Base of the section 13.2a exceptions. The message carries only the reason code, endpoint and audit_ref, never Turvis's detail text or any payload. */
sealed class TurvisRejectException(val rejection: TurvisRejection) :
  RuntimeException("Turvis rejected ${rejection.endpoint}: ${rejection.reasonCode} (audit_ref=${rejection.auditRef})") {
  val reasonCode get() = rejection.reasonCode
  val auditRef get() = rejection.auditRef
}

class MalformedEnvelopeException(r: TurvisRejection) : TurvisRejectException(r)
class HostileInputException(r: TurvisRejection) : TurvisRejectException(r)
class SignatureMissingException(r: TurvisRejection) : TurvisRejectException(r)
class SignatureInvalidException(r: TurvisRejection) : TurvisRejectException(r)
class SignaturePolicyException(r: TurvisRejection) : TurvisRejectException(r)
class SignatureAlgoException(r: TurvisRejection) : TurvisRejectException(r)
class SignerUntrustedException(r: TurvisRejection) : TurvisRejectException(r)
class SignerIdentityMismatchException(r: TurvisRejection) : TurvisRejectException(r)
class OutboundSignBuildException(r: TurvisRejection) : TurvisRejectException(r)
class KeyHandleUnknownException(r: TurvisRejection) : TurvisRejectException(r)
class KeyUnavailableException(r: TurvisRejection) : TurvisRejectException(r)
class WrappedKeyException(r: TurvisRejection) : TurvisRejectException(r)
class RecipientMismatchException(r: TurvisRejection) : TurvisRejectException(r)
class KeyUnwrapException(r: TurvisRejection) : TurvisRejectException(r)
class PayloadDecryptException(r: TurvisRejection) : TurvisRejectException(r)
class ReceiverUnknownException(r: TurvisRejection) : TurvisRejectException(r)
class ReceiverCertExpiredException(r: TurvisRejection) : TurvisRejectException(r)
class ReceiverCertUntrustedException(r: TurvisRejection) : TurvisRejectException(r)
class TurvisPayloadTooLargeException(r: TurvisRejection) : TurvisRejectException(r)
/** ALERT: edelivery sent a `return_fields` value Turvis does not know; this is a code bug. */
class UnknownReturnFieldException(r: TurvisRejection) : TurvisRejectException(r)
class AlgoUnsupportedException(r: TurvisRejection) : TurvisRejectException(r)
class TrustStoreUnavailableException(r: TurvisRejection) : TurvisRejectException(r)
/** ALERT: internal Turvis bug. */
class TurvisInternalException(r: TurvisRejection) : TurvisRejectException(r)
/** A reason code this build does not know; handled as SIG_CRYPTO_FAIL ([TurvisRejection.effectiveReason]). */
class UnrecognisedRejectException(r: TurvisRejection) : TurvisRejectException(r)

/** Turvis could not be reached or did not answer within the timeout, after the retries the contract allows. */
class TurvisUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Turvis answered, but not in the contract's shape (also: a missing or unexpected field in a 200). Fail closed, never retried. */
class TurvisProtocolException(message: String) : RuntimeException(message)

/** Turvis refused our credential (HTTP 401/403): a configuration problem, never retried. ALERT. */
class TurvisAuthException(val httpStatus: Int, val endpoint: String) :
  RuntimeException("Turvis refused the credential on $endpoint (HTTP $httpStatus)")
