import edelivery.*
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import klite.*
import klite.annotations.annotated
import klite.http.httpClient
import klite.json.JsonBody
import klite.openapi.openApi
import metrics.RequestMetrics
import java.util.concurrent.TimeoutException

fun main() {
  Config.useEnvFile()
  if (Config.optional("PORT") == null) Config["PORT"] = "8081"

  // Fail closed on a bad or unsafe Turvis configuration (e.g. the mock URL in prod/staging), in any mode.
  val turvisConfig = TurvisConfig.load()

  val registry = DependencyInjectingRegistry().apply {
    register<RequestLogger>(RequestLogger { ms ->
      "<" + attr<String?>("client") + "> " + defaultRequestLogFormatter(ms)
    })
    register<RequestMetrics>(RequestMetrics())
  }

  Server(requestIdGenerator = RequestIdHandler(), registry = registry).apply {
    useOnly<EDeliveryBody>()
    register(httpClient())
    // #305a: available for injection, not used by any call site yet; the default mode is legacy (Turvis is never called).
    register(turvisConfig)
    register<TurvisClient>()
    register<TurvisInbound>()
    register<TurvisShadow>()
    register<TurvisOutbound>()
    register<RuuterClient>(if (Config.isProd) RuuterClient::class else RuuterClientDev::class)
    register<PartyRegistry>(EDeliveryPartyRegistry::class)
    register<AsyncResponseProvider>(DbAsyncResponseProvider::class)
    register<MessageHandlers>(EftiMessageHandlers::class)

    errors.on<TimeoutException>(StatusCode.GatewayTimeout)

    metrics()

    context("/health") {
      get { "OK" }
    }

    // Internal
    context("/api/v1") {
      before(InternalServiceTokenAuth())
      annotated<InternalRoutes>()

      openApi(annotations = listOf(
        OpenAPIDefinition(info = Info(
          title = "eDelivery",
          version = "1.0",
          description = "Internal eDelivery AS4 integration API used by the gate services."
        )),
      ))
    }

    // Internet-facing
    context("/services") {
      bodySizeLimit()
      annotated<EDeliveryRoutes>()
    }

    start()
  }
}

class EDeliveryBody: JsonBody() {
  override fun render(e: HttpExchange, code: StatusCode, value: Any?) {
    if (value is String) e.send(code, value, MimeTypes.xml)
    else super.render(e, code, value)
  }
}
