package edelivery

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class JsonKeysTest {
  private fun dup(json: String) = JsonKeys.hasDuplicate(json)

  @Test fun `a repeated key in one object is found`() {
    assertTrue(dup("""{"verdict":"reject","verdict":"accept"}"""))
    assertTrue(dup("""{"a":1,"b":2,"a":3}"""))
  }

  @Test fun `a repeated key in a nested object or an object inside an array is found`() {
    assertTrue(dup("""{"signer":{"id":"a","id":"b"}}"""))
    assertTrue(dup("""{"items":[{"k":1},{"k":1,"k":2}]}"""))
  }

  @Test fun `keys are compared after unescaping`() {
    assertTrue(dup("""{"a":1,"\u0061":2}"""))
    assertTrue(dup("""{"a\nb":1,"a\u000ab":2}"""))
  }

  @Test fun `the same key in different objects is fine`() {
    assertFalse(dup("""{"a":{"id":1},"b":{"id":2}}"""))
    assertFalse(dup("""{"items":[{"k":1},{"k":2}],"k":3}"""))
  }

  @Test fun `values that look like keys, braces, quotes and escapes do not confuse it`() {
    assertFalse(dup("""{"a":"x\",\"a\":1","b":"{\"a\":1,\"a\":2}","c":["a","a"]}"""))
    assertFalse(dup("""{"a":1,"b":{"a":2},"c":[1,[2,{"a":3}]]}"""))
  }

  @Test fun `plain documents have no duplicate`() {
    assertFalse(dup("{}"))
    assertFalse(dup("[]"))
    assertFalse(dup("""{"verdict":"accept","audit_ref":"x","signer":{"verified_identity":"EU-DE"}}"""))
  }
}
