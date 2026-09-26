import edelivery.EDeliveryClient
import edelivery.EDeliveryParty
import edelivery.PartyId
import edelivery.RequestKey
import edelivery.UserMessageParams
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import klite.HttpExchange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID

class InternalRoutesTest {
  private val eDeliveryClient = mockk<EDeliveryClient>()
  private val partyRegistry = mockk<EDeliveryPartyRegistry>()
  private val routes = InternalRoutes(eDeliveryClient, partyRegistry)

  @Test fun `send resolves the party and forwards the eDelivery response`() {
    val party = EDeliveryParty(PartyId("EU-EE"), URI("http://gate/msh"), "cert")
    val expectedRequestId = UUID.randomUUID()
    val exchange = mockk<HttpExchange> { every { requestId } returns expectedRequestId.toString() }
    every { partyRegistry[PartyId("EU-EE")] } returns party
    every { eDeliveryClient.sendAndReceive(party.eDeliveryUrl, any(), "<xml/>") } returns "<Response/>"

    val result = routes.send("<xml/>", PartyId("EU-EE"), exchange)

    assertEquals("<Response/>", result)
    verify {
      eDeliveryClient.sendAndReceive(party.eDeliveryUrl, match<UserMessageParams> {
        it.requestKey == RequestKey(PartyId("EU-EE"), expectedRequestId)
      }, "<xml/>")
    }
  }

  @Test fun `ping reloads the party registry before resolving and pinging the party`() {
    val party = EDeliveryParty(PartyId("EU-EE"), URI("http://gate/msh"), "cert")
    every { partyRegistry.reload() } returns Unit
    every { partyRegistry[PartyId("EU-EE")] } returns party
    every { eDeliveryClient.ping(party) } returns Unit

    routes.ping(PartyId("EU-EE"))

    verify(ordering = io.mockk.Ordering.ORDERED) {
      partyRegistry.reload()
      partyRegistry[PartyId("EU-EE")]
      eDeliveryClient.ping(party)
    }
  }
}
