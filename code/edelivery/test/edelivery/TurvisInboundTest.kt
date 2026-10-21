package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.*
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import klite.base64Decode
import klite.base64Encode
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class TurvisInboundTest {
  private val client = mockk<TurvisClient>()
  private fun inbound(mode: TurvisIntegrationMode = TurvisIntegrationMode.TURVIS) =
    TurvisInbound(TurvisConfig(mode = mode, keyHandle = "EU-EE"), client)

  private val soapBytes = "<s:Envelope>\r\n <eb:Messaging/>\n</s:Envelope>".toByteArray()
  private val encryptedAttachment = ByteArray(64) { (it * 3).toByte() }
  private val decryptedSoap = "<s:Envelope><decrypted/></s:Envelope>".toByteArray()
  private val payload = "<hello>world</hello>"

  private fun gzip(s: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()
  private val decryptedAttachment = gzip(payload)

  private fun message(): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("--b1\r\nContent-Type: application/soap+xml\r\n\r\n".toByteArray()); out.write(soapBytes)
    out.write("\r\n--b1\r\nContent-ID: <message>\r\n\r\n".toByteArray()); out.write(encryptedAttachment)
    out.write("\r\n--b1--\r\n".toByteArray())
    return out.toByteArray()
  }

  private fun routing(from: String = "DE", to: String = "EU-EE") =
    TurvisEbmsRouting("m-1@x", "11111111-1111-1111-1111-111111111111", null, from, to, "svc", "act")

  private fun signer(identity: String = "DE") = TurvisSigner("CN=de", "ski", "01", identity)

  private val decryptResponse = TurvisDecryptResponse("dec-1", decryptedSoap.base64Encode(), decryptedAttachment.base64Encode(), null)

  private fun verifyResponse(
    from: String = "DE",
    identity: String = "DE",
    xpaths: List<String> = TurvisInbound.REQUIRED_SIGNED_XPATHS,
    cids: List<String> = TurvisInbound.REQUIRED_SIGNED_CID_REFS,
  ) = TurvisVerifyResponse("ver-1", signer(identity), xpaths, cids, listOf(TurvisSignatureReference("#id", null, "ZGlnZXN0", "SHA-256", emptyList())), routing(from))

  private fun reject(endpoint: String, code: String) =
    TurvisResult.Reject(TurvisRejection(endpoint, TurvisReason.of(code)!!.httpStatus, code, "aud-1", "detail from turvis"))

  private val decryptCall = slot<String>()
  private val attachmentCall = slot<String?>()
  private val decryptFields = slot<Set<TurvisDecryptField>>()
  private val verifySoap = slot<String>()
  private val verifyAttachments = slot<List<TurvisAttachment>>()
  private val verifyFields = slot<Set<TurvisVerifyField>>()
  private val verifyClaimed = slot<String?>()

  private fun stub(decrypt: TurvisResult<TurvisDecryptResponse> = TurvisResult.Accept(decryptResponse), verify: TurvisResult<TurvisVerifyResponse> = TurvisResult.Accept(verifyResponse())) {
    every { client.decrypt(capture(decryptCall), captureNullable(attachmentCall), capture(decryptFields), any()) } returns decrypt
    every { client.verify(capture(verifySoap), capture(verifyAttachments), any(), any(), capture(verifyFields), captureNullable(verifyClaimed), any(), any()) } returns verify
  }

  @Test fun `authoritative only in turvis mode`() {
    expect(inbound(TurvisIntegrationMode.TURVIS).authoritative).toEqual(true)
    expect(inbound(TurvisIntegrationMode.LEGACY).authoritative).toEqual(false)
    expect(inbound(TurvisIntegrationMode.SHADOW).authoritative).toEqual(false)
  }

  @Test fun `decrypt gets the received bytes exactly and is asked for no routing`() {
    stub()

    inbound().process(message())

    expect(decryptCall.captured.base64Decode().toList()).toEqual(soapBytes.toList())
    expect(attachmentCall.captured!!.base64Decode().toList()).toEqual(encryptedAttachment.toList())
    expect(decryptFields.captured).toEqual(TurvisInbound.DECRYPT_FIELDS)
    expect(decryptFields.captured).notToContain(TurvisDecryptField.EBMS_ROUTING)
  }

  @Test fun `verify gets the decrypt output verbatim with the policy of SPEC 9 2`() {
    stub()

    inbound().process(message())

    expect(verifySoap.captured).toEqual(decryptResponse.decryptedSoapXmlB64!!)
    expect(verifyAttachments.captured).toEqual(listOf(TurvisAttachment("cid:message", decryptResponse.decryptedAttachmentB64!!)))
    verify { client.verify(any(), any(), listOf("//eb:Messaging", "//env:Body"), listOf("cid:message"), any(), any(), any(), any()) }
  }

  @Test fun `routing and sender come from verify, never from decrypt`() {
    val decryptWithLyingRouting = decryptResponse.copy(ebmsRouting = routing(from = "MALLORY", to = "ELSEWHERE"))
    stub(decrypt = TurvisResult.Accept(decryptWithLyingRouting))

    val result = inbound().process(message())

    expect(result.routing).toEqual(routing())
    expect(result.signer.verifiedIdentity).toEqual("DE")
    expect(result.payloadXml).toEqual(payload)
    expect(result.decryptAuditRef).toEqual("dec-1")
    expect(result.verifyAuditRef).toEqual("ver-1")
    expect(result.signatureDigests.size).toEqual(1)
  }

  @Test fun `claimed signer id is not sent, it cannot be known before the envelope is verified`() {
    stub()

    inbound().process(message())

    verify { client.verify(any(), any(), any(), any(), any(), null, any(), any()) }
  }

  @Test fun `sender binding is case-insensitive like PartyId`() {
    stub(verify = TurvisResult.Accept(verifyResponse(from = "de", identity = "DE")))

    expect(inbound().process(message()).signer.verifiedIdentity).toEqual("DE")
  }

  @Test fun `envelope sender that is not the verified signer is rejected`() {
    stub(verify = TurvisResult.Accept(verifyResponse(from = "FR", identity = "DE")))

    val ex = runCatching { inbound().process(message()) }.exceptionOrNull()!!

    expect(ex).toBeAnInstanceOf<SignerIdentityMismatchException>()
    expect(FaultReason.of(ex)).toEqual(FaultReason.SIGNER_IDENTITY_MISMATCH)
  }

  @Test fun `verify that does not cover the required xpaths is rejected`() {
    stub(verify = TurvisResult.Accept(verifyResponse(xpaths = listOf("//eb:Messaging"))))

    expect { inbound().process(message()) }.toThrow<SignaturePolicyException>()
  }

  @Test fun `verify that does not cover the attachment is rejected`() {
    stub(verify = TurvisResult.Accept(verifyResponse(cids = emptyList())))

    expect { inbound().process(message()) }.toThrow<SignaturePolicyException>()
  }

  @Test fun `decrypt reject ends the message and verify is never called`() {
    stub(decrypt = reject("decryptions", "SKI_MISMATCH"))

    expect { inbound().process(message()) }.toThrow<RecipientMismatchException>()

    verify(exactly = 0) { client.verify(any(), any(), any(), any(), any(), any(), any(), any()) }
  }

  @Test fun `verify reject propagates as its own exception`() {
    stub(verify = reject("verifications", "SIGNER_UNTRUSTED"))

    expect { inbound().process(message()) }.toThrow<SignerUntrustedException>()
  }

  @Test fun `a transient Turvis failure is not turned into a peer fault`() {
    every { client.decrypt(any(), any(), any(), any()) } throws TurvisUnavailableException("down")

    expect { inbound().process(message()) }.toThrow<TurvisUnavailableException>()
  }

  @Test fun `malformed multipart never reaches Turvis`() {
    expect { inbound().process("not multipart".toByteArray()) }.toThrow<IllegalArgumentException>()
    expect { inbound().process("--b1\r\nX: 1\r\n\r\nonly one\r\n--b1--\r\n".toByteArray()) }.toThrow<IllegalArgumentException>()
    expect { inbound().process("--b1\r\n\r\na\r\n--b1\r\n\r\nb\r\n--b1\r\n\r\nc\r\n--b1--\r\n".toByteArray()) }.toThrow<IllegalArgumentException>()

    verify(exactly = 0) { client.decrypt(any(), any(), any(), any()) }
  }

  @Test fun `attachment that is not gzip is a parse failure, not a crash`() {
    stub(decrypt = TurvisResult.Accept(decryptResponse.copy(decryptedAttachmentB64 = "plain, not gzip".toByteArray().base64Encode())))

    val ex = runCatching { inbound().process(message()) }.exceptionOrNull()!!

    expect(ex).toBeAnInstanceOf<java.util.zip.ZipException>()
    expect(FaultReason.of(ex)).toEqual(FaultReason.XML_PARSE_FAIL)
  }

  @Test fun `decompressed payload over the limit is rejected`() {
    val big = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(ByteArray(11 * 1024 * 1024)) } }.toByteArray()
    stub(decrypt = TurvisResult.Accept(decryptResponse.copy(decryptedAttachmentB64 = big.base64Encode())))

    expect { inbound().process(message()) }.toThrow<PayloadTooLargeException>()
  }
}
