package edelivery

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import resql.ResqlClient
import java.io.IOException

class AsyncFailureAuditTest {
  private val resql = mockk<ResqlClient>(relaxed = true)
  private val audit = AsyncFailureAudit(resql)

  @Test fun `writes one audit row with ids and the reason bucket only`() {
    audit.record("ref-1", "handler", FaultReason.OTHER, mapOf("response_key" to "EU-FR:abc:EU-EE"))

    verify(exactly = 1) {
      resql.insertAuditLog("as4.handler_failed", "as4_message", "ref-1",
        mapOf("response_key" to "EU-FR:abc:EU-EE", "audit_ref" to "ref-1", "handler" to "handler", "reason" to "OTHER"))
    }
  }

  @Test fun `a failing audit write never propagates`() {
    every { resql.insertAuditLog(any(), any(), any(), any()) } throws IOException("db down")

    audit.record("ref-1", "handler", FaultReason.OTHER, emptyMap())
  }
}
