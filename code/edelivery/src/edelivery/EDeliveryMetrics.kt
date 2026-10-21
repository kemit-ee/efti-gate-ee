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

  init {
    Metrics.register("edeliveryMessagesInboundTotal") { inbound.mapValues { it.value.sum() } }
    Metrics.register("edeliveryMessagesOutboundTotal") { outbound.mapValues { it.value.sum() } }
    // klite's Metrics has no labels: each key becomes a metric name suffix, e.g.
    // edelivery_turvis_calls_total_verifications_accept, edelivery_turvis_rejects_total_verifications_sig_policy_fail.
    Metrics.register("edeliveryTurvisCallsTotal") { turvisCalls.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisRejectsTotal") { turvisRejects.mapValues { it.value.sum() } }
    Metrics.register("edeliveryTurvisRetriesTotal") { turvisRetries.mapValues { it.value.sum() } }
  }

  /** `endpoint` is decryptions, verifications, encryptions or signatures; `verdict` is accept, reject or error (unreachable, bad shape, refused credential). */
  fun turvisCall(endpoint: String, verdict: String) = turvisCalls.getOrPut("${endpoint}_$verdict") { LongAdder() }.increment()
  fun turvisReject(endpoint: String, reasonCode: String) = turvisRejects.getOrPut("${endpoint}_${reasonCode.lowercase().replace(Regex("[^a-z0-9]"), "_")}") { LongAdder() }.increment()
  fun turvisRetry(endpoint: String) = turvisRetries.getOrPut(endpoint) { LongAdder() }.increment()

  fun inbound(rootTag: String?) = inbound.getOrPut((rootTag ?: "unknown").lowercase()) { LongAdder() }.increment()
  fun outboundSuccess() = outbound.getOrPut("success") { LongAdder() }.increment()
  fun outboundFailure() = outbound.getOrPut("failure") { LongAdder() }.increment()
}
