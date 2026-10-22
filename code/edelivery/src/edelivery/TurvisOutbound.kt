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

  /** Whether Turvis is the authority for outbound messages. In legacy and shadow the in-process path is. */
  val authoritative get() = config.mode == TurvisIntegrationMode.TURVIS

  fun requestMessage(params: UserMessageParams, payload: String, profile: TurvisEncryptProfile = TurvisEncryptProfile.WSS_ENCRYPTED): OutboundMessage =
    try {
      val compressed = generator.gzip(payload.toByteArray())
      val compressedB64 = compressed.base64Encode()

      val encrypted = client.encrypt(compressedB64, params.requestKey.receiverId.toString(), profile, ENCRYPT_FIELDS, randomTraceId()).orThrow()
      val unsigned = generator.unsignedRequestEnvelope(params, encrypted.wrappedKeyXml.orEmpty() + encrypted.encryptedDataXml.orEmpty())

      val signed = client.sign(
        unsigned.toByteArray().base64Encode(), SIGNED_XPATHS, SIGNED_CID_REFS, emptySet(), listOf(TurvisAttachment(ATTACHMENT_CID, compressedB64)), randomTraceId(),
      ).orThrow()

      OutboundMessage(signed.signedXmlB64.base64Decode(), encrypted.encryptedPayloadB64.base64Decode(), encrypted.auditRef, signed.auditRef)
    } catch (ex: Exception) {
      val reason = failureReason(ex)
      EDeliveryMetrics.outboundTurvisFailure(reason)
      log.warn("Outbound message not sent, Turvis failed: conversation=${params.requestKey.requestId} reason=$reason audit_ref=${(ex as? TurvisRejectException)?.auditRef}")
      throw ex
    }

  private fun failureReason(ex: Exception) = when (ex) {
    is TurvisRejectException -> ex.rejection.reason?.code ?: "unrecognised"
    is TurvisUnavailableException -> "turvis_unavailable"
    is TurvisProtocolException -> "turvis_protocol"
    is TurvisAuthException -> "turvis_auth"
    else -> "internal"
  }

  companion object {
    /** The outbound signing policy (SPEC 13.5): the same subtrees the inbound policy requires of a peer. */
    val SIGNED_XPATHS = TurvisInbound.REQUIRED_SIGNED_XPATHS
    val SIGNED_CID_REFS = TurvisInbound.REQUIRED_SIGNED_CID_REFS
    const val ATTACHMENT_CID = TurvisInbound.ATTACHMENT_CID
    val ENCRYPT_FIELDS = setOf(TurvisEncryptField.WRAPPED_KEY_XML, TurvisEncryptField.ENCRYPTED_DATA_XML)
  }
}
