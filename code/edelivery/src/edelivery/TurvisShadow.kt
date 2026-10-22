package edelivery

import klite.error
import klite.logger
import klite.warn
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** What the legacy inbound path did with one message, collected as it goes so the shadow comparison can read it afterwards. */
class LegacyObservation {
  enum class Stage { PARSE, PARTY_LOOKUP, CRYPTO, VERIFIED }

  @Volatile var stage = Stage.PARSE
  @Volatile var senderId: PartyId? = null
  @Volatile var receiverId: PartyId? = null
  @Volatile var conversationId: String? = null
  @Volatile var messageId: String? = null
  @Volatile var attachment: ByteArray? = null
  @Volatile var payloadXml: String? = null
}

/** The security decision of the legacy path for one message: the thing Turvis is compared against. */
sealed interface LegacyDecision {
  data class Accepted(val observation: LegacyObservation) : LegacyDecision
  data class Rejected(val reason: FaultReason, val peer: PartyId? = null) : LegacyDecision
  /** Legacy refused before any cryptography (unknown sender party): a registry decision, nothing for Turvis to agree or disagree with. */
  data object NotComparable : LegacyDecision
}

/**
 * `TURVIS_INTEGRATION_MODE=shadow` for inbound messages (SPEC 13.0, #305b-2): the legacy in-process path stays
 * authoritative and answers the peer; afterwards the same bytes go through [TurvisInbound] on a separate virtual
 * thread and the two outcomes are compared and counted.
 *
 * Invariants (each has a test):
 * - bounded: at most [TurvisConfig.shadowMaxInflight] comparisons run at once, each is limited by the client's
 *   own per-call timeout and retry budget; a message over the limit is dropped and counted, never queued;
 * - isolated: the comparison runs on its own thread, catches everything, and nothing it does can reach the legacy
 *   response; submitting it cannot throw into the caller;
 * - queryable: every comparison, discrepancy (by endpoint and kind), security discrepancy, error and drop is a
 *   counter ([EDeliveryMetrics]); a discrepancy also gets one log line with ids only, never values or bytes.
 *
 * What is compared (SPEC 13.0 step 2): accept/reject outcome, the reject's ebMS code, the signer identity against
 * the legacy sender, routing, the decrypted attachment byte for byte, the decrypted envelope modulo whitespace
 * between elements, and the business payload. The first four and the routing are **security** kinds: they are never absorbed by
 * the 0.01 % allowance. A Turvis that is unreachable, answers an internal error or breaks the contract is an
 * error count, not a discrepancy: nothing was learnt about the message.
 */
class TurvisShadow(private val config: TurvisConfig, private val inbound: TurvisInbound) {
  private val log = logger()
  private val inflight = AtomicInteger()

  val enabled get() = config.mode == TurvisIntegrationMode.SHADOW
  internal val inflightNow get() = inflight.get()

  /** Hands the message to a comparison thread. Never throws, never blocks. */
  fun compareInbound(auditRef: String, body: ByteArray, decision: LegacyDecision) {
    if (!enabled) return
    var acquired = false
    try {
      if (decision is LegacyDecision.NotComparable) return EDeliveryMetrics.shadowSkipped("not_comparable")
      EDeliveryMetrics.shadowAttempted("inbound")
      if (inflight.incrementAndGet() > config.shadowMaxInflight) {
        inflight.decrementAndGet()
        return EDeliveryMetrics.shadowDropped()
      }
      acquired = true
      Thread.ofVirtual().name("turvis-shadow").start {
        try {
          compare(auditRef, body, decision)
        } catch (t: Throwable) {
          EDeliveryMetrics.shadowError("inbound", "internal")
          log.error("Turvis shadow comparison failed: audit_ref=$auditRef", t.withoutMessages())
        } finally {
          inflight.decrementAndGet()
        }
      }
    } catch (t: Throwable) {
      if (acquired) inflight.decrementAndGet()
      EDeliveryMetrics.shadowError("inbound", "submit")
    }
  }

