package edelivery

import klite.base64Decode
import klite.base64Encode

/** What a successful inbound pass through Turvis yields: everything the handlers and the audit need, no XML. */
class VerifiedInbound(
  /** Routing from the verify response (the signed `eb:Messaging`): the only routing that may drive dispatch. */
  val routing: TurvisEbmsRouting,
  /** The sender, as the signature proves it. Business authorisation uses this, not [TurvisEbmsRouting.fromPartyId] (SPEC 13.9). */
  val signer: TurvisSigner,
  /** The references the signature covers, as written: what a receipt (NRR) needs (SPEC 13.7). */
  val signatureDigests: List<TurvisSignatureReference>,
  /** The decrypted, decompressed business payload (an eFTI message XML; handlers and xml-mapper own it from here). */
  val payloadXml: String,
  val decryptAuditRef: String,
  val verifyAuditRef: String,
  /** What decrypt returned, as bytes: the shadow comparison reads them; nothing else needs them. */
  val decryptedSoap: ByteArray = ByteArray(0),
  val decryptedAttachment: ByteArray = ByteArray(0),
)

/**
 * The inbound AS4 security pass when Turvis is authoritative (`TURVIS_INTEGRATION_MODE=turvis`), SPEC 2.2 and 9:
 * MIME-split the body into raw bytes, decrypt, verify, and hand back only what Turvis vouched for.
 *
 * edelivery parses no XML on this path. Invariants (each has a test):
 * - the SOAP envelope and the attachment reach Turvis with exactly the received bytes, and verify gets the
 *   decrypt output verbatim (SPEC 9.1a);
 * - routing and sender come from the **verify** response; decrypt is asked for no routing at all;
 * - the sender is bound to the verified signer even though `claimed_signer_id` cannot be known before the
 *   envelope is read: the envelope's `From` must equal `signer.verified_identity` (PartyId equality,
 *   case-insensitive), and the response must cover every XPath/cid of the policy;
 * - any reject, or a verify that does not cover the policy, ends the message here: no dispatch.
 */
class TurvisInbound(private val config: TurvisConfig, private val client: TurvisClient) {
  /** Whether Turvis is the authority for inbound messages. In legacy and shadow the in-process path is. */
  val authoritative get() = config.mode == TurvisIntegrationMode.TURVIS

  fun process(body: ByteArray): VerifiedInbound {
    val parts = MimeSplitter.split(body)
    // Exactly two parts: a third one (the legacy detached key part) never reaches Turvis, so it is refused instead of dropped silently.
    require(parts.size == 2) { "AS4 message needs exactly a SOAP part and an attachment part" }
    val soap = parts.first().bytes
    val attachmentPart = parts.last()
    require(attachmentPart.contentId.let { it == null || it == ATTACHMENT_CID.removePrefix("cid:") }) { "AS4 attachment part is not cid:message" }
    val attachment = attachmentPart.bytes

    // The answers of decrypt and verify are trusted without re-verification: only safe once the mesh (#309) authenticates Turvis and limits egress (docs/architecture/security-and-compliance/perimeter-trust-model.md, path 3).
    val decrypted = client.decrypt(soap.base64Encode(), attachment.base64Encode(), DECRYPT_FIELDS, randomTraceId()).orThrow()
    val soapB64 = decrypted.decryptedSoapXmlB64!!
    val attachmentB64 = decrypted.decryptedAttachmentB64!!

    val verified = client.verify(
      soapB64, listOf(TurvisAttachment(ATTACHMENT_CID, attachmentB64)), REQUIRED_SIGNED_XPATHS, REQUIRED_SIGNED_CID_REFS, VERIFY_FIELDS, traceId = randomTraceId(),
    ).orThrow()
    val signer = verified.signer!!
    val routing = verified.ebmsRouting!!

    if (!PartyId(routing.fromPartyId).equals(PartyId(signer.verifiedIdentity)))
      throw SignerIdentityMismatchException(rejection("SIGNER_IDENTITY_MISMATCH", verified.auditRef, "envelope sender is not the verified signer"))
    if (!verified.matchedSignedXpaths!!.containsAll(REQUIRED_SIGNED_XPATHS) || !verified.matchedSignedCidRefs!!.containsAll(REQUIRED_SIGNED_CID_REFS))
      throw SignaturePolicyException(rejection("SIG_POLICY_FAIL", verified.auditRef, "verify did not cover the required signed content"))

    val attachmentBytes = attachmentB64.base64Decode()
    val payloadXml = String(gunzip(attachmentBytes, config.maxPayloadBytes))
    return VerifiedInbound(routing, signer, verified.signatureDigests!!, payloadXml, decrypted.auditRef, verified.auditRef, soapB64.base64Decode(), attachmentBytes)
  }

  private fun rejection(code: String, auditRef: String, detail: String) =
    TurvisRejection("verifications", TurvisReason.of(code)!!.httpStatus, code, auditRef, detail)

  companion object {
    /** SPEC 9.2: the policy edelivery passes on an AS4 inbound verify. It lives here, not in Turvis. */
    val REQUIRED_SIGNED_XPATHS = listOf("//eb:Messaging", "//env:Body")
    val REQUIRED_SIGNED_CID_REFS = listOf("cid:message")
    const val ATTACHMENT_CID = "cid:message"

    /** Decrypt is asked for the bytes only: its routing is diagnostic and must never authorise dispatch (SPEC 4.4). */
    val DECRYPT_FIELDS = setOf(TurvisDecryptField.DECRYPTED_SOAP_XML_B64, TurvisDecryptField.DECRYPTED_ATTACHMENT_B64)
    val VERIFY_FIELDS = setOf(
      TurvisVerifyField.SIGNER, TurvisVerifyField.EBMS_ROUTING, TurvisVerifyField.SIGNATURE_DIGESTS,
      TurvisVerifyField.MATCHED_SIGNED_XPATHS, TurvisVerifyField.MATCHED_SIGNED_CID_REFS,
    )
  }
}
