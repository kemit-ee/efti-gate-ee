package edelivery

import klite.AppScope
import klite.debug
import klite.error
import klite.logger
import klite.warn
import resql.ResqlClient

private val log = logger("AsyncHandlers")

/**
 * Writes the `audit_log` row for a handler that failed after the peer was answered. Best effort: an audit
 * failure is logged by class only and never reaches the handler's caller.
 */
class AsyncFailureAudit(private val resql: ResqlClient) {
  fun record(auditRef: String, name: String, reason: FaultReason, context: Map<String, String>) {
    try {
      resql.insertAuditLog("as4.handler_failed", "as4_message", auditRef, context + mapOf("audit_ref" to auditRef, "handler" to name, "reason" to reason.name))
    } catch (e: Exception) {
      log.warn("Audit row for async handler failure not written: audit_ref=$auditRef ${e.javaClass.name}")
    }
  }
}

/**
 * Runs [block] on [AppScope] after the peer has already been answered, so nobody is waiting for it. klite
 * logs an escaped exception with its message and nothing else; here a failure is logged by class and frame only
 * (no message, #322), tagged with [auditRef], counted by [FaultReason] bucket and written to `audit_log`.
 * [context] holds ids only (party ids, request id), never message content.
 */
internal fun auditAsync(audit: AsyncFailureAudit, auditRef: String, name: String, context: Map<String, String>, block: () -> Unit) {
  AppScope.async(name) {
    log.debug("async $name start: audit_ref=$auditRef")
    try {
      block()
      log.debug("async $name done: audit_ref=$auditRef")
    } catch (t: Throwable) {
      val reason = FaultReason.of(t)
      EDeliveryMetrics.asyncHandlerFailure(reason.name)
      log.error("handler-dispatch failure: audit_ref=$auditRef name=$name reason=$reason", t.withoutMessages())
      audit.record(auditRef, name, reason, context)
    }
  }
}
