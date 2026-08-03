import klite.Config
import klite.Server
import klite.metrics

fun main() {
  Config.useEnvFile()
  // TODO: val gateRegistry load on startup using ReSql (CI/whatever will restart us on change)
  Server().apply {
    metrics()

    context("/health") {
      get { "OK" }
    }

    context("/api/v1") {
      post("/first/:searchId") {
        // body as xml payload -> send to every gate, return first response (xml)
      }

      get("/rest/:searchId") {
        // return all the rest responses (many xmls, with string delimiter)
      }
    }

    start()
  }
}
