import klite.StatusCode
import klite.http.HttpException
import java.net.http.HttpResponse

/**
 * Like klite's `bodyOrThrow()`, but a failure carries the status only. klite writes the response body into the exception
 * message (`Failed with 500: <body>`) and keeps it in `HttpException.body`; an exception message is logged and can reach a
 * caller, and the rule is that an answer's text never does (AGENTS.md "Kotlin services", `withoutMessages()`). The answers of
 * the internal services (ReSql, registry, Ruuter) are not a peer's, but their error text is not ours to repeat either.
 * The same threshold as klite's (any status from 300 up throws; the JDK client does not follow redirects, so a 3xx reaches the
 * caller and must not count as a success). No declared return type, so the platform type of `body()` is kept, as with klite's own function.
 */
fun HttpResponse<String>.bodyOrThrowStatusOnly() = if (statusCode() >= 300) throw HttpException(StatusCode(statusCode()), "") else body()
