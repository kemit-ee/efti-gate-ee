package edelivery

import io.mockk.every
import io.mockk.mockk
import klite.Metrics
import klite.json.JsonMapper
import klite.json.parse
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Flow
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurvisClientTest {
  private val json = JsonMapper()
  private val config = TurvisConfig(TurvisIntegrationMode.TURVIS, URI("http://turvis:8080/"), "dev-turvis-token", "EU-EE")
  private val http = mockk<HttpClient>()
  private val sleeps = mutableListOf<Long>()
  private val requests = mutableListOf<HttpRequest>()
  private val bodies = mutableListOf<String>()
  private val script = ArrayDeque<Any>()
  private val client = TurvisClient(config, http, json, sleeper = { sleeps += it }, newTraceId = { "0123456789abcdef0123456789abcdef" })

  init {
    every { http.send(any(), any<BodyHandler<String>>()) } answers {
      val request = firstArg<HttpRequest>()
      requests += request
      bodies += request.bodyPublisher().get().let(::readBody)
      when (val next = script.removeFirstOrNull() ?: error("no scripted answer left")) {
        is Throwable -> throw next
        else -> next as HttpResponse<String>
      }
    }
  }

  private fun readBody(publisher: HttpRequest.BodyPublisher): String {
    val out = StringBuilder()
    publisher.subscribe(object : Flow.Subscriber<java.nio.ByteBuffer> {
      override fun onSubscribe(s: Flow.Subscription) = s.request(Long.MAX_VALUE)
      override fun onNext(item: java.nio.ByteBuffer) { out.append(Charsets.UTF_8.decode(item)) }
      override fun onError(t: Throwable) = throw t
      override fun onComplete() {}
    })
    return out.toString()
  }

  private fun answer(status: Int = 200, body: String): HttpResponse<String> = mockk {
    every { statusCode() } returns status
    every { body() } returns body
  }

  private fun reject(status: Int, code: String, auditRef: String = "00000000-0000-4000-8000-000000000001", detail: String = "mock detail") =
    answer(status, """{"type":"https://efti.example/problem/x","title":"t","status":$status,"verdict":"reject","reason_code":"$code","detail":"$detail","audit_ref":"$auditRef"}""")

  private val audit = "11111111-1111-4111-8111-111111111111"
  private val routingJson = """{"message_id":"m-1","conversation_id":"c-1","ref_to_message_id":"","from_party_id":"EU-DE","to_party_id":"EU-EE","service":"FTI010","action":"FTI010Response"}"""
  private val routing = TurvisEbmsRouting("m-1", "c-1", "", "EU-DE", "EU-EE", "FTI010", "FTI010Response")
  private val referencesJson = """[{"reference_uri":"#MSG-ID","subtree_xpath":"//eb:Messaging","digest_b64":"ZA==","algorithm":"SHA-256","transforms":["http://www.w3.org/2001/10/xml-exc-c14n#"]},
    {"reference_uri":"cid:message","digest_b64":"YQ==","algorithm":"SHA-256","transforms":["http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform"]}]"""

  private fun verifyCall(fields: Set<TurvisVerifyField> = setOf(TurvisVerifyField.SIGNER), claimed: String? = null) =
    client.verify("U09BUA==", listOf(TurvisAttachment("cid:message", "QUJD")), listOf("//eb:Messaging", "//env:Body"), listOf("cid:message"), fields, claimed)

  private fun body(index: Int = 0) = json.parse<Map<String, Any?>>(bodies[index])

  private val verifyAccept = """{"verdict":"accept","audit_ref":"$audit","signer":{"cert_subject_dn":"CN=x","cert_ski":"00","cert_serial":"1","verified_identity":"EU-DE"}}"""

  // ---------------------------------------------------------------- request shape

  @Test fun `decrypt posts the as-received bytes with the dedicated token, own key handle and declared fields`() {
    script += answer(body = """{"audit_ref":"$audit","ebms_routing":$routingJson}""")

    client.decrypt("U09BUA==", "QUJD", setOf(TurvisDecryptField.EBMS_ROUTING))

    val request = requests.single()
    assertEquals("POST", request.method())
    assertEquals(URI("http://turvis:8080/turvis/v1/decryptions/xmlenc"), request.uri())
    assertEquals("application/json", request.headers().firstValue("Content-Type").get())
    assertEquals("dev-turvis-token", request.headers().firstValue("X-Internal-Service-Token").get())
    assertEquals(Duration.ofSeconds(10), request.timeout().get())
    assertEquals(
      mapOf("soap_xml_b64" to "U09BUA==", "encrypted_attachment_b64" to "QUJD", "key_handle" to "EU-EE", "return_fields" to listOf("ebms_routing"), "trace_id" to "0123456789abcdef0123456789abcdef"),
      body(),
    )
  }

  @Test fun `decrypt omits the attachment when the message has none`() {
    script += answer(body = """{"audit_ref":"$audit","decrypted_soap_xml_b64":"x"}""")
    client.decrypt("U09BUA==", null, setOf(TurvisDecryptField.DECRYPTED_SOAP_XML_B64))
    assertFalse("encrypted_attachment_b64" in body())
  }

  @Test fun `verify sends policy, attachments and claimed signer only when given`() {
    script.addAll(listOf(answer(body = verifyAccept), answer(body = verifyAccept)))

    verifyCall()
    verifyCall(claimed = "EU-DE")

    val first = body(0)
    assertEquals(URI("http://turvis:8080/turvis/v1/verifications/xmldsig"), requests[0].uri())
    assertEquals(listOf(mapOf("cid" to "cid:message", "bytes_b64" to "QUJD")), first["attachments"])
    assertEquals(listOf("//eb:Messaging", "//env:Body"), first["required_signed_xpaths"])
    assertEquals(listOf("cid:message"), first["required_signed_cid_refs"])
    assertFalse("claimed_signer_id" in first)
    assertFalse("trust_store_version" in first)
    assertEquals("EU-DE", body(1)["claimed_signer_id"])
  }

  @Test fun `encrypt sends receiver and profile, sign sends policy, key handle and attachment bytes`() {
    script += answer(body = """{"encrypted_payload_b64":"QUJD","audit_ref":"$audit"}""")
    script += answer(body = """{"signed_xml_b64":"U0lH","audit_ref":"$audit"}""")

    client.encrypt("UEw=", "EU-FR", TurvisEncryptProfile.WSS_ENCRYPTED, emptySet())
    client.sign("U09BUA==", listOf("//eb:Messaging"), listOf("cid:message"), emptySet(), listOf(TurvisAttachment("cid:message", "QUJD")))

    assertEquals(URI("http://turvis:8080/turvis/v1/encryptions/xmlenc"), requests[0].uri())
    assertEquals(mapOf("plaintext_bytes_b64" to "UEw=", "receiver_party_id" to "EU-FR", "profile" to "wss-encrypted", "return_fields" to emptyList<String>(), "trace_id" to "0123456789abcdef0123456789abcdef"), body(0))
    assertEquals(URI("http://turvis:8080/turvis/v1/signatures/xmldsig"), requests[1].uri())
    val sign = body(1)
    assertEquals("EU-EE", sign["key_handle"])
    assertEquals(listOf(mapOf("cid" to "cid:message", "bytes_b64" to "QUJD")), sign["attachment_bytes_b64"])
  }

  @Test fun `a trailing slash on the base URL does not double up`() {
    script += answer(body = """{"audit_ref":"$audit","decrypted_soap_xml_b64":"x"}""")
    client.decrypt("U09BUA==", null, setOf(TurvisDecryptField.DECRYPTED_SOAP_XML_B64))
    assertEquals("/turvis/v1/decryptions/xmlenc", requests.single().uri().path)
  }

  @Test fun `a trace id is generated as 32 hex characters when the caller gives none`() {
    assertTrue(Regex("[0-9a-f]{32}").matches(randomTraceId()))
    assertNotEquals(randomTraceId(), randomTraceId())
  }

  // ---------------------------------------------------------------- accept shapes

  @Test fun `a verify accept carries signer, routing and the original references for the receipt`() {
    script += answer(body = """{"verdict":"accept","audit_ref":"$audit","signer":{"cert_subject_dn":"CN=EU-DE","cert_ski":"ab","cert_serial":"7","verified_identity":"EU-DE"},
      "matched_signed_xpaths":["//eb:Messaging"],"matched_signed_cid_refs":["cid:message"],"signature_digests":$referencesJson,"ebms_routing":$routingJson}""")

    val result = verifyCall(TurvisVerifyField.entries.toSet())

    val accept = assertIs<TurvisResult.Accept<TurvisVerifyResponse>>(result).value
    assertEquals(audit, accept.auditRef)
    assertEquals(TurvisSigner("CN=EU-DE", "ab", "7", "EU-DE"), accept.signer)
    assertEquals(listOf("//eb:Messaging"), accept.matchedSignedXpaths)
    assertEquals(listOf("cid:message"), accept.matchedSignedCidRefs)
    assertEquals(routing, accept.ebmsRouting)
    assertEquals(
      listOf(
        TurvisSignatureReference("#MSG-ID", "//eb:Messaging", "ZA==", "SHA-256", listOf("http://www.w3.org/2001/10/xml-exc-c14n#")),
        TurvisSignatureReference("cid:message", null, "YQ==", "SHA-256", listOf("http://docs.oasis-open.org/wss/oasis-wss-SwAProfile-1.1#Attachment-Content-Signature-Transform")),
      ),
      accept.signatureDigests,
    )
  }

  @Test fun `fields that were not requested stay null in the typed response`() {
    script += answer(body = verifyAccept)
    val accept = assertIs<TurvisResult.Accept<TurvisVerifyResponse>>(verifyCall()).value
    assertNull(accept.ebmsRouting)
    assertNull(accept.signatureDigests)
    assertNull(accept.matchedSignedXpaths)
  }

  @Test fun `a plaintext encrypt accept has empty fragments and an empty key, not null`() {
    script += answer(body = """{"encrypted_payload_b64":"QUJD","audit_ref":"$audit","wrapped_key_xml":"","encrypted_data_xml":"","wrapped_key_b64":""}""")

    val accept = assertIs<TurvisResult.Accept<TurvisEncryptResponse>>(
      client.encrypt("QUJD", "EU-FR", TurvisEncryptProfile.PLAINTEXT, TurvisEncryptField.entries.toSet()),
    ).value

    assertEquals("QUJD", accept.encryptedPayloadB64)
    assertEquals("", accept.wrappedKeyXml)
    assertEquals("", accept.encryptedDataXml)
    assertEquals("", accept.wrappedKeyB64)
  }

  @Test fun `strings come back exactly as sent, without trimming and with an empty string kept, whatever the shared mapper does`() {
    assertTrue(json.trimToNull, "the default mapper is the one that would trim")
    script += answer(body = """{"encrypted_payload_b64":"QUJD","audit_ref":"$audit","wrapped_key_xml":"  <k/>\n  ","encrypted_data_xml":"","wrapped_key_b64":" "}""")

    val accept = assertIs<TurvisResult.Accept<TurvisEncryptResponse>>(
      client.encrypt("QUJD", "EU-FR", TurvisEncryptProfile.WSS_ENCRYPTED, TurvisEncryptField.entries.toSet()),
    ).value

    assertEquals("  <k/>\n  ", accept.wrappedKeyXml)
    assertEquals("", accept.encryptedDataXml)
    assertEquals(" ", accept.wrappedKeyB64)
  }

  @Test fun `a sign accept returns the signed bytes untouched`() {
    script += answer(body = """{"signed_xml_b64":"PHNpZ25lZCAgIC8+DQo=","audit_ref":"$audit","signatures":$referencesJson}""")

    val accept = assertIs<TurvisResult.Accept<TurvisSignResponse>>(
      client.sign("U09BUA==", listOf("//eb:Messaging"), emptyList(), setOf(TurvisSignField.SIGNATURES)),
    ).value

    assertEquals("PHNpZ25lZCAgIC8+DQo=", accept.signedXmlB64) // not decoded, not trimmed, not normalised
    assertEquals(2, accept.signatures!!.size)
  }

  @Test fun `a decrypt accept keeps routing diagnostic and passes bytes through`() {
    script += answer(body = """{"audit_ref":"$audit","decrypted_soap_xml_b64":"U09BUA==","decrypted_attachment_b64":"","ebms_routing":$routingJson}""")
    val accept = assertIs<TurvisResult.Accept<TurvisDecryptResponse>>(client.decrypt("U09BUA==", null, TurvisDecryptField.entries.toSet())).value
    assertEquals("U09BUA==", accept.decryptedSoapXmlB64)
    assertEquals("", accept.decryptedAttachmentB64)
    assertEquals(routing, accept.ebmsRouting)
  }

  // ---------------------------------------------------------------- a 200 is only trusted in the contract's shape

  @Test fun `a 200 with a field that was not requested is refused (response stuffing)`() {
    script += answer(body = """{"verdict":"accept","audit_ref":"$audit","signer":{"cert_subject_dn":"a","cert_ski":"b","cert_serial":"1","verified_identity":"EU-DE"},"ebms_routing":$routingJson}""")
    val e = assertFailsWith<TurvisProtocolException> { verifyCall() }
    assertContains(e.message!!, "unrequested field 'ebms_routing'")
  }

  @Test fun `a 200 that lacks a requested field is refused`() {
    script += answer(body = """{"verdict":"accept","audit_ref":"$audit"}""")
    val e = assertFailsWith<TurvisProtocolException> { verifyCall() }
    assertContains(e.message!!, "lacks the field 'signer'")
  }

  @Test fun `a 200 without audit_ref, with an empty one or without verdict accept is refused`() {
    script += answer(body = """{"verdict":"accept","signer":{"cert_subject_dn":"a","cert_ski":"b","cert_serial":"1","verified_identity":"x"}}""")
    assertFailsWith<TurvisProtocolException> { verifyCall() }
    script += answer(body = """{"verdict":"accept","audit_ref":"","signer":{"cert_subject_dn":"a","cert_ski":"b","cert_serial":"1","verified_identity":"x"}}""")
    assertFailsWith<TurvisProtocolException> { verifyCall() }
    script += answer(body = """{"verdict":"reject","audit_ref":"$audit","signer":{"cert_subject_dn":"a","cert_ski":"b","cert_serial":"1","verified_identity":"x"}}""")
    assertFailsWith<TurvisProtocolException> { verifyCall() }
  }

  @Test fun `a 200 with a malformed or non-object body, or a wrongly typed field, is refused`() {
    script += answer(body = "not json")
    assertFailsWith<TurvisProtocolException> { verifyCall() }
    script += answer(body = "[1,2]")
    assertFailsWith<TurvisProtocolException> { verifyCall() }
    script += answer(body = """{"verdict":"accept","audit_ref":"$audit","signer":"EU-DE"}""")
    assertFailsWith<TurvisProtocolException> { verifyCall() }
    script += answer(body = """{"audit_ref":"$audit","decrypted_soap_xml_b64":42}""")
    assertFailsWith<TurvisProtocolException> { client.decrypt("U09BUA==", null, setOf(TurvisDecryptField.DECRYPTED_SOAP_XML_B64)) }
    assertEquals(4, requests.size)
  }

  @Test fun `an engine 400 or an unexpected status without a reason code is a protocol error and is not retried`() {
    script += answer(400, """{"error":"Field missing: soap_xml_b64"}""")
    assertFailsWith<TurvisProtocolException> { verifyCall() }
    script += answer(503, """{"error":"overloaded"}""")
    assertFailsWith<TurvisProtocolException> { verifyCall() }
    script += answer(502, "<html>bad gateway</html>")
    assertFailsWith<TurvisProtocolException> { verifyCall() }
    assertEquals(3, requests.size)
    assertTrue(sleeps.isEmpty())
  }

  @Test fun `a credential refusal is its own error and is not retried`() {
    script += answer(401, """{"title":"Authentication required","status":401}""")
    val e = assertFailsWith<TurvisAuthException> { verifyCall() }
    assertEquals(401, e.httpStatus)
    script += answer(403, "{}")
    assertFailsWith<TurvisAuthException> { verifyCall() }
    assertEquals(2, requests.size)
    assertTrue(sleeps.isEmpty())
  }

  // ---------------------------------------------------------------- rejects

  @Test fun `every reason code of the contract maps to a Reject with its exception and fault`() {
    for (reason in TurvisReason.entries) {
      repeat(if (reason.transient) 3 else 1) { script += reject(reason.httpStatus, reason.code) }

      val result = verifyCall()

      val rejection = assertIs<TurvisResult.Reject>(result, reason.code).rejection
      assertEquals(reason.code, rejection.reasonCode)
      assertEquals(reason, rejection.reason)
      assertEquals(reason, rejection.effectiveReason)
      assertEquals(reason.httpStatus, rejection.httpStatus)
      val exception = rejection.toException()
      assertEquals(reason.code, exception.reasonCode)
      assertEquals("00000000-0000-4000-8000-000000000001", exception.auditRef)
      assertEquals(reason, TurvisReason.of(reason.code))
    }
  }

  @Test fun `exception messages carry the reason, endpoint and audit_ref but never Turvis's detail text`() {
    script += reject(422, "SIG_POLICY_FAIL", detail = "secret-fragment-of-the-envelope")
    val e = assertFailsWith<SignaturePolicyException> { verifyCall().orThrow() }
    assertContains(e.message!!, "SIG_POLICY_FAIL")
    assertContains(e.message!!, "verifications")
    assertContains(e.message!!, "00000000-0000-4000-8000-000000000001")
    assertFalse(e.message!!.contains("secret-fragment"))
  }

  @Test fun `an unknown but well-formed reason code is a reject that fails closed as SIG_CRYPTO_FAIL`() {
    script += reject(422, "SOMETHING_NEW")

    val rejection = assertIs<TurvisResult.Reject>(verifyCall()).rejection

    assertNull(rejection.reason)
    assertEquals(TurvisReason.SIG_CRYPTO_FAIL, rejection.effectiveReason)
    assertIs<UnrecognisedRejectException>(rejection.toException())
  }

  @Test fun `a malformed reason code is not a reject body at all`() {
    for (code in listOf("lower_case", "WITH SPACE", "A".repeat(65), "<script>")) {
      script += reject(422, code.replace("\"", ""))
      assertFailsWith<TurvisProtocolException>(code) { verifyCall() }
    }
  }

  @Test fun `the inbound handling follows the contract table`() {
    val faults = TurvisReason.entries.filter { it.answeredWithFault }.associate { it.code to "${it.ebmsCode} ${it.ebmsShortDescription}" }
    assertEquals(
      mapOf(
        "XML_PARSE_FAIL" to "EBMS:0004 InvalidHeader", "XML_ATTACK_DETECTED" to "EBMS:0004 InvalidHeader",
        "SIG_MISSING" to "EBMS:0101 SignatureFailure", "SIG_CRYPTO_FAIL" to "EBMS:0101 SignatureFailure", "SIG_POLICY_FAIL" to "EBMS:0101 SignatureFailure",
        "SIG_ALGO_UNSUPPORTED" to "EBMS:0101 SignatureFailure", "SIGNER_UNTRUSTED" to "EBMS:0101 SignatureFailure", "SIGNER_IDENTITY_MISMATCH" to "EBMS:0101 SignatureFailure",
        "WRAPPED_KEY_MALFORMED" to "EBMS:0102 DecryptionFailure", "SKI_MISMATCH" to "EBMS:0102 DecryptionFailure", "AES_UNWRAP_FAIL" to "EBMS:0102 DecryptionFailure",
        "PAYLOAD_DECRYPT_FAIL" to "EBMS:0102 DecryptionFailure", "PAYLOAD_TOO_LARGE" to "EBMS:0003 ValueInconsistent", "ALGO_UNSUPPORTED" to "EBMS:0003 ValueInconsistent",
      ),
      faults,
    )
    // No ebMS fault for internal, transient, outbound-only and programmer-error reasons: HTTP 503 to the peer (SPEC 13.2).
    for (code in listOf("TURVIS_INTERNAL", "KEY_UNAVAILABLE", "TRUST_STORE_UNAVAILABLE", "UNKNOWN_RETURN_FIELD", "KEY_HANDLE_UNKNOWN", "SIG_BUILD_FAIL", "RECEIVER_UNKNOWN")) {
      assertFalse(TurvisReason.of(code)!!.answeredWithFault, code)
    }
  }

  @Test fun `orThrow returns the value of an accept and throws the mapped exception for a reject`() {
    script += answer(body = verifyAccept)
    assertEquals(audit, verifyCall().orThrow().auditRef)
    script += reject(422, "SIGNER_UNTRUSTED")
    assertFailsWith<SignerUntrustedException> { verifyCall().orThrow() }
  }

  // ---------------------------------------------------------------- retry policy (SPEC 13.3)

  @Test fun `a 503 with KEY_UNAVAILABLE is retried and the second answer wins`() {
    script.addAll(listOf(reject(503, "KEY_UNAVAILABLE"), answer(body = verifyAccept)))
    assertIs<TurvisResult.Accept<*>>(verifyCall())
    assertEquals(2, requests.size)
    assertEquals(listOf(100L), sleeps)
  }

  @Test fun `a 503 with TRUST_STORE_UNAVAILABLE is retried twice with 100 ms then 300 ms, then the reject is final`() {
    script.addAll(listOf(reject(503, "TRUST_STORE_UNAVAILABLE"), reject(503, "TRUST_STORE_UNAVAILABLE"), reject(503, "TRUST_STORE_UNAVAILABLE")))
    val rejection = assertIs<TurvisResult.Reject>(verifyCall()).rejection
    assertEquals("TRUST_STORE_UNAVAILABLE", rejection.reasonCode)
    assertEquals(3, requests.size)
    assertEquals(listOf(100L, 300L), sleeps)
    assertEquals(listOf(100L, 300L), TurvisClient.RETRY_BACKOFF_MS)
  }

  @Test fun `permanent rejects and TURVIS_INTERNAL are never retried`() {
    for ((status, code) in listOf(422 to "SIG_POLICY_FAIL", 422 to "UNKNOWN_RETURN_FIELD", 500 to "TURVIS_INTERNAL", 413 to "PAYLOAD_TOO_LARGE", 422 to "KEY_HANDLE_UNKNOWN")) {
      requests.clear()
      script += reject(status, code)
      assertIs<TurvisResult.Reject>(verifyCall())
      assertEquals(1, requests.size, code)
    }
    assertTrue(sleeps.isEmpty())
  }

  @Test fun `a 503 reject with a permanent reason is not retried either`() {
    script += reject(503, "TURVIS_INTERNAL")
    assertIs<TurvisResult.Reject>(verifyCall())
    assertEquals(1, requests.size)
  }

  @Test fun `idempotent endpoints retry a network error and a timeout`() {
    val calls = listOf<() -> Any>(
      { client.decrypt("U09BUA==", null, setOf(TurvisDecryptField.DECRYPTED_SOAP_XML_B64)) },
      { verifyCall() },
      { client.sign("U09BUA==", listOf("//eb:Messaging"), emptyList(), emptySet()) },
      { client.encrypt("QUJD", "EU-FR", TurvisEncryptProfile.PLAINTEXT, emptySet()) },
    )
    val accepts = listOf(
      """{"audit_ref":"$audit","decrypted_soap_xml_b64":"x"}""", verifyAccept,
      """{"signed_xml_b64":"U0lH","audit_ref":"$audit"}""", """{"encrypted_payload_b64":"QUJD","audit_ref":"$audit"}""",
    )
    calls.zip(accepts).forEachIndexed { i, (call, accept) ->
      requests.clear()
      script.addAll(listOf(IOException("connection reset"), HttpTimeoutException("timed out"), answer(body = accept)))
      assertIs<TurvisResult.Accept<*>>(call(), "call $i")
      assertEquals(3, requests.size, "call $i")
    }
  }

  @Test fun `encrypt in the wss-encrypted profile is never retried after a network error or timeout`() {
    script += IOException("connection reset")
    assertFailsWith<TurvisUnavailableException> { client.encrypt("QUJD", "EU-FR", TurvisEncryptProfile.WSS_ENCRYPTED, emptySet()) }
    script += HttpTimeoutException("timed out")
    assertFailsWith<TurvisUnavailableException> { client.encrypt("QUJD", "EU-FR", TurvisEncryptProfile.WSS_ENCRYPTED, emptySet()) }
    assertEquals(2, requests.size)
    assertTrue(sleeps.isEmpty())
  }

  @Test fun `encrypt in the wss-encrypted profile is retried on a 503 reject with a reason code`() {
    script.addAll(listOf(reject(503, "KEY_UNAVAILABLE"), reject(503, "TRUST_STORE_UNAVAILABLE"), answer(body = """{"encrypted_payload_b64":"QUJD","audit_ref":"$audit"}""")))
    assertIs<TurvisResult.Accept<*>>(client.encrypt("QUJD", "EU-FR", TurvisEncryptProfile.WSS_ENCRYPTED, emptySet()))
    assertEquals(3, requests.size)
    assertEquals(listOf(100L, 300L), sleeps)
  }

  @Test fun `encrypt is not retried after a 503 without a reason code`() {
    script += answer(503, "service unavailable")
    assertFailsWith<TurvisProtocolException> { client.encrypt("QUJD", "EU-FR", TurvisEncryptProfile.WSS_ENCRYPTED, emptySet()) }
    assertEquals(1, requests.size)
  }

  @Test fun `a network error that persists ends in TurvisUnavailableException after three attempts`() {
    script.addAll(listOf(IOException("a"), IOException("b"), IOException("c")))
    val e = assertFailsWith<TurvisUnavailableException> { verifyCall() }
    assertEquals(3, requests.size)
    assertEquals(listOf(100L, 300L), sleeps)
    assertContains(e.message!!, "verifications")
  }

  // ---------------------------------------------------------------- metrics

  private fun counter(metric: String, key: String): Long = (Metrics.data[metric] as Map<*, *>? ?: emptyMap<String, Long>())[key] as Long? ?: 0L

  @Test fun `calls, rejects and retries are counted per endpoint with a bounded set of keys`() {
    val accept = counter("edeliveryTurvisCallsTotal", "verifications_accept")
    val reject = counter("edeliveryTurvisCallsTotal", "verifications_reject")
    val policy = counter("edeliveryTurvisRejectsTotal", "verifications_SIG_POLICY_FAIL".lowercase())
    val retries = counter("edeliveryTurvisRetriesTotal", "verifications")
    val unrecognised = counter("edeliveryTurvisRejectsTotal", "verifications_unrecognised")

    script.addAll(listOf(answer(body = verifyAccept), reject(422, "SIG_POLICY_FAIL"), reject(422, "BRAND_NEW_CODE"), reject(503, "KEY_UNAVAILABLE"), answer(body = verifyAccept)))
    verifyCall(); verifyCall(); verifyCall(); verifyCall()

    assertEquals(accept + 2, counter("edeliveryTurvisCallsTotal", "verifications_accept"))
    assertEquals(reject + 2, counter("edeliveryTurvisCallsTotal", "verifications_reject"))
    assertEquals(policy + 1, counter("edeliveryTurvisRejectsTotal", "verifications_sig_policy_fail"))
    assertEquals(unrecognised + 1, counter("edeliveryTurvisRejectsTotal", "verifications_unrecognised"))
    assertEquals(retries + 1, counter("edeliveryTurvisRetriesTotal", "verifications"))
    assertEquals(0L, counter("edeliveryTurvisRejectsTotal", "verifications_brand_new_code"))
  }

  // ---------------------------------------------------------------- the contract in SPEC.md

  private val spec = Files.readString(Path.of("../../docs/specs/turvis/SPEC.md"))

  private fun requestSchema(endpoint: String): Map<String, Any?> {
    val section = Regex("(?s)### 4\\.\\d+ `POST $endpoint`\\n(.*?)(?=\\n### |\\n---\\n)").find(spec)!!.groupValues[1]
    val block = Regex("(?s)#### Request body schema\\s+```json\\n(.*?)```").find(section)!!.groupValues[1]
    return json.parse(block)
  }

  @Suppress("UNCHECKED_CAST")
  private fun assertMatchesSpec(endpoint: String, request: Map<String, Any?>) {
    val schema = requestSchema(endpoint)
    val required = (schema["required"] as List<String>).toSet()
    val properties = (schema["properties"] as Map<String, Any?>).keys
    assertTrue(request.keys.containsAll(required), "$endpoint: missing required ${required - request.keys}")
    assertTrue(properties.containsAll(request.keys), "$endpoint: sends a field the SPEC does not have: ${request.keys - properties}")
    val returnFields = (((schema["properties"] as Map<String, Any?>)["return_fields"] as Map<String, Any?>)["items"] as Map<String, Any?>)["enum"] as List<String>
    assertTrue(returnFields.containsAll(request["return_fields"] as List<String>), "$endpoint: return_fields outside the SPEC enum")
    assertTrue(Regex("[0-9a-f]{32}").matches(request["trace_id"] as String))
  }

  @Test fun `every request body matches the SPEC section 4 schema, with every optional field set`() {
    script += answer(body = """{"audit_ref":"$audit","decrypted_soap_xml_b64":"a","decrypted_attachment_b64":"b","ebms_routing":$routingJson}""")
    script += answer(body = """{"verdict":"accept","audit_ref":"$audit","signer":{"cert_subject_dn":"a","cert_ski":"b","cert_serial":"1","verified_identity":"x"},"matched_signed_xpaths":[],"matched_signed_cid_refs":[],"signature_digests":[],"ebms_routing":$routingJson}""")
    script += answer(body = """{"encrypted_payload_b64":"a","audit_ref":"$audit","wrapped_key_xml":"","encrypted_data_xml":"","wrapped_key_b64":""}""")
    script += answer(body = """{"signed_xml_b64":"a","audit_ref":"$audit","signatures":[]}""")

    client.decrypt("U09BUA==", "QUJD", TurvisDecryptField.entries.toSet())
    client.verify("U09BUA==", listOf(TurvisAttachment("cid:message", "QUJD")), listOf("//eb:Messaging"), listOf("cid:message"), TurvisVerifyField.entries.toSet(), "EU-DE", "gen-1")
    client.encrypt("QUJD", "EU-FR", TurvisEncryptProfile.WSS_ENCRYPTED, TurvisEncryptField.entries.toSet())
    client.sign("U09BUA==", listOf("//eb:Messaging"), listOf("cid:message"), TurvisSignField.entries.toSet(), listOf(TurvisAttachment("cid:message", "QUJD")))

    assertMatchesSpec("/turvis/v1/decryptions/xmlenc", body(0))
    assertMatchesSpec("/turvis/v1/verifications/xmldsig", body(1))
    assertMatchesSpec("/turvis/v1/encryptions/xmlenc", body(2))
    assertMatchesSpec("/turvis/v1/signatures/xmldsig", body(3))
  }

  @Test fun `the return field enums of the client are exactly the SPEC projectable sets`() {
    @Suppress("UNCHECKED_CAST")
    fun specEnum(endpoint: String) = ((((requestSchema(endpoint)["properties"] as Map<String, Any?>)["return_fields"] as Map<String, Any?>)["items"] as Map<String, Any?>)["enum"] as List<String>).toSet()
    assertEquals(specEnum("/turvis/v1/decryptions/xmlenc"), TurvisDecryptField.entries.map { it.wire }.toSet())
    assertEquals(specEnum("/turvis/v1/verifications/xmldsig"), TurvisVerifyField.entries.map { it.wire }.toSet())
    assertEquals(specEnum("/turvis/v1/encryptions/xmlenc"), TurvisEncryptField.entries.map { it.wire }.toSet())
    assertEquals(specEnum("/turvis/v1/signatures/xmldsig"), TurvisSignField.entries.map { it.wire }.toSet())
    @Suppress("UNCHECKED_CAST")
    val profiles = (((requestSchema("/turvis/v1/encryptions/xmlenc")["properties"] as Map<String, Any?>)["profile"] as Map<String, Any?>)["enum"] as List<String>).toSet()
    assertEquals(profiles, TurvisEncryptProfile.entries.map { it.wire }.toSet())
  }

  @Test fun `the reason code table is the one in SPEC sections 5 and 13_2a`() {
    val table5 = spec.substringAfter("## 5. Reason code enum").substringBefore("## 6.").lines()
      .filter { Regex("^\\| `[A-Z_]+` \\|").containsMatchIn(it) }
      .associate { line -> line.split("|").map(String::trim).let { it[1].trim('`') to it[2].toInt() } }
    assertEquals(table5, TurvisReason.entries.associate { it.code to it.httpStatus }, "SPEC section 5 codes and statuses")

    val table13 = spec.substringAfter("#### 13.2a").substringBefore("### 13.3").lines()
      .filter { Regex("^\\| `[A-Z_]+` \\|").containsMatchIn(it) }
      .map { line -> line.split("|").map(String::trim) }
    assertEquals(TurvisReason.entries.map { it.code }.toSet(), table13.map { it[1].trim('`') }.toSet())
    for (row in table13) {
      val reason = TurvisReason.of(row[1].trim('`'))!!
      val exceptionName = row[4].trim('`').substringBefore("`").substringBefore(" ")
      val rejection = TurvisRejection("verifications", reason.httpStatus, reason.code, null, null)
      // The SPEC calls the payload-size exception PayloadTooLargeException; the in-process one of the legacy path has that name already.
      val expected = if (exceptionName == "PayloadTooLargeException") "TurvisPayloadTooLargeException" else exceptionName
      assertEquals(expected, reason.exception(rejection)::class.simpleName, reason.code)
      val fault = Regex("`(EBMS:\\d+)` (\\w+)").find(row[2])
      assertEquals(fault?.groupValues?.get(1), reason.ebmsCode, reason.code)
      assertEquals(fault?.groupValues?.get(2), reason.ebmsShortDescription, reason.code)
    }
  }

  @Test fun `the client registers through klite dependency injection with its defaults`() {
    val registry = klite.DependencyInjectingRegistry().apply {
      register(TurvisConfig::class, config)
      register(HttpClient::class, http)
      register(JsonMapper::class, json)
      register(TurvisClient::class, TurvisClient::class)
    }
    assertIs<TurvisClient>(registry.require(TurvisClient::class))
  }
}
