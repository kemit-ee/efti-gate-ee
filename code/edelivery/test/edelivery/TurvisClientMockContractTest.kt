package edelivery

import klite.json.JsonMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client against the real `turvis-mock` service (#306), over HTTP: the contract as the mock enforces it,
 * not a canned answer. Skipped unless `TURVIS_MOCK_URL` is set, so the backend job does not need Docker:
 *
 *     docker compose up --build -d turvis-mock
 *     TURVIS_MOCK_URL=http://127.0.0.1:8091 ./gradlew edelivery:test --tests 'edelivery.TurvisClientMockContractTest'
 *
 * `TURVIS_MOCK_TOKEN` overrides the token (default: the mock's committed dev token). The mock has no real
 * cryptography, so this proves the shapes, status handling and projection, nothing about a real message.
 */
class TurvisClientMockContractTest {
  private val mockUrl = System.getenv("TURVIS_MOCK_URL")
  private val token = System.getenv("TURVIS_MOCK_TOKEN") ?: "dev-turvis-mock-token"
  private val http = HttpClient.newHttpClient()
  private val json = JsonMapper()

  private fun client(url: String = mockUrl, token: String = this.token, keyHandle: String = "EU-EE") =
    TurvisClient(TurvisConfig(TurvisIntegrationMode.TURVIS, URI(url), token, keyHandle), http, json)

  private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

  private val envelope = b64(
    """<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope" xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ns/v3.0/ebms/core/200704/"><env:Header>""" +
      """<eb:Messaging wsu:Id="MSG-ID"><eb:UserMessage><eb:MessageInfo><eb:MessageId>contract-0001@eu-de.example</eb:MessageId><eb:RefToMessageId>earlier-0000@eu-ee.example</eb:RefToMessageId></eb:MessageInfo>""" +
      """<eb:PartyInfo><eb:From><eb:PartyId>EU-DE</eb:PartyId></eb:From><eb:To><eb:PartyId>EU-EE</eb:PartyId></eb:To></eb:PartyInfo>""" +
      """<eb:CollaborationInfo><eb:Service>FTI010</eb:Service><eb:Action>FTI010Response</eb:Action><eb:ConversationId>conv-contract-0001</eb:ConversationId></eb:CollaborationInfo></eb:UserMessage></eb:Messaging>""" +
      """</env:Header><env:Body wsu:Id="BODY-ID"/></env:Envelope>""",
  )

  private val routing = TurvisEbmsRouting("contract-0001@eu-de.example", "conv-contract-0001", "earlier-0000@eu-ee.example", "EU-DE", "EU-EE", "FTI010", "FTI010Response")

  private fun needMock() = assumeTrue(mockUrl != null, "TURVIS_MOCK_URL is not set")

  @Test fun `decrypt and verify return the same message, conversation and correlation ids for the same bytes`() {
    needMock()
    val decrypted = assertIs<TurvisResult.Accept<TurvisDecryptResponse>>(
      client().decrypt(envelope, "QUJD", TurvisDecryptField.entries.toSet()),
    ).value
    assertEquals(envelope, decrypted.decryptedSoapXmlB64)
    assertEquals("QUJD", decrypted.decryptedAttachmentB64)
    assertEquals(routing, decrypted.ebmsRouting)

    val verified = assertIs<TurvisResult.Accept<TurvisVerifyResponse>>(
      client().verify(
        decrypted.decryptedSoapXmlB64!!, listOf(TurvisAttachment("cid:message", "QUJD")), listOf("//eb:Messaging", "//env:Body"), listOf("cid:message"),
        TurvisVerifyField.entries.toSet(),
      ),
    ).value
    assertEquals(routing, verified.ebmsRouting)
    assertEquals("EU-DE", verified.signer!!.verifiedIdentity)
    assertEquals(listOf("//eb:Messaging", "//env:Body"), verified.matchedSignedXpaths)
    assertEquals(listOf("#MSG-ID", "#BODY-ID", "cid:message"), verified.signatureDigests!!.map { it.referenceUri })
    assertTrue(verified.signatureDigests.all { it.algorithm == "SHA-256" && it.transforms.isNotEmpty() })
    assertNotNull(verified.signatureDigests.single { it.referenceUri == "cid:message" }.transforms.firstOrNull())
  }

  @Test fun `the inbound pass splits, decrypts and verifies a multipart message against the mock`() {
    needMock()
    val payload = "<hello>world</hello>"
    val gzipped = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(payload.toByteArray()) } }.toByteArray()
    val soap = Base64.getDecoder().decode(envelope)
    val body = "--bnd\r\nContent-Type: application/soap+xml\r\n\r\n".toByteArray() + soap +
      "\r\n--bnd\r\nContent-ID: <message>\r\n\r\n".toByteArray() + gzipped + "\r\n--bnd--\r\n".toByteArray()

    val inbound = TurvisInbound(TurvisConfig(TurvisIntegrationMode.TURVIS, URI(mockUrl), token, "EU-EE"), client()).process(body)

    assertEquals(routing, inbound.routing)
    assertEquals("EU-DE", inbound.signer.verifiedIdentity)
    assertEquals(payload, inbound.payloadXml)
    assertEquals(listOf("#MSG-ID", "#BODY-ID", "cid:message"), inbound.signatureDigests.map { it.referenceUri })
  }

  @Test fun `shadow comparison against the mock agrees with a matching legacy outcome and flags a different sender`() {
    needMock()
    val payload = "<hello>world</hello>"
    val gzipped = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(payload.toByteArray()) } }.toByteArray()
    val body = "--bnd\r\nContent-Type: application/soap+xml\r\n\r\n".toByteArray() + Base64.getDecoder().decode(envelope) +
      "\r\n--bnd\r\nContent-ID: <message>\r\n\r\n".toByteArray() + gzipped + "\r\n--bnd--\r\n".toByteArray()
    val config = TurvisConfig(TurvisIntegrationMode.SHADOW, URI(mockUrl), token, "EU-EE")
    val shadow = TurvisShadow(config, TurvisInbound(config, client()))
    fun observation(sender: String) = LegacyObservation().apply {
      stage = LegacyObservation.Stage.VERIFIED; senderId = PartyId(sender); receiverId = PartyId("EU-EE")
      conversationId = "conv-contract-0001"; attachment = gzipped; payloadXml = payload
    }
    fun security() = (klite.Metrics.data["edeliveryTurvisShadowSecurityDiscrepanciesTotal"] as Number?)?.toLong() ?: 0L

    val before = security()
    shadow.compare("contract-1", body, LegacyDecision.Accepted(observation("EU-DE")))
    assertEquals(before, security())

    shadow.compare("contract-2", body, LegacyDecision.Accepted(observation("EU-FR")))
    assertEquals(before + 1, security())
  }

  @Test fun `verify binds the sender with and without a claimed signer id`() {
    needMock()
    fun verify(claimed: String?) = client().verify(envelope, emptyList(), emptyList(), emptyList(), setOf(TurvisVerifyField.SIGNER), claimed)

    assertEquals("EU-DE", assertIs<TurvisResult.Accept<TurvisVerifyResponse>>(verify(null)).value.signer!!.verifiedIdentity)
    assertEquals("EU-DE", assertIs<TurvisResult.Accept<TurvisVerifyResponse>>(verify("EU-DE")).value.signer!!.verifiedIdentity)
    val mismatch = assertIs<TurvisResult.Reject>(verify("EU-FR")).rejection
    assertEquals(TurvisReason.SIGNER_IDENTITY_MISMATCH, mismatch.reason)
    assertIs<SignerIdentityMismatchException>(mismatch.toException())
  }

  @Test fun `sign returns the signed bytes the caller must send as-is, with receipt references for the policy`() {
    needMock()
    val result = assertIs<TurvisResult.Accept<TurvisSignResponse>>(
      client().sign(envelope, listOf("//eb:Messaging", "//env:Body"), listOf("cid:message"), setOf(TurvisSignField.SIGNATURES), listOf(TurvisAttachment("cid:message", "QUJD"))),
    ).value
    assertEquals(envelope, result.signedXmlB64)
    assertEquals(listOf("#MSG-ID", "#BODY-ID", "cid:message"), result.signatures!!.map { it.referenceUri })
  }

  @Test fun `encrypt in the plaintext profile returns empty fragments and the input bytes, wss-encrypted a stub`() {
    needMock()
    val plain = assertIs<TurvisResult.Accept<TurvisEncryptResponse>>(
      client().encrypt("QUJD", "EU-FR", TurvisEncryptProfile.PLAINTEXT, TurvisEncryptField.entries.toSet()),
    ).value
    assertEquals("QUJD", plain.encryptedPayloadB64)
    assertEquals("", plain.wrappedKeyXml)
    assertEquals("", plain.encryptedDataXml)
    assertEquals("", plain.wrappedKeyB64)

    val encrypted = assertIs<TurvisResult.Accept<TurvisEncryptResponse>>(
      client().encrypt("QUJD", "EU-FR", TurvisEncryptProfile.WSS_ENCRYPTED, setOf(TurvisEncryptField.WRAPPED_KEY_XML, TurvisEncryptField.ENCRYPTED_DATA_XML)),
    ).value
    assertTrue(encrypted.wrappedKeyXml!!.contains("<xenc:EncryptedKey"))
    assertTrue(encrypted.encryptedDataXml!!.contains("<xenc:EncryptedData"))
    assertEquals(null, encrypted.wrappedKeyB64)
  }

  @Test fun `a key handle other than the own gate id is a KEY_HANDLE_UNKNOWN reject on sign and decrypt`() {
    needMock()
    val sign = assertIs<TurvisResult.Reject>(client(keyHandle = "EU-XX").sign(envelope, listOf("//eb:Messaging"), emptyList(), emptySet())).rejection
    assertEquals(TurvisReason.KEY_HANDLE_UNKNOWN, sign.reason)
    val decrypt = assertIs<TurvisResult.Reject>(client(keyHandle = "EU-XX").decrypt(envelope, null, setOf(TurvisDecryptField.EBMS_ROUTING))).rejection
    assertEquals(TurvisReason.KEY_HANDLE_UNKNOWN, decrypt.reason)
    assertIs<KeyHandleUnknownException>(decrypt.toException())
  }

  @Test fun `a wrong token is a credential error, not a reject, and nothing is retried`() {
    needMock()
    assertFailsWith<TurvisAuthException> { client(token = "wrong").decrypt(envelope, null, setOf(TurvisDecryptField.EBMS_ROUTING)) }
  }

  @Test fun `an unreachable Turvis ends in TurvisUnavailableException after the retries`() {
    // Needs no mock: nothing listens on port 1, so every attempt is refused (about 400 ms of back-off in total).
    assertFailsWith<TurvisUnavailableException> { TurvisClient(TurvisConfig(TurvisIntegrationMode.TURVIS, URI("http://127.0.0.1:1"), token, "EU-EE"), http, json).decrypt(envelope, null, setOf(TurvisDecryptField.EBMS_ROUTING)) }
  }
}
