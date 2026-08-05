import dataset.DatasetRoutes
import klite.Config
import klite.Server
import klite.annotations.annotated
import klite.metrics
import search.SearchRoutes
import upload.UploadRoutes

fun main() {
  Config.useEnvFile()
  Server().apply {
    metrics()

    context("/health") {
      get { "OK" }
    }

    context("/api/v1") {
      annotated<UploadRoutes>("/upload")
      annotated<SearchRoutes>("/search")
      annotated<DatasetRoutes>("/dataset")

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
