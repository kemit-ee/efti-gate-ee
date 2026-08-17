package edelivery

import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.verbs.expect
import core.KeyManager
import core.toX509
import io.mockk.mockk
import klite.Config
import org.junit.jupiter.api.Test

class KeyManagerTest {
  init { Config.useEnvFile() }
  val keyManager = KeyManager(mockk(relaxed = true))

  @Test fun extractSKI() {
    expect(keyManager.ownCertSki).toEqual("t8GqaKD/lNytZIjtDIqdPapkJgQ=")
    expect(keyManager.certSki(keyManager.ownCert)).toEqual("t8GqaKD/lNytZIjtDIqdPapkJgQ=")
  }

  @Test fun generateSKI() {
    val cert = """
      -----BEGIN CERTIFICATE-----
      MIIC1DCCAbygAwIBAgIEaG4t0jANBgkqhkiG9w0BAQsFADAsMQswCQYDVQQGEwJk
      ZTENMAsGA1UECwwEZWZ0aTEOMAwGA1UEAwwFZXVkZTEwHhcNMjUwNzA5MDg1MjM0
      WhcNMjYwNzA5MDg1MjM0WjAsMQswCQYDVQQGEwJkZTENMAsGA1UECwwEZWZ0aTEO
      MAwGA1UEAwwFZXVkZTEwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDV
      jLHdxwR8C+HGGE1nu/Oz6jiSumjxg53bgNfYCGG8m92AlCgQuTt7ValibcOIt4gu
      HilBhdkA8ODqwegbf3RvrZzk8aAxjPofHwiH0vv4r+jo0y+q47wfWdI2UWGsBGm4
      zTcxflx8wpXt6wtO9M/XLVxmccv7cpeQMJsFuE3bRTwkkM7eiMluxERWYcES4xhz
      PLK8ks3oHODlueGxWzsRs8SqyxHY9SKW0ZI5V1RvttNX7LSBPGZI6qsNE6IXyoyw
      QKoUehr/m0HxoPQ9MUK7KJBKXULXaJ/RUJmnP7MBKZ8vErqW665xbmO0cQVEia8g
      ovyRfxHze/Iu05YJl633AgMBAAEwDQYJKoZIhvcNAQELBQADggEBAL5I/DItCOmk
      T9Ii2awbprzcGcsS3MQrOGEHTCIbyYLQ+lAGWn/Gnm7niQ3RPH8IxFBA+7ycC+Lf
      pL3/u5bRJTrPB0WHAzyRqKlBq4cn9RUgS4Winpfc0gb2jm+UCWS6zujj+JEM+uFZ
      FdS21Oy2VsZPNzHrBaGcVOSS+6TGI74ouRadkllU0/QW0F3x/4z0bw3LI3Qlmzhs
      YH1wbGm1dP1aylj4w0mnPTNiq9WNFY2pSqw6XKpBfT0RkeGDhl+yMsTaLCag6mtH
      96Jvfi9gBwapRbDb+Q85Z/yNZd9Av8Gpr23tUOpDv32IeEgU/4m7e1qe91q9oR1p
      WbIKjWjnu80=
      -----END CERTIFICATE-----
    """.trimIndent()
    expect(keyManager.certSki(cert.toX509())).toEqual("FFAdy/XBv9OZLogpN7IfBaIxtLI=")
  }
}
