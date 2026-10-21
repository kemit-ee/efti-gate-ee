package edelivery

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurvisConfigTest {
  private fun load(vararg env: Pair<String, String>) = TurvisConfig.load(mapOf("OWN_GATE_ID" to "EU-EE", *env)::get)

  @Test fun `nothing set means legacy, no URL, no token and no Turvis call`() {
    val config = load()
    assertEquals(TurvisIntegrationMode.LEGACY, config.mode)
    assertNull(config.url)
    assertNull(config.token)
    assertEquals("EU-EE", config.keyHandle)
  }

  @Test fun `mode parsing accepts the three wire values case-insensitively and rejects anything else`() {
    assertEquals(TurvisIntegrationMode.LEGACY, TurvisIntegrationMode.parse(null))
    assertEquals(TurvisIntegrationMode.LEGACY, TurvisIntegrationMode.parse(" "))
    assertEquals(TurvisIntegrationMode.SHADOW, TurvisIntegrationMode.parse("Shadow"))
    assertEquals(TurvisIntegrationMode.TURVIS, TurvisIntegrationMode.parse("turvis"))
    assertEquals("turvis", TurvisIntegrationMode.TURVIS.wire)
    assertFailsWith<IllegalStateException> { TurvisIntegrationMode.parse("on") }
    assertFailsWith<IllegalStateException> { load("TURVIS_INTEGRATION_MODE" to "enabled") }
  }

  @Test fun `shadow and turvis need both the URL and the token`() {
    for (mode in listOf("shadow", "turvis")) {
      assertFailsWith<IllegalStateException> { load("TURVIS_INTEGRATION_MODE" to mode) }
      assertFailsWith<IllegalStateException> { load("TURVIS_INTEGRATION_MODE" to mode, "TURVIS_URL" to "http://turvis:8080") }
      assertFailsWith<IllegalStateException> { load("TURVIS_INTEGRATION_MODE" to mode, "TURVIS_INTERNAL_TOKEN" to "t") }
      assertFailsWith<IllegalStateException> { load("TURVIS_INTEGRATION_MODE" to mode, "TURVIS_URL" to "http://turvis:8080", "TURVIS_INTERNAL_TOKEN" to "") }
      val config = load("TURVIS_INTEGRATION_MODE" to mode, "TURVIS_URL" to "http://turvis:8080", "TURVIS_INTERNAL_TOKEN" to "t")
      assertEquals("http://turvis:8080", config.baseUrl.toString())
      assertEquals("t", config.internalToken)
    }
  }

  @Test fun `the Turvis token must be dedicated, not the fleet-wide internal service token`() {
    assertFailsWith<IllegalStateException> {
      load("TURVIS_INTEGRATION_MODE" to "shadow", "TURVIS_URL" to "http://turvis:8080", "TURVIS_INTERNAL_TOKEN" to "same", "INTERNAL_SERVICE_TOKEN" to "same")
    }
    load("TURVIS_INTEGRATION_MODE" to "shadow", "TURVIS_URL" to "http://turvis:8080", "TURVIS_INTERNAL_TOKEN" to "own", "INTERNAL_SERVICE_TOKEN" to "other")
  }

  @Test fun `an unusable Turvis URL is ignored in legacy and refused, without echoing it, in the other modes`() {
    for (url in listOf("ftp://user:pass@turvis", "turvis:8080", "/relative/path", "http://", "http://user:pw@a b")) {
      assertNull(load("TURVIS_URL" to url).url, url)
      for (mode in listOf("shadow", "turvis")) {
        val e = assertFailsWith<IllegalArgumentException>("$mode $url") { load("TURVIS_INTEGRATION_MODE" to mode, "TURVIS_URL" to url, "TURVIS_INTERNAL_TOKEN" to "t") }
        assertEquals(false, e.message!!.contains("pass") || e.message!!.contains("pw") || e.message!!.contains(url))
      }
    }
  }

  @Test fun `OWN_GATE_ID is required for the key handle`() {
    assertFailsWith<IllegalStateException> { TurvisConfig.load(emptyMap<String, String>()::get) }
  }

  @Test fun `the mock host is refused in prod, production and staging in every mode`() {
    for (variable in listOf("ENVIRONMENT", "APP_ENV", "DEPLOY_ENV")) {
      for (value in listOf("prod", "PRODUCTION", "Staging")) {
        for (mode in listOf("legacy", "shadow", "turvis")) {
          val env = arrayOf("TURVIS_INTEGRATION_MODE" to mode, "TURVIS_URL" to "http://turvis-mock:8091", "TURVIS_INTERNAL_TOKEN" to "t", variable to value)
          assertFailsWith<IllegalStateException>("$variable=$value mode=$mode") { load(*env) }
        }
      }
    }
    assertFailsWith<IllegalStateException> { load("TURVIS_URL" to "http://Turvis-Mock:8091", "ENV" to "prod") }
  }

  @Test fun `fully-qualified and differently written forms of the mock host are refused too`() {
    for (host in listOf("turvis-mock.", "TURVIS-MOCK", "turvis-mock.efti", "turvis-mock.svc.cluster.local", "Turvis-Mock.Efti.Svc.", "turvis-mock.example.com")) {
      assertFailsWith<IllegalStateException>(host) { load("TURVIS_URL" to "http://$host:8091", "ENVIRONMENT" to "prod") }
    }
    assertTrue(TurvisConfig.isMockHost("turvis-mock."))
    assertTrue(TurvisConfig.isMockHost("turvis-mock.svc"))
  }

  @Test fun `similar names that are not the mock are not refused`() {
    for (host in listOf("turvis-mocked", "my-turvis-mock", "turvis", "turvis-mock-2", "evil.example", "127.0.0.1")) {
      load("TURVIS_URL" to "http://$host:8091", "ENVIRONMENT" to "prod")
    }
    assertEquals(false, TurvisConfig.isMockHost(null))
  }

  @Test fun `an unparseable URL does not stop legacy, fails the other modes and is never echoed`() {
    assertNull(load("TURVIS_URL" to "http://a b:pw@x").url)
    for (mode in listOf("shadow", "turvis")) {
      val e = assertFailsWith<IllegalArgumentException> { load("TURVIS_INTEGRATION_MODE" to mode, "TURVIS_URL" to "http://user:pw@a b", "TURVIS_INTERNAL_TOKEN" to "t") }
      assertEquals(false, e.message!!.contains("pw"))
    }
  }

  @Test fun `the mock host is allowed outside prod and staging and other hosts are allowed everywhere`() {
    load("TURVIS_URL" to "http://turvis-mock:8091")
    load("TURVIS_URL" to "http://turvis-mock:8091", "ENVIRONMENT" to "dev")
    load("TURVIS_URL" to "http://turvis-mock:8091", "APP_ENV" to "test")
    load("TURVIS_URL" to "http://turvis:8080", "ENVIRONMENT" to "prod")
    load("TURVIS_URL" to "https://turvis.example/turvis-mock", "ENVIRONMENT" to "staging")
    assertTrue(true)
  }

  @Test fun `every environment name that is set counts, so one variable cannot hide another`() {
    assertEquals(setOf("prod", "dev"), TurvisConfig.environments(mapOf("ENVIRONMENT" to " Prod ", "APP_ENV" to "dev")::get))
    assertEquals(setOf("dev"), TurvisConfig.environments(mapOf("ENVIRONMENT" to "", "APP_ENV" to "dev")::get))
    assertEquals(setOf("staging"), TurvisConfig.environments(mapOf("DEPLOY_ENV" to "staging")::get))
    assertEquals(setOf("test", "prod"), TurvisConfig.environments(mapOf("ENV" to "test, PROD")::get))
    assertEquals(emptySet(), TurvisConfig.environments(emptyMap<String, String>()::get))
    assertFailsWith<IllegalStateException> { load("TURVIS_URL" to "http://turvis-mock:8091", "APP_ENV" to "dev", "DEPLOY_ENV" to "prod") }
    assertFailsWith<IllegalStateException> { load("TURVIS_URL" to "http://turvis-mock:8091", "ENV" to "dev,staging") }
  }
}
