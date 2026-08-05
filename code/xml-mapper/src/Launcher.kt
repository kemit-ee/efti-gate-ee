import dataset.DatasetRoutes
import followup.FollowupRoutes
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
      annotated<FollowupRoutes>("/followup")
    }

    start()
  }
}
