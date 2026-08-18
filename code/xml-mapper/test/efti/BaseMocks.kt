package efti

import RequestIdHandler
import io.mockk.every
import io.mockk.mockk
import klite.HttpExchange

abstract class BaseMocks {
  val requestIdHandler = mockk<RequestIdHandler>(relaxed = true)
  val exchange = mockk<HttpExchange>(relaxed = true) { every { requestId } returns "00000000-0000-0000-0000-000000000001" }
}
