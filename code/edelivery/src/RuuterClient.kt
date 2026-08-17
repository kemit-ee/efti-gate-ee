import edelivery.EDeliveryParty
import edelivery.PartyId
import java.net.http.HttpClient

/** Forwards raw EFTI XMLs to Ruuter for further conversion */
class RuuterClient(
  private val http: HttpClient,
) {
  fun getParties(): Map<PartyId, EDeliveryParty> {
    // TODO fetch edelivery parties from ruuter
    return mapOf()
  }

  fun saveConsignment(xml: String /* FTI004UploadIdentifierRequest */): String {
    TODO()
  }

  fun searchConsignments(xml: String /* FTI019SearchIdentifierRequest */): String {
    TODO()
  }

  fun getDataset(xml: String /* FTI009GetCmdsRequest */): String {
    TODO()
  }

  fun followUp(xml: String /* FTI025LodgeFollowUpCommRequest */): String {
    TODO()
  }
}
