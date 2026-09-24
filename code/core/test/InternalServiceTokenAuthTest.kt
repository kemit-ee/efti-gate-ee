import io.mockk.every
import io.mockk.mockk
import klite.HttpExchange
import klite.UnauthorizedException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InternalServiceTokenAuthTest {
  private val exchange = mockk<HttpExchange>()

  @Test fun `allows a matching token`() {
    every { exchange.header("X-Internal-Service-Token") } returns "secret"
    InternalServiceTokenAuth("secret").before(exchange)
  }

  @Test fun `rejects a missing token`() {
    every { exchange.header("X-Internal-Service-Token") } returns null
    assertThrows<UnauthorizedException> { InternalServiceTokenAuth("secret").before(exchange) }
  }

  @Test fun `rejects a wrong token`() {
    every { exchange.header("X-Internal-Service-Token") } returns "wrong"
    assertThrows<UnauthorizedException> { InternalServiceTokenAuth("secret").before(exchange) }
  }

  @Test fun `fails closed when the expected token is unset`() {
    every { exchange.header("X-Internal-Service-Token") } returns "anything"
    assertThrows<UnauthorizedException> { InternalServiceTokenAuth(null).before(exchange) }
  }

  @Test fun `fails closed when the expected token is blank`() {
    every { exchange.header("X-Internal-Service-Token") } returns ""
    assertThrows<UnauthorizedException> { InternalServiceTokenAuth("").before(exchange) }
  }

  @Test fun `honours a custom header name`() {
    every { exchange.header("X-Custom-Token") } returns "secret"
    InternalServiceTokenAuth("secret", "X-Custom-Token").before(exchange)
  }
}
