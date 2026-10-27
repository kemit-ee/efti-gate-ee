package edelivery

import klite.Config
import java.net.URI
import java.net.URISyntaxException

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
 * - `TURVIS_SHADOW_MAX_INFLIGHT` = concurrent shadow comparisons, 1 to 64 (default 8); only used in shadow mode.
 * - `TURVIS_MAX_PAYLOAD_MIB` = largest business payload of the Turvis path, 1 to 256 (default 40, SPEC 13.8); bounds
 *   the decompressed inbound payload, the outbound payload and, derived from it, the largest Turvis answer read.
 *   The HTTP cap of `POST /services/msh` follows it in `turvis` mode ([maxMshBodyBytes], never below `BODY_LIMIT_MB`);
 *   in legacy and shadow mode it stays `BODY_LIMIT_MB` (default 10), because the legacy path decides there.
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
  /** Shadow mode only: how many Turvis comparisons may run at once; a message over the limit is dropped and counted, never queued. */
  val shadowMaxInflight: Int = DEFAULT_SHADOW_MAX_INFLIGHT,
  /** Largest business payload, in MiB, the Turvis path accepts (inbound after decompression, outbound before it): SPEC 13.8. */
  val maxPayloadMib: Int = DEFAULT_MAX_PAYLOAD_MIB,
) {
  val maxPayloadBytes: Long get() = maxPayloadMib * MIB

  /** The largest Turvis answer read: base64 of a payload-sized attachment (4/3) with margin for gzip overhead and the envelope. */
  val maxResponseBytes: Long get() = maxPayloadBytes * 3 / 2 + 4 * MIB

  /**
   * The largest HTTP body `POST /services/msh` accepts when Turvis is authoritative: the gzip attachment as base64
   * (4/3 of a payload-sized, incompressible attachment) plus the SOAP envelope and the multipart framing.
   */
  val maxMshBodyBytes: Long get() = maxPayloadBytes * 4 / 3 + MSH_ENVELOPE_ALLOWANCE

  /** Never prints the token or any credential in the URL: a logged or thrown config must not leak them. */
  override fun toString() =
    "TurvisConfig(mode=$mode, url=${url?.let { "${it.scheme}://${it.host}${if (it.port > 0) ":${it.port}" else ""}" }}, token=${if (token == null) "unset" else "***"}, keyHandle=$keyHandle)"

  /** The URL to call; only valid when [mode] is not legacy. */
  val baseUrl: URI get() = requireNotNull(url) { "TURVIS_URL is not set" }
  val internalToken: String get() = requireNotNull(token) { "TURVIS_INTERNAL_TOKEN is not set" }

  companion object {
    const val MOCK_HOST = "turvis-mock"
    const val DEFAULT_SHADOW_MAX_INFLIGHT = 8
    const val DEFAULT_MAX_PAYLOAD_MIB = 40
    private const val MSH_ENVELOPE_ALLOWANCE = 2L * 1024 * 1024
    private const val MIB = 1024L * 1024
    private val RESTRICTED_ENVIRONMENTS = setOf("prod", "production", "staging")
    private val ENVIRONMENT_VARIABLES = listOf("ENVIRONMENT", "APP_ENV", "DEPLOY_ENV")

    fun load(get: (String) -> String? = { Config.optional(it) }): TurvisConfig {
      val mode = TurvisIntegrationMode.parse(get("TURVIS_INTEGRATION_MODE"))
      // An unparseable URL is only a start-up error when it would be used; legacy needs nothing from Turvis.
      // The message never repeats the value: a URL may carry credentials.
      // "Unusable" covers a syntax error, a non-http(s) scheme and a missing host.
      val url = get("TURVIS_URL")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        val parsed = try { URI(it) } catch (e: URISyntaxException) { null }
        if (parsed != null && parsed.scheme?.lowercase() in setOf("http", "https") && !parsed.host.isNullOrEmpty()) parsed
        else if (mode == TurvisIntegrationMode.LEGACY) null
        else throw IllegalArgumentException("TURVIS_URL must be an absolute http(s) URL")
      }
      val token = get("TURVIS_INTERNAL_TOKEN")?.takeIf { it.isNotEmpty() }
      val keyHandle = checkNotNull(get("OWN_GATE_ID")?.takeIf { it.isNotBlank() }) { "OWN_GATE_ID is not set" }

      if (url != null) {
        val restricted = environments(get).intersect(RESTRICTED_ENVIRONMENTS)
        check(!(isMockHost(url.host) && restricted.isNotEmpty())) {
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
      val maxInflight = get("TURVIS_SHADOW_MAX_INFLIGHT")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        requireNotNull(it.toIntOrNull()?.takeIf { n -> n in 1..64 }) { "TURVIS_SHADOW_MAX_INFLIGHT must be an integer from 1 to 64" }
      } ?: DEFAULT_SHADOW_MAX_INFLIGHT
      val maxPayloadMib = get("TURVIS_MAX_PAYLOAD_MIB")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        requireNotNull(it.toIntOrNull()?.takeIf { n -> n in 1..256 }) { "TURVIS_MAX_PAYLOAD_MIB must be an integer from 1 to 256" }
      } ?: DEFAULT_MAX_PAYLOAD_MIB
      return TurvisConfig(mode, url, token, keyHandle, maxInflight, maxPayloadMib)
    }

    /**
     * The mock by name: `turvis-mock`, its fully-qualified forms (`turvis-mock.`, `turvis-mock.<namespace>`,
     * `turvis-mock.svc.cluster.local`) in any case. A hostname check cannot see an IP address or an unrelated alias.
     */
    internal fun isMockHost(host: String?): Boolean {
      val h = host.orEmpty().lowercase().trimEnd('.')
      return h == MOCK_HOST || h.startsWith("$MOCK_HOST.")
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
