package edelivery

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TurvisAuditTest {
  private fun inbound(messageId: String = "m-1@eu-fr", refTo: String? = null, identity: String = "EU-FR") = VerifiedInbound(
    TurvisEbmsRouting(messageId, "6f1c3a52-0000-4000-8000-000000000001", refTo, "EU-FR", "EU-EE", "bdx:noprocess", "eftiGateAction"),
    TurvisSigner("CN=secret subject", "AB12CD", "7", identity),
    emptyList(), "<payload>secret</payload>", "dec-1", "ver-1",
  )

  @Test fun `the inbound line carries every SPEC 13_7 field and nothing else`() {
    val line = TurvisAudit.inbound("req-1", inbound(), "sig-1")

    val fields = line.removePrefix("turvis_audit ").split(" ").associate { it.substringBefore('=') to it.substringAfter('=') }
    assertEquals(
      mapOf(
        "direction" to "inbound", "audit_ref" to "req-1", "turvis_audit_ref_decrypt" to "dec-1", "turvis_audit_ref_verify" to "ver-1",
        "turvis_audit_ref_sign" to "sig-1", "signer_identity" to "EU-FR", "signer_cert_ski" to "AB12CD", "message_id" to "m-1@eu-fr",
        "conversation_id" to "6f1c3a52-0000-4000-8000-000000000001", "ref_to_message_id" to "-", "from_party_id" to "EU-FR",
        "to_party_id" to "EU-EE", "service" to "bdx:noprocess", "action" to "eftiGateAction",
      ),
      fields,
    )
    assertFalse(line.contains("secret"), "no subject DN, no payload")
  }

  @Test fun `a receipt that was not signed has no sign ref`() {
    assertTrue(TurvisAudit.inbound("req-1", inbound(), null).contains(" turvis_audit_ref_sign=- "))
  }

  @Test fun `a value that could forge a line or a field is replaced`() {
    val line = TurvisAudit.inbound("req-1", inbound(messageId = "m\nturvis_audit direction=outbound", refTo = "a b", identity = "x=y z"), "sig-1")

    assertEquals(1, line.lines().size)
    assertTrue(line.contains(" message_id=? "))
    assertTrue(line.contains(" ref_to_message_id=? "))
    assertTrue(line.contains(" signer_identity=? "))
    assertEquals(14, line.split(" ").size - 1, "still one field per name")
  }

  @Test fun `an over-long value is replaced`() {
    assertTrue(TurvisAudit.inbound("req-1", inbound(messageId = "a".repeat(201)), "s").contains(" message_id=? "))
    assertTrue(TurvisAudit.inbound("req-1", inbound(messageId = "a".repeat(200)), "s").contains(" message_id=${"a".repeat(200)} "))
  }

  @Test fun `the outbound line carries the encrypt and sign refs and the routing, no payload`() {
    val id = UUID.fromString("6f1c3a52-0000-4000-8000-000000000002")
    val params = UserMessageParams(RequestKey(PartyId("EU-FR"), id, PartyId("EU-EE")))

    val line = TurvisAudit.outbound(params, OutboundMessage(byteArrayOf(1), byteArrayOf(2), "enc-1", "sig-1"))

    assertEquals(
      "turvis_audit direction=outbound request_id=$id turvis_audit_ref_encrypt=enc-1 turvis_audit_ref_sign=sig-1 " +
        "from_party_id=EU-EE to_party_id=EU-FR service=bdx:noprocess action=eftiGateAction",
      line,
    )
  }
}
