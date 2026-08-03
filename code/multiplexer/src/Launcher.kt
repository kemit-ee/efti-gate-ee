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

    start()
  }
}
