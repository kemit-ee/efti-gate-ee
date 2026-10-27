package edelivery

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpClient
import java.util.Base64
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import klite.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Memory and size check of a maximum-size payload through the Turvis path against the real `turvis-mock` (#308 groundwork,
 * SPEC 13.8). Opt-in, never part of CI: skipped unless `TURVIS_MOCK_URL` is set. `TURVIS_LARGE_PAYLOAD_MIB` (default 2, what the mock can answer within the client timeout; 40 only against a real Turvis)
 * picks the payload; run it with the heap the container gives `edelivery` (80 % of `mem_limit`) to see whether it fits:
 *
 *     docker compose up --build -d turvis-mock
 *     JAVA_TOOL_OPTIONS=-Xmx410m TURVIS_MOCK_URL=http://127.0.0.1:8091 ./gradlew edelivery:test --rerun --tests 'edelivery.TurvisLargePayloadMockTest' -i
 *
 * **The mock cannot carry the 40 MiB budget** (it is a Ruuter): a request body over 16 MiB is answered 413 by Ruuter's fixed
 * perimeter cap, and its guard costs roughly 0.2 to 0.6 s per MiB of body, so a payload of a couple of MiB already runs into the
 * client's 10 s timeout. Point `TURVIS_MOCK_URL` at a real Turvis (or a stub that answers at full size) for a full-size run.
 *
 * The payload is random printable text (gzip keeps about 3/4 of it), so it is a realistic hard case, not the incompressible
 * worst case; the peak used heap is printed. The mock has no real cryptography: the copies of the payload the client makes
 * (base64, JSON, string) are real, the cost of a real Turvis is not measured.
 */
class TurvisLargePayloadMockTest {
  private val mockUrl = System.getenv("TURVIS_MOCK_URL")
  private val token = System.getenv("TURVIS_MOCK_TOKEN") ?: "dev-turvis-mock-token"
  private val mib = System.getenv("TURVIS_LARGE_PAYLOAD_MIB")?.toInt() ?: 2

  private fun config() = TurvisConfig(TurvisIntegrationMode.TURVIS, URI(mockUrl), token, "EU-EE", maxPayloadMib = mib)

  private fun payload(): String {
    val random = Random(42)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 "
    val body = StringBuilder(mib * 1024 * 1024)
    body.append("<r>")
    while (body.length < mib * 1024 * 1024 - 8) body.append(alphabet[random.nextInt(alphabet.length)])
    return body.append("</r>").toString()
  }

  private inline fun <T> peakHeap(label: String, block: () -> T): T {
    val peak = AtomicLong()
    val memory = ManagementFactory.getMemoryMXBean()
    val sampler = Thread { while (!Thread.interrupted()) { peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf); try { Thread.sleep(5) } catch (_: InterruptedException) { return@Thread } } }.apply { isDaemon = true; start() }
    try { return block() } finally {
      sampler.interrupt(); sampler.join()
      println("TURVIS_LARGE_PAYLOAD $label payload=${mib}MiB peakHeap=${peak.get() / 1024 / 1024}MiB maxHeap=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MiB")
    }
  }

  @Test fun `a payload goes out and comes back in against the mock and the peak heap is printed`() {
    assumeTrue(mockUrl != null, "TURVIS_MOCK_URL is not set")
    val config = config()
    val client = TurvisClient(config, HttpClient.newHttpClient(), JsonMapper())
    val outbound = TurvisOutbound(config, client, EDeliveryMessageGenerator(io.mockk.mockk { io.mockk.every { partyId } returns PartyId("EU-EE") })).also { it.allowUnsignedEcho = true }
    val xml = payload()

    val sent = peakHeap("outbound") { outbound.requestMessage(UserMessageParams(RequestKey(PartyId("EU-FR"), senderId = PartyId("EU-EE"))), xml) }
    assertTrue(sent.encryptedAttachment.size > 0)
    val attachmentBase64 = Base64.getEncoder().encode(sent.encryptedAttachment).size
    println("TURVIS_LARGE_PAYLOAD attachmentBytes=${sent.encryptedAttachment.size} asBase64=$attachmentBase64 httpCap=${config.maxMshBodyBytes}")
    assertTrue(sent.encryptedAttachment.size + sent.signedSoap.size < config.maxMshBodyBytes, "an outbound message of the maximum payload fits the receiving gate's HTTP cap")

    val envelope = Base64.getEncoder().encodeToString(
      ("""<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope" xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ns/v3.0/ebms/core/200704/"><env:Header>""" +
        """<eb:Messaging wsu:Id="MSG-ID"><eb:UserMessage><eb:MessageInfo><eb:MessageId>large-0001@eu-de.example</eb:MessageId></eb:MessageInfo>""" +
        """<eb:PartyInfo><eb:From><eb:PartyId>EU-DE</eb:PartyId></eb:From><eb:To><eb:PartyId>EU-EE</eb:PartyId></eb:To></eb:PartyInfo>""" +
        """<eb:CollaborationInfo><eb:Service>FTI010</eb:Service><eb:Action>FTI010Response</eb:Action><eb:ConversationId>11111111-1111-1111-1111-111111111111</eb:ConversationId></eb:CollaborationInfo></eb:UserMessage></eb:Messaging>""" +
        """</env:Header><env:Body wsu:Id="BODY-ID"/></env:Envelope>""").toByteArray(),
    )
    val gzipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(xml.toByteArray()) } }.toByteArray()
    val body = "--bnd\r\nContent-Type: application/soap+xml\r\n\r\n".toByteArray() + Base64.getDecoder().decode(envelope) +
      "\r\n--bnd\r\nContent-ID: <message>\r\n\r\n".toByteArray() + gzipped + "\r\n--bnd--\r\n".toByteArray()
    println("TURVIS_LARGE_PAYLOAD inboundBodyBytes=${body.size}")

    val received = peakHeap("inbound") { TurvisInbound(config, client).process(body) }
    assertEquals(xml.length, received.payloadXml.length)
  }
}
