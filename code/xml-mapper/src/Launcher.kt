import klite.Config
import klite.Server
import klite.metrics

fun main() {
  Config.useEnvFile()
  Server().apply {
    metrics()

    context("/health") {
      get { "OK" }
    }

    context("/api/v1") {
      post("/upload/request-to-json") {
        // body: FTI004UploadIdentifierRequest or UniqueIDSetUIL as xml
        // return: UIL+ParameterIDSetCriteria
      }

      post("/upload/response-to-xml") {
        // body: UIL as json
        // return: FTI029UploadIdentifierResponse as xml
      }

      post("/search/request-to-xml") {
        // body: ParameterSearchCriteria as json
        // return: Fti019SearchIdentifierRequest(ParameterSearchCriteria) as xml
      }

      post("/search/request-to-json") {
        // body: FTI019SearchIdentifierRequest as xml
        // return: ParameterSearchCriteria as json
      }

      post("/search/response-to-json") {
        // body: one or more FTI021SearchIdentifierResponse as xml with delimiter
        // return: multiple UIL+ParameterIDSetCriteria as json (for Authority)
      }

      post("/search/response-to-xml") {
        // body: ParameterSearchCriteria as json
        // return: FTI021SearchIdentifierResponse as xml (for another Gate)
      }

      post("/dataset/request-to-xml") {
        // body: UIL+SubsetIds as json
        // return: FTI009GetCmdsRequest as xml
      }

      post("/dataset/request-to-json") {
        // body: FTI009GetCmdsRequest as xml
        // return: UIL+SubsetIds as json
      }

      post("/dataset/response-to-json") { // or just unwrap xml?
        // body: FTI010GetCmdsResponse or SpecifiedSupplyChainConsignment as xml
        // return: SpecifiedSupplyChainConsignment as xml or converted to json
      }

      post("/dataset/response-to-xml") {
        // body: FTI010GetCmdsResponse or SpecifiedSupplyChainConsignment as xml
        // return: FTI010GetCmdsResponse as xml
      }

      post("/followup/request-to-xml") {
        // body: UIL+Message as json
        // return: FTI025LodgeFollowUpCommRequest as xml
      }

      post("/followup/request-to-json") {
        // body: FTI025LodgeFollowUpCommRequest as xml
        // return: UIL+Message as json
      }

      post("/followup/response-to-json") { // do we need this at all?
        // body: FTI030LodgeFollowUpCommResponse as xml
        // return: UIL as json
      }

      post("/followup/response-to-xml") { // do we need this at all?
        // body: UIL as json
        // return: FTI030LodgeFollowUpCommResponse as xml
      }
    }

    start()
  }
}
