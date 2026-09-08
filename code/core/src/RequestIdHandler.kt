import klite.Headers
import klite.HttpExchange
import klite.RequestIdGenerator
import java.util.*

private const val requestIdHeader = "x-request-id"

class RequestIdHandler: RequestIdGenerator() {
  override fun invoke(headers: Headers) =
    headers.getFirst(requestIdHeader)?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()

  fun send(e: HttpExchange, requestId: UUID) =
    e.header(requestIdHeader, requestId.toString())
}
