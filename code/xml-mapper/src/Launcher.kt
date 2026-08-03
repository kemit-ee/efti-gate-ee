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
      post("/search-request-xml") {
        // body: ParameterSearchCriteria as json
        // return: Fti019SearchIdentifierRequest(ParameterSearchCriteria) as xml
      }
    }

    start()
  }
}
