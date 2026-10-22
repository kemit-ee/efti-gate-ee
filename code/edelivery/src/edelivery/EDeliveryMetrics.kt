package edelivery

import klite.Metrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

object EDeliveryMetrics {
  private val inbound = ConcurrentHashMap<String, LongAdder>()
  private val outbound = ConcurrentHashMap<String, LongAdder>()
  private val turvisCalls = ConcurrentHashMap<String, LongAdder>()
  private val turvisRejects = ConcurrentHashMap<String, LongAdder>()
  private val turvisRetries = ConcurrentHashMap<String, LongAdder>()
  private val shadowCompared = ConcurrentHashMap<String, LongAdder>()
  private val shadowDiscrepancies = ConcurrentHashMap<String, LongAdder>()
  private val shadowSecurityDiscrepancies = LongAdder()
  private val shadowErrors = ConcurrentHashMap<String, LongAdder>()
  private val shadowSkipped = ConcurrentHashMap<String, LongAdder>()
  private val shadowDropped = LongAdder()

  init {
    Metrics.register("edeliveryMessagesInboundTotal") { inbound.mapValues { it.value.sum() } }
    Metrics.register("edeliveryMessagesOutboundTotal") { outbound.mapValues { it.value.sum() } }
    // klite's Metrics has no labels: each key becomes a metric name suffix, e.g.
    // edelivery_turvis_calls_total_verifications_accept, edelivery_turvis_rejects_total_verifications_sig_policy_fail.
    Metrics.register("edeliveryTurvisCallsTotal") { turvisCalls.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisRejectsTotal") { turvisRejects.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisRetriesTotal") { turvisRetries.mapValues { it.value.sum() } }
    // Shadow mode (#305b-2): the promotion gate of SPEC 13.0 is queried from these. Security discrepancies must stay at zero.
    Metrics.register("edeliveryTurvisShadowComparedTotal") { shadowCompared.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisShadowDiscrepanciesTotal") { shadowDiscrepancies.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisShadowSecurityDiscrepanciesTotal") { shadowSecurityDiscrepancies.sum() }
    Metrics.register("edeliveryTurvisShadowErrorsTotal") { shadowErrors.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisShadowSkippedTotal") { shadowSkipped.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisShadowDroppedTotal") { shadowDropped.sum() }
  }

  /** `endpoint` is decryptions, verifications, encryptions or signatures; `verdict` is accept, reject or error (unreachable, bad shape, refused credential). */
  fun turvisCall(endpoint: String, verdict: String) = turvisCalls.getOrPut("${endpoint}_$verdict") { LongAdder() }.increment()
  fun turvisReject(endpoint: String, reasonCode: String) = turvisRejects.getOrPut("${endpoint}_${reasonCode.lowercase().replace(Regex("[^a-z0-9]"), "_")}") { LongAdder() }.increment()
  fun turvisRetry(endpoint: String) = turvisRetries.getOrPut(endpoint) { LongAdder() }.increment()

  private fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "_")

  /** One message compared; the denominator of the 0.01 % criterion. */
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

  fun inbound(rootTag: String?) = inbound.getOrPut((rootTag ?: "unknown").lowercase()) { LongAdder() }.increment()
  fun outboundSuccess() = outbound.getOrPut("success") { LongAdder() }.increment()
  fun outboundFailure() = outbound.getOrPut("failure") { LongAdder() }.increment()
}
