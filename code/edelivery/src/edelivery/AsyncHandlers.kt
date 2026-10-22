package edelivery

import klite.AppScope
import klite.debug
import klite.error
import klite.logger

private val log = logger("AsyncHandlers")

/**
 * Runs [block] on [AppScope] after the peer has already been answered, so nobody is waiting for it. klite
 * logs an escaped exception with its message and nothing else; here a failure is logged by class and frame only
 * (no message, #322), tagged with [auditRef], and counted by [FaultReason] bucket.
 */
internal fun auditAsync(auditRef: String, name: String, block: () -> Unit) {
  AppScope.async(name) {
    log.debug("async $name start: audit_ref=$auditRef")
    try {
      block()
      log.debug("async $name done: audit_ref=$auditRef")
    } catch (t: Throwable) {
      val reason = FaultReason.of(t)
      EDeliveryMetrics.asyncHandlerFailure(reason.name)
      log.error("handler-dispatch failure: audit_ref=$auditRef name=$name reason=$reason", t.withoutMessages())
    }
  }
}
