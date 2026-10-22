package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.*
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import klite.Metrics
import klite.register
import klite.base64Decode
import klite.base64Encode
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TurvisOutboundTest {
  private val client = mockk<TurvisClient>()
  private val keyManager = mockk<KeyManager> { every { partyId } returns PartyId("EU-EE") }
  private val generator = EDeliveryMessageGenerator(keyManager)
  private fun outbound(mode: TurvisIntegrationMode = TurvisIntegrationMode.TURVIS, allowUnsignedEcho: Boolean = false) =
    TurvisOutbound(TurvisConfig(mode = mode, keyHandle = "EU-EE"), client, generator).also { it.allowUnsignedEcho = allowUnsignedEcho }

  private val params = UserMessageParams(RequestKey(PartyId("EU-FR"), senderId = PartyId("EU-EE")))
  private val payload = "<hello>world</hello>"
  private val wrappedKey = """<xenc:EncryptedKey xmlns:xenc="http://www.w3.org/2001/04/xmlenc#" Id="EK-ID"> <xenc:CipherData/> </xenc:EncryptedKey>"""
  private val encryptedData = """<xenc:EncryptedData xmlns:xenc="http://www.w3.org/2001/04/xmlenc#" Id="ED-ID"/>"""
  private val wirePayload = ByteArray(40) { (it * 7).toByte() }
  // What a real signer might put in: mixed line endings, trailing space, control bytes. None of it may be touched.
  private val signature = "<ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">\r\n  <x/> \n".toByteArray() + byteArrayOf(0x0b, 0x7f) + "</ds:Signature>".toByteArray()
  private fun withSignature(unsigned: ByteArray, at: String = "</wsse:Security>", sig: ByteArray = signature): ByteArray {
    val text = String(unsigned)
    val i = text.lastIndexOf(at)
    return text.substring(0, i).toByteArray() + sig + text.substring(i).toByteArray()
  }
  private var lastSigned = ByteArray(0)

  private val encryptPlaintext = slot<String>()
  private val encryptReceiver = slot<String>()
  private val encryptProfile = slot<TurvisEncryptProfile>()
  private val encryptFields = slot<Set<TurvisEncryptField>>()
  private val signSoap = slot<String>()
  private val signXpaths = slot<List<String>>()
  private val signCids = slot<List<String>>()
  private val signFields = slot<Set<TurvisSignField>>()
  private val signAttachments = slot<List<TurvisAttachment>>()

  private fun stub(
    encrypt: TurvisResult<TurvisEncryptResponse> = TurvisResult.Accept(TurvisEncryptResponse("enc-1", wirePayload.base64Encode(), "a2V5", wrappedKey, encryptedData)),
    sign: ((ByteArray) -> TurvisResult<TurvisSignResponse>) = { unsigned -> TurvisResult.Accept(TurvisSignResponse("sig-1", withSignature(unsigned).also { lastSigned = it }.base64Encode(), null)) },
  ) {
    every { client.encrypt(capture(encryptPlaintext), capture(encryptReceiver), capture(encryptProfile), capture(encryptFields), any()) } returns encrypt
    every { client.sign(capture(signSoap), capture(signXpaths), capture(signCids), capture(signFields), capture(signAttachments), any()) } answers { sign(signSoap.captured.base64Decode()) }
  }

  private fun failures(reason: String) = ((Metrics.data["edeliveryOutboundTurvisFailuresTotal"] as Map<*, *>?)?.get(reason) as Number?)?.toLong() ?: 0L

  @Test fun `the registry can create it from the registered dependencies, as Launcher does`() {
    val registry = klite.DependencyInjectingRegistry()
    registry.register(TurvisConfig(mode = TurvisIntegrationMode.LEGACY, keyHandle = "EU-EE"))
    registry.register(client)
    registry.register(generator)
    registry.register<TurvisOutbound>()

    expect(registry.require(TurvisOutbound::class).authoritative).toEqual(false)
  }

  @Test fun `authoritative only in turvis mode`() {
    expect(outbound(TurvisIntegrationMode.TURVIS).authoritative).toEqual(true)
    expect(outbound(TurvisIntegrationMode.LEGACY).authoritative).toEqual(false)
    expect(outbound(TurvisIntegrationMode.SHADOW).authoritative).toEqual(false)
  }

  @Test fun `the signed envelope reaches the caller byte for byte and the wire attachment is the encrypted payload`() {
    stub()

    val message = outbound().requestMessage(params, payload)

    assertContentEquals(lastSigned, message.signedSoap)
    assertContentEquals(wirePayload, message.encryptedAttachment)
    expect(message.encryptAuditRef).toEqual("enc-1")
    expect(message.signAuditRef).toEqual("sig-1")
  }

  @Test fun `the same gzip bytes are encrypted and signed, so the cid digest covers gzip of the plaintext`() {
    stub()

    outbound().requestMessage(params, payload)

    expect(signAttachments.captured).toEqual(listOf(TurvisAttachment("cid:message", encryptPlaintext.captured)))
    val unzipped = GZIPInputStream(ByteArrayInputStream(encryptPlaintext.captured.base64Decode())).readBytes()
    expect(String(unzipped)).toEqual(payload)
  }

  @Test fun `encrypt is asked for the receiver, the fragments and nothing else, sign for the signing policy`() {
    stub()

    outbound().requestMessage(params, payload)

    expect(encryptReceiver.captured).toEqual("EU-FR")
    expect(encryptProfile.captured).toEqual(TurvisEncryptProfile.WSS_ENCRYPTED)
    expect(encryptFields.captured).toEqual(setOf(TurvisEncryptField.WRAPPED_KEY_XML, TurvisEncryptField.ENCRYPTED_DATA_XML))
    expect(signXpaths.captured).toEqual(listOf("//eb:Messaging", "//env:Body"))
    expect(signCids.captured).toEqual(listOf("cid:message"))
    expect(signFields.captured).toEqual(emptySet())
  }

  @Test fun `the unsigned envelope carries Turvis's fragments verbatim in the security header`() {
    stub()

    outbound().requestMessage(params, payload)

    val envelope = String(signSoap.captured.base64Decode())
    expect(envelope).toContain("""<wsse:Security xmlns:wsse="$wsseNs" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" env:mustUnderstand="true">$wrappedKey$encryptedData</wsse:Security>""")
    expect(envelope).toContain("""wsu:Id="MSG-ID"""")
    expect(envelope).toContain("""wsu:Id="BODY-ID"""")
    expect(envelope).toContain("<eb:PartyId type=\"urn:oasis:names:tc:ebcore:partyid-type:unregistered\">EU-FR</eb:PartyId>")
    expect(envelope).notToContain("<ds:Signature")
  }

  @Test fun `the plaintext profile takes the same path and splices nothing`() {
    stub(encrypt = TurvisResult.Accept(TurvisEncryptResponse("enc-1", wirePayload.base64Encode(), "", "", "")))

    val message = outbound().requestMessage(params, payload, TurvisEncryptProfile.PLAINTEXT)

    expect(encryptProfile.captured).toEqual(TurvisEncryptProfile.PLAINTEXT)
    expect(String(signSoap.captured.base64Decode())).toContain("env:mustUnderstand=\"true\"></wsse:Security>")
    assertContentEquals(lastSigned, message.signedSoap)
  }

  @Test fun `a rejected encrypt sends nothing, never reaches sign, and is counted`() {
    stub(encrypt = TurvisResult.Reject(TurvisRejection("encryptions", 422, "RECEIVER_CERT_EXPIRED", "aud-9", "detail from turvis")))
    val before = failures("receiver_cert_expired")

    assertFailsWith<ReceiverCertExpiredException> { outbound().requestMessage(params, payload) }

    verify(exactly = 0) { client.sign(any(), any(), any(), any(), any(), any()) }
    expect(failures("receiver_cert_expired")).toEqual(before + 1)
  }

  @Test fun `a rejected sign is rethrown and counted under its reason code`() {
    stub(sign = { TurvisResult.Reject(TurvisRejection("signatures", 422, "SIG_BUILD_FAIL", "aud-9", null)) })
    val before = failures("sig_build_fail")

    assertFailsWith<OutboundSignBuildException> { outbound().requestMessage(params, payload) }

    expect(failures("sig_build_fail")).toEqual(before + 1)
  }

  @Test fun `an unknown reason code is counted in one bucket`() {
    stub(sign = { TurvisResult.Reject(TurvisRejection("signatures", 422, "SOMETHING_NEW", "aud-9", null)) })
    val before = failures("unrecognised")

    assertFailsWith<UnrecognisedRejectException> { outbound().requestMessage(params, payload) }

    expect(failures("unrecognised")).toEqual(before + 1)
  }

  @Test fun `Turvis down, a broken contract and a refused credential are each counted and rethrown`() {
    listOf<Triple<Exception, String, Class<out Exception>>>(
      Triple(TurvisUnavailableException("down"), "turvis_unavailable", TurvisUnavailableException::class.java),
      Triple(TurvisProtocolException("shape"), "turvis_protocol", TurvisProtocolException::class.java),
      Triple(TurvisAuthException(401, "encryptions"), "turvis_auth", TurvisAuthException::class.java),
    ).forEach { (failure, reason, type) ->
      every { client.encrypt(any(), any(), any(), any(), any()) } throws failure
      val before = failures(reason)

      val thrown = assertFailsWith<Exception> { outbound().requestMessage(params, payload) }

      assertEquals(type, thrown.javaClass)
      expect(failures(reason)).toEqual(before + 1)
    }
  }

  @Test fun `an unexpected error is counted as internal and rethrown`() {
    every { client.encrypt(any(), any(), any(), any(), any()) } throws IllegalStateException("boom")
    val before = failures("internal")

    assertFailsWith<IllegalStateException> { outbound().requestMessage(params, payload) }

    assertEquals(before + 1, failures("internal"))
  }

  private fun refused(sign: (ByteArray) -> ByteArray) {
    stub(sign = { unsigned -> TurvisResult.Accept(TurvisSignResponse("sig-1", sign(unsigned).base64Encode(), null)) })
    val before = failures("turvis_protocol")

    assertFailsWith<TurvisProtocolException> { outbound().requestMessage(params, payload) }

    expect(failures("turvis_protocol")).toEqual(before + 1)
  }

  @Test fun `a signature in front of the encryption block is refused`() = refused { withSignature(it, at = "$wrappedKey$encryptedData") }

  @Test fun `a signature after the security header is refused`() = refused { withSignature(it, at = "</env:Header>") }

  @Test fun `a byte changed in eb Messaging is refused`() = refused { withSignature(String(it).replace("<eb:Messaging", "<eb:Messagin2").toByteArray()) }

  @Test fun `a byte changed after the security header is refused`() = refused { withSignature(it).also { b -> b[b.size - 3] = 'X'.code.toByte() } }

  @Test fun `an inserted element that is not a signature is refused`() = refused { withSignature(it, sig = "<ds:Other/>".toByteArray()) }

  @Test fun `an unchanged answer is refused for a real Turvis`() = refused { it }

  @Test fun `echo is allowed by default only for the turvis-mock host`() {
    fun default(url: String) = TurvisOutbound(TurvisConfig(TurvisIntegrationMode.TURVIS, java.net.URI(url), "t", "EU-EE"), client, generator).allowUnsignedEcho
    expect(default("http://turvis-mock:8091")).toEqual(true)
    expect(default("http://turvis:8080")).toEqual(false)
    expect(default("http://127.0.0.1:8091")).toEqual(false)
  }

  @Test fun `a fragment that contains the closing security tag still passes`() {
    val tricky = "<xenc:EncryptedData Id=\"ED-ID\">x</wsse:Security>y</xenc:EncryptedData>"
    stub(encrypt = TurvisResult.Accept(TurvisEncryptResponse("enc-1", wirePayload.base64Encode(), "a2V5", wrappedKey, tricky)))

    val message = outbound().requestMessage(params, payload)

    assertContentEquals(lastSigned, message.signedSoap)
  }

  @Test fun `an answer identical to the input, which is what turvis-mock returns, is let through for the mock host`() {
    stub(sign = { unsigned -> TurvisResult.Accept(TurvisSignResponse("sig-1", unsigned.base64Encode(), null)) })

    val message = outbound(allowUnsignedEcho = true).requestMessage(params, payload)

    expect(String(message.signedSoap)).toContain("<wsse:Security")
  }
}
