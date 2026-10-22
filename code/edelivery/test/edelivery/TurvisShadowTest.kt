package edelivery

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import klite.Metrics
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurvisShadowTest {
  private val inbound = mockk<TurvisInbound>()
  private fun shadow(mode: TurvisIntegrationMode = TurvisIntegrationMode.SHADOW, maxInflight: Int = 8) =
    TurvisShadow(TurvisConfig(mode, keyHandle = "EU-EE", shadowMaxInflight = maxInflight), inbound)

  private val soap = "<env:Envelope>\r\n  <eb:Messaging/>\r\n</env:Envelope>".toByteArray()
  private val attachment = ByteArray(32) { it.toByte() }
  private val body = "--b\r\nContent-Type: application/soap+xml\r\n\r\n".toByteArray() + soap +
    "\r\n--b\r\nContent-ID: <message>\r\n\r\n".toByteArray() + attachment + "\r\n--b--\r\n".toByteArray()
  private val conversation = "11111111-1111-1111-1111-111111111111"

  private fun observation(sender: String? = "DE", receiver: String? = "EU-EE", attachmentBytes: ByteArray? = attachment, payload: String? = "<hello/>") = LegacyObservation().apply {
    stage = LegacyObservation.Stage.VERIFIED
    senderId = sender?.let(::PartyId)
    receiverId = receiver?.let(::PartyId)
    conversationId = conversation
    messageId = "m-1@x"
    this.attachment = attachmentBytes
    payloadXml = payload
  }

  private fun verified(
    identity: String = "DE",
    to: String = "EU-EE",
    decryptedSoap: ByteArray = soap,
    decryptedAttachment: ByteArray = attachment,
    payload: String = "<hello/>",
    messageId: String = "m-1@x",
    conversationId: String = conversation,
  ) = VerifiedInbound(
    TurvisEbmsRouting(messageId, conversationId, null, identity, to, "svc", "act"),
    TurvisSigner("CN=de", "ski", "01", identity), emptyList(), payload, "dec-1", "ver-1", decryptedSoap, decryptedAttachment,
  )

  private fun reject(code: String, endpoint: String = "verifications") =
    TurvisReason.of(code)!!.exception(TurvisRejection(endpoint, TurvisReason.of(code)!!.httpStatus, code, "aud-1", "turvis detail"))

  private fun counter(metric: String, key: String? = null): Long {
    val data = Metrics.data[metric]
    return when {
      key == null -> (data as Number?)?.toLong() ?: 0L
      else -> ((data as Map<*, *>?)?.get(key) as Number?)?.toLong() ?: 0L
    }
  }

  private val compared = "edeliveryTurvisShadowComparedTotal"
  private val discrepancies = "edeliveryTurvisShadowDiscrepanciesTotal"
  private val security = "edeliveryTurvisShadowSecurityDiscrepanciesTotal"
  private val errors = "edeliveryTurvisShadowErrorsTotal"
  private val skipped = "edeliveryTurvisShadowSkippedTotal"
  private val dropped = "edeliveryTurvisShadowDroppedTotal"

  private fun accepted() = LegacyDecision.Accepted(observation())

  private class Snapshot(val s: TurvisShadowTest) {
    val compared = s.counter(s.compared, "inbound")
    val security = s.counter(s.security)
    val total = (Metrics.data[s.discrepancies] as Map<*, *>?)?.values?.sumOf { (it as Number).toLong() } ?: 0L
  }

  private fun assertNoDiscrepancy(before: Snapshot) {
    assertEquals(before.total, Snapshot(this).total)
    assertEquals(before.security, counter(security))
  }

  @Test fun `matching accept is compared and counted without a discrepancy`() {
    every { inbound.process(any()) } returns verified()
    val before = Snapshot(this)

    shadow().compare("a-1", body, accepted())

    assertEquals(before.compared + 1, counter(compared, "inbound"))
    assertNoDiscrepancy(before)
  }

  @Test fun `signer identity that differs from the legacy sender is a security discrepancy`() {
    every { inbound.process(any()) } returns verified(identity = "FR")
    val before = counter(discrepancies, "verifications_signer_identity")
    val beforeSecurity = counter(security)

    shadow().compare("a-1", body, accepted())

    assertEquals(before + 1, counter(discrepancies, "verifications_signer_identity"))
    assertEquals(beforeSecurity + 1, counter(security))
  }

  @Test fun `routing that differs is a security discrepancy`() {
    every { inbound.process(any()) } returns verified(to = "EU-XX")
    val before = counter(discrepancies, "verifications_routing")
    val beforeSecurity = counter(security)

    shadow().compare("a-1", body, accepted())

    assertEquals(before + 1, counter(discrepancies, "verifications_routing"))
    assertEquals(beforeSecurity + 1, counter(security))
  }

  @Test fun `a message id that differs is a routing security discrepancy`() {
    every { inbound.process(any()) } returns verified(messageId = "other@x")
    val before = counter(discrepancies, "verifications_routing")
    val beforeSecurity = counter(security)

    shadow().compare("a-1", body, accepted())

    assertEquals(before + 1, counter(discrepancies, "verifications_routing"))
    assertEquals(beforeSecurity + 1, counter(security))
  }

  @Test fun `a non-canonical conversation id that is the same UUID is not a discrepancy`() {
    every { inbound.process(any()) } returns verified(conversationId = "1-2-3-4-5")
    val before = Snapshot(this)

    shadow().compare("a-1", body, LegacyDecision.Accepted(observation().apply { conversationId = "00000001-0002-0003-0004-000000000005" }))

    assertNoDiscrepancy(before)
  }

  @Test fun `compareInbound counts every comparable message as attempted, a skipped legacy refusal not`() {
    every { inbound.process(any()) } returns verified()
    val before = counter("edeliveryTurvisShadowAttemptedTotal")

    shadow().compareInbound("a-1", body, LegacyDecision.NotComparable)
    assertEquals(before, counter("edeliveryTurvisShadowAttemptedTotal"))

    shadow().compareInbound("a-2", body, accepted())
    assertEquals(before + 1, counter("edeliveryTurvisShadowAttemptedTotal"))
  }

  @Test fun `decrypted attachment that differs byte for byte is counted but not a security kind`() {
    every { inbound.process(any()) } returns verified(decryptedAttachment = attachment.copyOf().also { it[5] = 99 })
    val before = counter(discrepancies, "decryptions_attachment_bytes")
    val beforeSecurity = counter(security)

    shadow().compare("a-1", body, accepted())

    assertEquals(before + 1, counter(discrepancies, "decryptions_attachment_bytes"))
    assertEquals(beforeSecurity, counter(security))
  }

  @Test fun `envelope that differs only in whitespace between elements is not a discrepancy, text is`() {
    val reformatted = "<env:Envelope><eb:Messaging/></env:Envelope>".toByteArray()
    every { inbound.process(any()) } returns verified(decryptedSoap = reformatted)
    val before = Snapshot(this)

    shadow().compare("a-1", body, accepted())
    assertNoDiscrepancy(before)

    every { inbound.process(any()) } returns verified(decryptedSoap = "<env:Envelope><eb:Messaging x='1'/></env:Envelope>".toByteArray())
    val soapBefore = counter(discrepancies, "decryptions_soap_xml")
    shadow().compare("a-2", body, accepted())
    assertEquals(soapBefore + 1, counter(discrepancies, "decryptions_soap_xml"))
  }

  @Test fun `different payload is counted`() {
    every { inbound.process(any()) } returns verified(payload = "<other/>")
    val before = counter(discrepancies, "decryptions_payload")

    shadow().compare("a-1", body, accepted())

    assertEquals(before + 1, counter(discrepancies, "decryptions_payload"))
  }

  @Test fun `legacy accepts and Turvis rejects is a security discrepancy on the endpoint that rejected`() {
    every { inbound.process(any()) } throws reject("SIGNER_UNTRUSTED")
    val before = counter(discrepancies, "verifications_outcome_accept_vs_reject")
    val beforeSecurity = counter(security)

    shadow().compare("a-1", body, accepted())

    assertEquals(before + 1, counter(discrepancies, "verifications_outcome_accept_vs_reject"))
    assertEquals(beforeSecurity + 1, counter(security))
  }

  @Test fun `Turvis decrypt reject is attributed to decryptions`() {
    every { inbound.process(any()) } throws reject("SKI_MISMATCH", "decryptions")
    val before = counter(discrepancies, "decryptions_outcome_accept_vs_reject")

    shadow().compare("a-1", body, accepted())

    assertEquals(before + 1, counter(discrepancies, "decryptions_outcome_accept_vs_reject"))
  }

  @Test fun `legacy rejects and Turvis accepts is a security discrepancy`() {
    every { inbound.process(any()) } returns verified()
    val before = counter(discrepancies, "verifications_outcome_reject_vs_accept")
    val beforeSecurity = counter(security)

    shadow().compare("a-1", body, LegacyDecision.Rejected(FaultReason.SIG_CRYPTO_FAIL))

    assertEquals(before + 1, counter(discrepancies, "verifications_outcome_reject_vs_accept"))
    assertEquals(beforeSecurity + 1, counter(security))
  }

  @Test fun `both reject with the same ebMS code agree even if the reason names differ`() {
    every { inbound.process(any()) } throws reject("SIGNER_UNTRUSTED")
    val before = Snapshot(this)

    shadow().compare("a-1", body, LegacyDecision.Rejected(FaultReason.SIG_CRYPTO_FAIL))

    assertNoDiscrepancy(before)
    assertEquals(before.compared + 1, counter(compared, "inbound"))
  }

  @Test fun `both reject with different ebMS codes is a security discrepancy`() {
    every { inbound.process(any()) } throws reject("SKI_MISMATCH", "decryptions")
    val before = counter(discrepancies, "decryptions_reject_reason")
    val beforeSecurity = counter(security)

    shadow().compare("a-1", body, LegacyDecision.Rejected(FaultReason.SIG_CRYPTO_FAIL))

    assertEquals(before + 1, counter(discrepancies, "decryptions_reject_reason"))
    assertEquals(beforeSecurity + 1, counter(security))
  }

  @Test fun `our own refusal of malformed bytes is a verdict that is compared`() {
    every { inbound.process(any()) } throws IllegalArgumentException("no boundary")
    val before = Snapshot(this)

    shadow().compare("a-1", body, LegacyDecision.Rejected(FaultReason.XML_PARSE_FAIL))

    assertNoDiscrepancy(before)
    assertEquals(before.compared + 1, counter(compared, "inbound"))
  }

  @Test fun `unreachable, broken or verdictless Turvis is an error, not a discrepancy and not a comparison`() {
    listOf<Pair<Exception, String>>(
      TurvisUnavailableException("down") to "inbound_unavailable",
      TurvisProtocolException("garbage") to "inbound_protocol",
      TurvisAuthException(401, "verifications") to "verifications_auth",
      reject("TURVIS_INTERNAL") to "verifications_no_verdict",
      reject("KEY_UNAVAILABLE") to "verifications_no_verdict",
    ).forEach { (ex, key) ->
      every { inbound.process(any()) } throws ex
      val before = Snapshot(this)
      val errorsBefore = counter(errors, key)

      shadow().compare("a-1", body, accepted())

      assertEquals(errorsBefore + 1, counter(errors, key), key)
      assertEquals(before.compared, counter(compared, "inbound"), key)
      assertNoDiscrepancy(before)
    }
  }

  @Test fun `a legacy refusal before any cryptography is skipped, Turvis is not called`() {
    val before = counter(skipped, "not_comparable")

    shadow().compareInbound("a-1", body, LegacyDecision.NotComparable)

    assertEquals(before + 1, counter(skipped, "not_comparable"))
    verify(exactly = 0) { inbound.process(any()) }
  }

  @Test fun `legacy and turvis modes never call Turvis from the shadow`() {
    listOf(TurvisIntegrationMode.LEGACY, TurvisIntegrationMode.TURVIS).forEach {
      shadow(it).compareInbound("a-1", body, accepted())
    }

    verify(exactly = 0) { inbound.process(any()) }
  }

  @Test fun `a slow Turvis does not block the caller`() {
    val release = CountDownLatch(1)
    val entered = CountDownLatch(1)
    every { inbound.process(any()) } answers { entered.countDown(); release.await(10, TimeUnit.SECONDS); verified() }

    val started = System.nanoTime()
    shadow().compareInbound("a-1", body, accepted())
    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    assertTrue(entered.await(5, TimeUnit.SECONDS), "the comparison runs on its own thread")
    assertTrue(elapsedMs < 1000, "caller returned in $elapsedMs ms while Turvis was still busy")
    release.countDown()
  }

  @Test fun `a crashing comparison cannot reach the caller`() {
    val done = CountDownLatch(1)
    every { inbound.process(any()) } answers { done.countDown(); throw StackOverflowError() }
    val before = counter(errors, "inbound_internal")

    shadow().compareInbound("a-1", body, accepted())

    assertTrue(done.await(5, TimeUnit.SECONDS))
    val deadline = System.currentTimeMillis() + 5000
    while (counter(errors, "inbound_internal") == before && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertEquals(before + 1, counter(errors, "inbound_internal"))
  }

  @Test fun `at most the configured number of comparisons run at once, the rest are dropped and counted`() {
    val release = CountDownLatch(1)
    val entered = CountDownLatch(1)
    every { inbound.process(any()) } answers { entered.countDown(); release.await(10, TimeUnit.SECONDS); verified() }
    val sut = shadow(maxInflight = 1)
    val before = counter(dropped)

    sut.compareInbound("a-1", body, accepted())
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    sut.compareInbound("a-2", body, accepted())
    sut.compareInbound("a-3", body, accepted())

    assertEquals(before + 2, counter(dropped))
    release.countDown()
    verify(timeout = 5000, exactly = 1) { inbound.process(any()) }
  }

  @Test fun `a slot is freed when a comparison ends`() {
    every { inbound.process(any()) } returns verified()
    val sut = shadow(maxInflight = 1)
    val before = counter(dropped)

    repeat(5) {
      sut.compareInbound("a-$it", body, accepted())
      val deadline = System.currentTimeMillis() + 5000
      val expected = it + 1
      while (System.currentTimeMillis() < deadline && runCatching { verify(exactly = expected) { inbound.process(any()) } }.isFailure) Thread.sleep(10)
      while (System.currentTimeMillis() < deadline && sut.inflightNow > 0) Thread.sleep(5)
      assertEquals(0, sut.inflightNow)
    }

    assertEquals(before, counter(dropped))
  }
}
