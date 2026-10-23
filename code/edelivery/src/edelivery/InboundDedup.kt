package edelivery

import klite.base64Decode
import klite.base64Encode
import resql.ResqlClient

/** The receipt and payload hash stored for an inbound message that was already accepted. */
class AcceptedInbound(val receipt: ByteArray, val payloadSha256: String)

/** The shared duplicate store cannot be read or written: the message is neither dispatched nor recorded, the peer gets HTTP 503 and retries. */
class InboundDedupUnavailableException(cause: Throwable? = null) : RuntimeException("inbound duplicate store unavailable", cause)

/**
 * Duplicate elimination for inbound AS4 messages in turvis mode (#336, SPEC 13.12): a peer that repeats a message
 * after a lost receipt must not get it dispatched twice. The state is one row per (verified sender, MessageId) in
 * the shared database (not memory: a repeat can land on another node), written with `INSERT ... ON CONFLICT DO NOTHING`
 * so that exactly one concurrent caller wins. The row holds the signed receipt that was sent, which a repeat gets back
 * byte for byte.
 *
 * The key is the **verified** sender identity (`signer.verified_identity`, upper-cased: a PartyId is case-insensitive),
 * never the unsigned envelope `From`, so one peer cannot use up another peer's message ids.
 */
class InboundDedup(private val resql: ResqlClient) {
  fun find(senderId: PartyId, messageId: String): AcceptedInbound? {
    checked(messageId)
    return guarded { resql.findInboundMessage(key(senderId), messageId)?.let { AcceptedInbound(it.receiptB64.base64Decode(), it.payloadSha256) } }
  }

  /** True if this call recorded the pair; false if it was already recorded (a repeat, or another node got there first). */
  fun record(senderId: PartyId, messageId: String, payloadSha256: String, receipt: ByteArray): Boolean {
    checked(messageId)
    return guarded { resql.insertInboundMessage(key(senderId), messageId, payloadSha256, receipt.base64Encode()) }
  }

  private fun key(sender: PartyId) = sender.toString().uppercase()

  /** A longer id is not a plausible ebMS MessageId and would not fit a b-tree index entry: refused as a malformed message, not as an outage. */
  private fun checked(messageId: String) {
    require(messageId.length <= MAX_MESSAGE_ID_LENGTH) { "MessageId is longer than $MAX_MESSAGE_ID_LENGTH characters" }
  }

  private inline fun <T> guarded(block: () -> T): T = try {
    block()
  } catch (e: Exception) {
    throw InboundDedupUnavailableException(e)
  }

  companion object {
    const val MAX_MESSAGE_ID_LENGTH = 512
  }
}
