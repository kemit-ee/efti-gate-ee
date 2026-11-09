package registry

/**
 * The `generation` of the registry/trust artifacts (SPEC 7.2): `sha256:` plus 64 lowercase hex digits.
 * It arrives from the registry's health.json and from Turvis's `X-Turvis-Trust-Generation` header, so only a value of exactly
 * this shape is ever logged or exported.
 */
object TrustGeneration {
  private val FORMAT = Regex("sha256:[0-9a-f]{64}")

  fun parse(value: Any?): String? = (value as? String)?.takeIf { FORMAT.matches(it) }

  /**
   * klite's Metrics has no labels, so the generation cannot be a label as in SPEC 7.4. The gauge carries the first 48 bits of the
   * hash instead (exact in a double): two gauges are equal exactly when their generations are, bar a 2^-48 collision.
   */
  fun gauge(generation: String): Long = generation.removePrefix("sha256:").take(12).toLong(16)
}
