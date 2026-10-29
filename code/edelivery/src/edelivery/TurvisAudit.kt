package edelivery

/**
 * The record-level audit fields of SPEC 13.7, as one log line per message (there is no message table in edelivery;
 * the line is what an incident lookup greps for, by `turvis_audit_ref_*` or `message_id`).
 *
 * Ids only, never a payload, envelope, certificate subject or exception message. Every value is taken from a Turvis
 * answer or from the peer's envelope, so it is checked against a narrow character set before it reaches a log line:
 * anything else is written as `?`, which keeps a crafted value from forging a second line or a second field.
 */
object TurvisAudit {
  private fun safe(value: String?) = value.logSafe()

  fun inbound(auditRef: String, inbound: VerifiedInbound, receiptSignAuditRef: String?): String = line(
    "turvis_audit direction=inbound",
    "audit_ref" to auditRef,
    "turvis_audit_ref_decrypt" to inbound.decryptAuditRef,
    "turvis_audit_ref_verify" to inbound.verifyAuditRef,
    "turvis_audit_ref_sign" to receiptSignAuditRef,
    "signer_identity" to inbound.signer.verifiedIdentity,
    "signer_cert_ski" to inbound.signer.certSki,
    "message_id" to inbound.routing.messageId,
    "conversation_id" to inbound.routing.conversationId,
    "ref_to_message_id" to inbound.routing.refToMessageId,
    "from_party_id" to inbound.routing.fromPartyId,
    "to_party_id" to inbound.routing.toPartyId,
    "service" to inbound.routing.service,
    "action" to inbound.routing.action,
  )

  /** A repeat of an inbound message that was already accepted (#336): answered with the stored receipt, not dispatched. */
  fun duplicate(auditRef: String, inbound: VerifiedInbound, samePayload: Boolean): String = line(
    "turvis_audit direction=inbound_duplicate",
    "audit_ref" to auditRef,
    "turvis_audit_ref_verify" to inbound.verifyAuditRef,
    "signer_identity" to inbound.signer.verifiedIdentity,
    "message_id" to inbound.routing.messageId,
    "payload_match" to samePayload.toString(),
  )

  fun outbound(params: UserMessageParams, message: OutboundMessage): String = line(
    "turvis_audit direction=outbound",
    "request_id" to params.requestKey.requestId.toString(),
    "turvis_audit_ref_encrypt" to message.encryptAuditRef,
    "turvis_audit_ref_sign" to message.signAuditRef,
    "from_party_id" to params.requestKey.senderId.toString(),
    "to_party_id" to params.requestKey.receiverId.toString(),
    "service" to params.service,
    "action" to params.action,
  )

  private fun line(head: String, vararg fields: Pair<String, String?>) =
    head + fields.joinToString("") { " ${it.first}=${safe(it.second)}" }
}

private val LOG_SAFE = Regex("[A-Za-z0-9._:@/+=-]{1,200}")

/** A peer-supplied value as it may appear in a log line: itself when it fits the narrow audit character set, `-` for null, otherwise `?`. */
fun String?.logSafe(): String = when {
  this == null -> "-"
  LOG_SAFE.matches(this) -> this
  else -> "?"
}
