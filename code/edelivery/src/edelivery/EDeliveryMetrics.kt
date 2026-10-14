package edelivery

import klite.Metrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

object EDeliveryMetrics {
  private val inbound = ConcurrentHashMap<String, LongAdder>()
  private val outbound = ConcurrentHashMap<String, LongAdder>()

  init {
    Metrics.register("edeliveryMessagesInboundTotal") { inbound.mapValues { it.value.sum() } }
    Metrics.register("edeliveryMessagesOutboundTotal") { outbound.mapValues { it.value.sum() } }
  }

  fun inbound(rootTag: String?) = inbound.getOrPut((rootTag ?: "unknown").lowercase()) { LongAdder() }.increment()
  fun outboundSuccess() = outbound.getOrPut("success") { LongAdder() }.increment()
  fun outboundFailure() = outbound.getOrPut("failure") { LongAdder() }.increment()
}
