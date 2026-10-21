package edelivery

import klite.Config
import java.net.URI

/**
 * How edelivery uses Turvis for XML signature/encryption (docs/specs/turvis/SPEC.md section 13.0).
 * The default is [LEGACY]: in-process crypto only, Turvis is never called.
 */
enum class TurvisIntegrationMode {
  /** In-process crypto only (default). Turvis is not called. */
  LEGACY,
  /** In-process crypto is authoritative; Turvis is called off the critical path and compared. */
  SHADOW,
  /** Turvis is authoritative; the legacy path is not invoked (its code is still present). */
  TURVIS;

  val wire get() = name.lowercase()

  companion object {
    fun parse(value: String?): TurvisIntegrationMode = when (val v = value?.trim()?.lowercase()) {
      null, "" -> LEGACY
      else -> entries.firstOrNull { it.wire == v }
        ?: throw IllegalStateException("TURVIS_INTEGRATION_MODE must be one of ${entries.map { it.wire }}, got '$value'")
    }
  }
}

/**
 * Turvis settings of this gate, read once at start-up. Invalid or unsafe combinations stop the service
 * ([TurvisConfig.load] throws) instead of being discovered at the first message.
 *
 * - `TURVIS_INTEGRATION_MODE` = legacy | shadow | turvis (default legacy).
 * - `TURVIS_URL` = base URL of Turvis, e.g. `http://turvis:8080`; required unless the mode is legacy.
 * - `TURVIS_INTERNAL_TOKEN` = the credential edelivery presents in `X-Internal-Service-Token`; required unless
 *   the mode is legacy. It is **dedicated** to Turvis (SPEC section 11.2): it must not equal the fleet-wide
 *   `INTERNAL_SERVICE_TOKEN`.
 * - The key handle sent to Turvis is the own gate id (`OWN_GATE_ID`, SPEC section 7.1).
 *
 * Start-up guard (SPEC section 13.0): the service refuses to start when `TURVIS_URL` points at the `turvis-mock`
 * host and `ENVIRONMENT`/`APP_ENV`/`DEPLOY_ENV` is `prod` or `staging`, in any mode, so a mock configuration
 * can never reach a production-like environment by being switched on later. Hostname based on purpose
 * (tightening it to the mesh identity is #309 work).
 */
data class TurvisConfig(
  val mode: TurvisIntegrationMode = TurvisIntegrationMode.LEGACY,
  val url: URI? = null,
  val token: String? = null,
  val keyHandle: String,
) {
  /** The URL to call; only valid when [mode] is not legacy. */
  val baseUrl: URI get() = requireNotNull(url) { "TURVIS_URL is not set" }
  val internalToken: String get() = requireNotNull(token) { "TURVIS_INTERNAL_TOKEN is not set" }

  companion object {
    const val MOCK_HOST = "turvis-mock"
    private val RESTRICTED_ENVIRONMENTS = setOf("prod", "production", "staging")
    private val ENVIRONMENT_VARIABLES = listOf("ENVIRONMENT", "APP_ENV", "DEPLOY_ENV")

    fun load(get: (String) -> String? = { Config.optional(it) }): TurvisConfig {
      val mode = TurvisIntegrationMode.parse(get("TURVIS_INTEGRATION_MODE"))
      val url = get("TURVIS_URL")?.trim()?.takeIf { it.isNotEmpty() }?.let { URI(it) }
      val token = get("TURVIS_INTERNAL_TOKEN")?.takeIf { it.isNotEmpty() }
      val keyHandle = checkNotNull(get("OWN_GATE_ID")?.takeIf { it.isNotBlank() }) { "OWN_GATE_ID is not set" }

      if (url != null) {
        require(url.scheme in setOf("http", "https") && !url.host.isNullOrEmpty()) { "TURVIS_URL must be an absolute http(s) URL, got '$url'" }
        val restricted = environments(get).intersect(RESTRICTED_ENVIRONMENTS)
        check(!(url.host.equals(MOCK_HOST, ignoreCase = true) && restricted.isNotEmpty())) {
          "TURVIS_URL points at the turvis-mock host in a ${restricted.first()} environment: refusing to start"
        }
      }
      if (mode != TurvisIntegrationMode.LEGACY) {
        checkNotNull(url) { "TURVIS_URL is required when TURVIS_INTEGRATION_MODE=${mode.wire}" }
        checkNotNull(token) { "TURVIS_INTERNAL_TOKEN is required when TURVIS_INTEGRATION_MODE=${mode.wire}" }
        check(token != get("INTERNAL_SERVICE_TOKEN")) {
          "TURVIS_INTERNAL_TOKEN must be a dedicated credential, not the fleet's INTERNAL_SERVICE_TOKEN"
        }
      }
      return TurvisConfig(mode, url, token, keyHandle)
    }

    /**
     * Every environment name that is set, lower-cased: ENVIRONMENT, APP_ENV, DEPLOY_ENV and the comma-separated
     * profiles of klite's `ENV`. Any one of them being prod/production/staging counts, so one variable cannot
     * hide another.
     */
    internal fun environments(get: (String) -> String?): Set<String> =
      (ENVIRONMENT_VARIABLES + "ENV").flatMap { name -> get(name).orEmpty().split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
  }
}
