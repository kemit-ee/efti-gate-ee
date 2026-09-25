import klite.Before
import klite.Config
import klite.HttpExchange
import klite.UnauthorizedException
import java.security.MessageDigest

class InternalServiceTokenAuth(
  private val expected: String? = Config.optional("INTERNAL_SERVICE_TOKEN"),
  private val header: String = "X-Internal-Service-Token",
) : Before {
  override fun before(exchange: HttpExchange) {
    val provided = exchange.header(header)
    if (expected.isNullOrEmpty() || provided == null ||
      !MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8)))
      throw UnauthorizedException("Missing or invalid $header")
  }
}
