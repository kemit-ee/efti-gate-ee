import java.net.http.HttpClient

/** Forwards raw EFTI XMLs to Ruuter for further conversion */
class RuuterClient(
    private val http: HttpClient,
) {
    fun saveConsignment(xml: String /* FTI004UploadIdentifierRequest */) {

    }

    fun searchConsignments(xml: String /* FTI019SearchIdentifierRequest */) {

    }

    fun getDataset(xml: String /* FTI009GetCmdsRequest */) {

    }

    fun followUp(xml: String /* FTI025LodgeFollowUpCommRequest */) {

    }
}