  internal fun compare(auditRef: String, body: ByteArray, decision: LegacyDecision) {
    val peer = when (decision) {
      is LegacyDecision.Accepted -> decision.observation.senderId
      is LegacyDecision.Rejected -> decision.peer
      LegacyDecision.NotComparable -> null
    }
    val turvis = try {
      Outcome.Accepted(inbound.process(body))
    } catch (ex: TurvisRejectException) {
      val fault = FaultReason.ofTurvis(ex.rejection.effectiveReason)
      if (fault == null) return skipped(auditRef, ex.rejection.endpoint, "no_verdict", peer, ex.auditRef)
      Outcome.Rejected(fault, ex.rejection.endpoint, ex.auditRef)
    } catch (ex: TurvisUnavailableException) {
      return skipped(auditRef, "inbound", "unavailable", peer)
    } catch (ex: TurvisProtocolException) {
      return skipped(auditRef, "inbound", "protocol", peer)
    } catch (ex: TurvisAuthException) {
      return skipped(auditRef, ex.endpoint, "auth", peer)
    } catch (ex: Exception) {
      // Our own refusal of the bytes (malformed multipart, bad gzip, oversize): a verdict Turvis never saw, but a verdict.
      Outcome.Rejected(FaultReason.of(ex), "inbound", null)
    }
    EDeliveryMetrics.shadowCompared("inbound")

    when (decision) {
      is LegacyDecision.NotComparable -> return
      is LegacyDecision.Rejected -> when (turvis) {
        is Outcome.Rejected -> if (decision.reason.ebmsCode != turvis.fault.ebmsCode) discrepancy(auditRef, turvis.endpoint, "reject_reason", security = true, turvis.turvisAuditRef)
        is Outcome.Accepted -> discrepancy(auditRef, "verifications", "outcome_reject_vs_accept", security = true, turvis.value.verifyAuditRef)
      }
      is LegacyDecision.Accepted -> when (turvis) {
        is Outcome.Rejected -> discrepancy(auditRef, turvis.endpoint, "outcome_accept_vs_reject", security = true, turvis.turvisAuditRef)
        is Outcome.Accepted -> compareAccepted(auditRef, body, decision.observation, turvis.value)
      }
    }
  }

  private fun compareAccepted(auditRef: String, body: ByteArray, legacy: LegacyObservation, turvis: VerifiedInbound) {
    val verifyRef = turvis.verifyAuditRef
    val legacySender = legacy.senderId
    if (legacySender == null || !legacySender.equals(PartyId(turvis.signer.verifiedIdentity)))
      discrepancy(auditRef, "verifications", "signer_identity", security = true, verifyRef)

    val routing = turvis.routing
    // The legacy side holds the canonical UUID text; Turvis hands the raw string, so compare the same equivalence mshViaTurvis uses.
    val turvisConversation = routing.conversationId?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
    val routingMatches = legacy.receiverId?.equals(PartyId(routing.toPartyId)) == true &&
      legacy.conversationId != null && legacy.conversationId == turvisConversation &&
      legacy.messageId != null && legacy.messageId == routing.messageId
    if (!routingMatches) discrepancy(auditRef, "verifications", "routing", security = true, verifyRef)

    val attachment = legacy.attachment
    if (attachment == null || !attachment.contentEquals(turvis.decryptedAttachment))
      discrepancy(auditRef, "decryptions", "attachment_bytes", security = false, turvis.decryptAuditRef)

    val receivedSoap = MimeSplitter.split(body).first().bytes
    if (!sameModuloWhitespace(receivedSoap, turvis.decryptedSoap))
      discrepancy(auditRef, "decryptions", "soap_xml", security = false, turvis.decryptAuditRef)

    if (legacy.payloadXml != turvis.payloadXml) discrepancy(auditRef, "decryptions", "payload", security = false, turvis.decryptAuditRef)
  }

  /** Equal once whitespace between tags is ignored (SPEC 13.0: "modulo whitespace"); text content and attributes still have to match. */
  private fun sameModuloWhitespace(a: ByteArray, b: ByteArray) = a.contentEquals(b) || normalise(a) == normalise(b)

  private fun normalise(bytes: ByteArray) = String(bytes, Charsets.UTF_8).replace(BETWEEN_TAGS, "><").trim()

  private fun discrepancy(auditRef: String, endpoint: String, kind: String, security: Boolean, turvisAuditRef: String?) {
    EDeliveryMetrics.shadowDiscrepancy(endpoint, kind, security)
    log.warn("Turvis shadow discrepancy: audit_ref=$auditRef endpoint=$endpoint kind=$kind security=$security turvis_audit_ref=$turvisAuditRef")
  }

  private fun skipped(auditRef: String, endpoint: String, kind: String, peer: PartyId?, turvisAuditRef: String? = null) {
    EDeliveryMetrics.shadowError(endpoint, kind)
    log.warn("Turvis shadow comparison skipped: audit_ref=$auditRef peer=$peer endpoint=$endpoint kind=$kind turvis_audit_ref=$turvisAuditRef")
  }

  private sealed interface Outcome {
    class Accepted(val value: VerifiedInbound) : Outcome
    class Rejected(val fault: FaultReason, val endpoint: String, val turvisAuditRef: String?) : Outcome
  }

  private companion object {
    val BETWEEN_TAGS = Regex(">\\s+<")
  }
}
