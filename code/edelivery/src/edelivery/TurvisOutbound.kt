package edelivery

import klite.base64Decode
import klite.base64Encode
import klite.logger
import klite.warn

/** A request message ready for the wire: the signed envelope exactly as Turvis returned it, and the encrypted attachment. */
class OutboundMessage(
  /** Sent to the peer AS-IS: no re-serialisation, no canonicalisation, no whitespace touch-up (SPEC 9.1). */
  val signedSoap: ByteArray,
  val encryptedAttachment: ByteArray,
  val encryptAuditRef: String,
  val signAuditRef: String,
)

/**
 * The outbound AS4 request when Turvis is authoritative (`TURVIS_INTEGRATION_MODE=turvis`), SPEC 2.3 and 13.3a:
 * compress the payload, `encrypt` it for the receiver, splice the returned fragments into the unsigned envelope,
 * `sign` that envelope, and hand back the signed bytes untouched. No cryptography runs here.
 *
 * Invariants (each has a test):
 * - the signed envelope is returned byte for byte as Turvis produced it;
 * - the `cid:message` digest covers gzip(plaintext) (SPEC 9.1a): the same bytes are encrypted and passed to sign as
 *   `attachment_bytes_b64`, while the wire attachment is the encrypted payload;
 * - the plaintext and the encrypted profile run the same code (an empty fragment splices to nothing);
 * - a failure is never swallowed: it is counted (`edelivery_outbound_turvis_failures_total_<reason>`), logged with
 *   the `audit_ref` only, and rethrown so the caller sees the message was not sent (SPEC 13.3a). An encrypt is not
 *   retried here: the client retries only what SPEC 13.3 allows.
 */
class TurvisOutbound(
  private val config: TurvisConfig,
  private val client: TurvisClient,
  private val generator: EDeliveryMessageGenerator,
) {
  private val log = logger()

  /**
   * Only the dev/CI turvis-mock (no cryptography) echoes its input unsigned; the start-up guard keeps that host out of
   * prod/staging. A property, not a constructor parameter: klite's registry cannot inject a Boolean.
   */
  internal var allowUnsignedEcho: Boolean = TurvisConfig.isMockHost(config.url?.host)

  /** Whether Turvis is the authority for outbound messages. In legacy and shadow the in-process path is. */
  val authoritative get() = config.mode == TurvisIntegrationMode.TURVIS

  /** The production entry point: the profile is always `wss-encrypted`; there is no way to ask for plaintext from outside. */
  fun requestMessage(params: UserMessageParams, payload: String): OutboundMessage = requestMessage(params, payload, TurvisEncryptProfile.WSS_ENCRYPTED)

  internal fun requestMessage(params: UserMessageParams, payload: String, profile: TurvisEncryptProfile): OutboundMessage =
    try {
      val compressed = generator.gzip(payload.toByteArray())
      val compressedB64 = compressed.base64Encode()

      val encrypted = client.encrypt(compressedB64, params.requestKey.receiverId.toString(), profile, ENCRYPT_FIELDS, randomTraceId()).orThrow()
      val unsigned = generator.unsignedRequestEnvelope(params, encrypted.wrappedKeyXml.orEmpty() + encrypted.encryptedDataXml.orEmpty())

      val signed = client.sign(
        unsigned.toByteArray().base64Encode(), SIGNED_XPATHS, SIGNED_CID_REFS, emptySet(), listOf(TurvisAttachment(ATTACHMENT_CID, compressedB64)), randomTraceId(),
      ).orThrow()

      val signedSoap = signed.signedXmlB64.base64Decode()
      requireSignatureOnlyInserted(unsigned.toByteArray(), signedSoap)
      OutboundMessage(signedSoap, encrypted.encryptedPayloadB64.base64Decode(), encrypted.auditRef, signed.auditRef)
    } catch (ex: Exception) {
      val reason = failureReason(ex)
      EDeliveryMetrics.outboundTurvisFailure(reason)
      log.warn("Outbound message not sent, Turvis failed: conversation=${params.requestKey.requestId} reason=$reason audit_ref=${(ex as? TurvisRejectException)?.auditRef}", ex.withoutMessages())
      throw ex
    }

  /**
   * Cheap byte guard, no XML parsing: the signed envelope must be the unsigned one plus one `Signature` element
   * inserted as the last child of the `wsse:Security` header. This is the shape the legacy path writes (key, data,
   * signature) and the one a peer reads in document order; anything else (a signature in front of the encryption
   * block, a changed `eb:Messaging` or body byte) would fail on the peer, so it is refused here instead.
   * The position is fixed by SPEC 4.2 "Signature placement". An answer identical to the input is let through only for the turvis-mock host ([allowUnsignedEcho]).
   */
  internal fun requireSignatureOnlyInserted(unsigned: ByteArray, signed: ByteArray) {
    if (signed.contentEquals(unsigned)) {
      if (!allowUnsignedEcho) throw TurvisProtocolException("sign returned the envelope unchanged")
      return
    }
    val at = unsigned.lastIndexOf(SECURITY_CLOSE)
    val tail = unsigned.size - at
    val inserted = signed.size - unsigned.size
    val ok = at >= 0 && inserted > 0 &&
      signed.copyOfRange(0, at).contentEquals(unsigned.copyOfRange(0, at)) &&
      signed.copyOfRange(signed.size - tail, signed.size).contentEquals(unsigned.copyOfRange(at, unsigned.size)) &&
      SIGNATURE_START.containsMatchIn(String(signed, at, minOf(inserted, 64), Charsets.UTF_8).trimStart())
    if (!ok) throw TurvisProtocolException("signed envelope is not the unsigned one plus a trailing ds:Signature")
  }

  private fun ByteArray.lastIndexOf(needle: ByteArray) =
    (size - needle.size downTo 0).firstOrNull { i -> needle.indices.all { this[i + it] == needle[it] } } ?: -1

  private fun failureReason(ex: Exception) = when (ex) {
    is TurvisRejectException -> ex.rejection.reason?.code ?: "unrecognised"
    is TurvisUnavailableException -> "turvis_unavailable"
    is TurvisProtocolException -> "turvis_protocol"
    is TurvisAuthException -> "turvis_auth"
    else -> "internal"
  }

  companion object {
    private val SECURITY_CLOSE = "</wsse:Security>".toByteArray()
    private val SIGNATURE_START = Regex("^<([A-Za-z_][\\w.-]*:)?Signature[\\s>]")
    /** The outbound signing policy (SPEC 13.5): the same subtrees the inbound policy requires of a peer. */
    val SIGNED_XPATHS = TurvisInbound.REQUIRED_SIGNED_XPATHS
    val SIGNED_CID_REFS = TurvisInbound.REQUIRED_SIGNED_CID_REFS
    const val ATTACHMENT_CID = TurvisInbound.ATTACHMENT_CID
    val ENCRYPT_FIELDS = setOf(TurvisEncryptField.WRAPPED_KEY_XML, TurvisEncryptField.ENCRYPTED_DATA_XML)
  }
}
