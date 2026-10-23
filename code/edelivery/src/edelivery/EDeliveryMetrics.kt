package edelivery

import klite.Metrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

object EDeliveryMetrics {
  private val inbound = ConcurrentHashMap<String, LongAdder>()
  private val outbound = ConcurrentHashMap<String, LongAdder>()
  private val outboundTurvisFailures = ConcurrentHashMap<String, LongAdder>()
  private val turvisCalls = ConcurrentHashMap<String, LongAdder>()
  private val turvisRejects = ConcurrentHashMap<String, LongAdder>()
  private val turvisRetries = ConcurrentHashMap<String, LongAdder>()
  private val shadowCompared = ConcurrentHashMap<String, LongAdder>()
  private val inboundDuplicates = ConcurrentHashMap<String, LongAdder>()
  private val inboundDedupUnavailable = LongAdder()
  private val shadowDiscrepancies = ConcurrentHashMap<String, LongAdder>()
  private val shadowSecurityDiscrepancies = LongAdder()
  private val shadowErrors = ConcurrentHashMap<String, LongAdder>()
  private val shadowSkipped = ConcurrentHashMap<String, LongAdder>()
  private val shadowDropped = LongAdder()
  private val shadowAttempted = ConcurrentHashMap<String, LongAdder>()
  private val asyncHandlerFailures = ConcurrentHashMap<String, LongAdder>()
  private val asyncResponseTimeouts = LongAdder()

  init {
    Metrics.register("edeliveryMessagesInboundTotal") { inbound.mapValues { it.value.sum() } }
    Metrics.register("edeliveryMessagesOutboundTotal") { outbound.mapValues { it.value.sum() } }
    // SPEC 13.3a: edelivery_outbound_turvis_failures_total_<reason_code>
    Metrics.register("edeliveryOutboundTurvisFailuresTotal") { outboundTurvisFailures.mapValues { it.value.sum() } }
    // klite's Metrics has no labels: each key becomes a metric name suffix, e.g.
    // edelivery_turvis_calls_total_verifications_accept, edelivery_turvis_rejects_total_verifications_sig_policy_fail.
    Metrics.register("edeliveryTurvisCallsTotal") { turvisCalls.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisRejectsTotal") { turvisRejects.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisRetriesTotal") { turvisRetries.mapValues { it.value.sum() } }
    // Shadow mode (#305b-2): the promotion gate of SPEC 13.0 is queried from these. Security discrepancies must stay at zero.
    Metrics.register("edeliveryTurvisShadowAttemptedTotal") { shadowAttempted.mapValues { it.value.sum() } }
    Metrics.register("edeliveryInboundDuplicatesTotal") { inboundDuplicates.mapValues { it.value.sum() } }
    Metrics.register("edeliveryInboundDedupUnavailableTotal") { inboundDedupUnavailable.sum() }
    Metrics.register("edeliveryTurvisShadowComparedTotal") { shadowCompared.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisShadowDiscrepanciesTotal") { shadowDiscrepancies.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisShadowSecurityDiscrepanciesTotal") { shadowSecurityDiscrepancies.sum() }
    Metrics.register("edeliveryTurvisShadowErrorsTotal") { shadowErrors.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisShadowSkippedTotal") { shadowSkipped.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisShadowDroppedTotal") { shadowDropped.sum() }
    // edelivery_async_handler_failures_total_<reason_class>: a handler dispatched after the 200 threw (reason_class = FaultReason).
    Metrics.register("edeliveryAsyncHandlerFailuresTotal") { asyncHandlerFailures.mapValues { it.value.sum() } }
    Metrics.register("edeliveryAsyncResponseTimeoutsTotal") { asyncResponseTimeouts.sum() }
  }

  /** `endpoint` is decryptions, verifications, encryptions or signatures; `verdict` is accept, reject or error (unreachable, bad shape, refused credential). */
  fun turvisCall(endpoint: String, verdict: String) = turvisCalls.getOrPut("${endpoint}_$verdict") { LongAdder() }.increment()
  fun turvisReject(endpoint: String, reasonCode: String) = turvisRejects.getOrPut("${endpoint}_${reasonCode.lowercase().replace(Regex("[^a-z0-9]"), "_")}") { LongAdder() }.increment()
  fun turvisRetry(endpoint: String) = turvisRetries.getOrPut(endpoint) { LongAdder() }.increment()

  private fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "_")

  /** Every comparable message handed to the shadow, before it is run, dropped or errors out: the denominator that errors and drops are measured against. */
  fun shadowAttempted(endpoint: String) = shadowAttempted.getOrPut(key(endpoint)) { LongAdder() }.increment()
  /** A repeat of an already accepted inbound message (#336): answered with the stored receipt (same payload) or a SOAP fault (content mismatch), not dispatched. Keys: `same_payload`, `content_mismatch`. */
  fun inboundDuplicate(samePayload: Boolean) = inboundDuplicates.getOrPut(if (samePayload) "same_payload" else "content_mismatch") { LongAdder() }.increment()
  /** The shared duplicate store could not be used: the peer got HTTP 503 and nothing was dispatched. */
  fun inboundDedupUnavailable() = inboundDedupUnavailable.increment()
  internal fun inboundDuplicateCounts(): Map<String, Long> = inboundDuplicates.mapValues { it.value.sum() }
  internal fun inboundDedupUnavailableCount() = inboundDedupUnavailable.sum()

  /** One message compared (Turvis gave a verdict). */
  fun shadowCompared(endpoint: String) = shadowCompared.getOrPut(key(endpoint)) { LongAdder() }.increment()
  /** Keys become `edelivery_turvis_shadow_discrepancies_total_<endpoint>_<kind>`. [security] also counts in the single security counter. */
  fun shadowDiscrepancy(endpoint: String, kind: String, security: Boolean) {
    shadowDiscrepancies.getOrPut("${key(endpoint)}_${key(kind)}") { LongAdder() }.increment()
    if (security) shadowSecurityDiscrepancies.increment()
  }
  /** Turvis unreachable, no verdict or contract broken: nothing was learnt about the message, so no discrepancy. */
  fun shadowError(endpoint: String, kind: String) = shadowErrors.getOrPut("${key(endpoint)}_${key(kind)}") { LongAdder() }.increment()
  fun shadowSkipped(kind: String) = shadowSkipped.getOrPut(key(kind)) { LongAdder() }.increment()
  fun shadowDropped() = shadowDropped.increment()

  internal fun asyncHandlerFailureCounts(): Map<String, Long> = asyncHandlerFailures.mapValues { it.value.sum() }
  internal fun asyncResponseTimeoutCount() = asyncResponseTimeouts.sum()
  fun asyncHandlerFailure(reasonClass: String) = asyncHandlerFailures.getOrPut(key(reasonClass)) { LongAdder() }.increment()
  /** The waiter of a cross-node reply hit its deadline before any reply arrived. */
  fun asyncResponseTimeout() = asyncResponseTimeouts.increment()

  fun inbound(rootTag: String?) = inbound.getOrPut((rootTag ?: "unknown").lowercase()) { LongAdder() }.increment()
  fun outboundSuccess() = outbound.getOrPut("success") { LongAdder() }.increment()
  /** [reason] is a contract reason code, or `turvis_unavailable` / `turvis_protocol` / `turvis_auth` / `internal`: a bounded set. */
  fun outboundTurvisFailure(reason: String) = outboundTurvisFailures.getOrPut(key(reason)) { LongAdder() }.increment()
  fun outboundFailure() = outbound.getOrPut("failure") { LongAdder() }.increment()
}
