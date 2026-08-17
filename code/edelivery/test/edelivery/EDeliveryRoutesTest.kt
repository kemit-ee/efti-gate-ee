package edelivery

import core.EDeliveryClient
import core.EDeliveryMessageGenerator
import core.EDeliveryParty
import core.EDeliveryRoutes
import core.KeyManager
import core.MessageHandler
import core.PartyId
import core.PartyRegistry
import core.soap
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import klite.Config
import klite.HttpExchange
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI

class EDeliveryRoutesTest {
  init {
    Config["KEYSTORE_DIR"] = "../gate/certs"
  }

  val party = EDeliveryParty(PartyId("fi-tst"), URI("http://ee"), "")
  val partyRegistry = mockk<PartyRegistry>(relaxUnitFun = true) {
    every { get(party.id) } returns party
  }
  val keyManager = KeyManager(partyRegistry)
  val messageHandler = mockk<MessageHandler>()
  val messageGenerator = EDeliveryMessageGenerator(keyManager)
  val eDeliveryClient = mockk<EDeliveryClient>(relaxed = true)

  val routes = EDeliveryRoutes(keyManager, messageHandler, messageGenerator, eDeliveryClient, partyRegistry)

  fun getEnvelope(cipherValue: String): String {
    val regex = Regex("(<xenc:CipherValue\\b[^>]*>)(.*?)(</xenc:CipherValue>)", setOf(RegexOption.DOT_MATCHES_ALL))

    val xml = File("test/edelivery/soap-env.xml").readText()

    return regex.replace(xml) { it.groupValues[1] + cipherValue + it.groupValues[3] }
  }

  @Test fun encryptedAesKeyInAnotherPart() {
    val aesKey = messageGenerator.generateAESKey()

    val payload = "<hello>world</hello>"

    val mimeBoundary = "----boundary"
    val body = listOf(
      "--$mimeBoundary\r\nContent-Type: ${soap}\r\n\r\n".toByteArray(),
      getEnvelope("""<xop:Include href="cid:7c232fbd-2b4d-4048-97c4-58dfa4fce251" />""").toByteArray(),
      ("\r\n--$mimeBoundary\r\n" +
        "Content-Type: application/ciphervalue\r\n" +
        "Content-ID: <7c232fbd-2b4d-4048-97c4-58dfa4fce251>\r\n\r\n").toByteArray(),
      messageGenerator.encryptAesKey(keyManager.ownCert, aesKey),
      ("\r\n--$mimeBoundary\r\n" +
        "Content-Type: application/octet-stream\r\n" +
        "Content-ID: <message>\r\n\r\n").toByteArray(),
      messageGenerator.encryptPayload(aesKey, messageGenerator.gzip(payload.toByteArray())),
      "\r\n--$mimeBoundary--".toByteArray()
    ).reduce(ByteArray::plus)

    val exchange = mockk<HttpExchange>(relaxed = true)
    every { exchange.requestStream } returns body.inputStream()

    every { messageHandler.response(any(), any()) } returns null

    routes.msh(exchange)

    verify { messageHandler.response(match { it.receiverId == party.id }, payload) }
  }
}
