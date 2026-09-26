package edelivery

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.xml.crypto.dsig.spec.TransformParameterSpec

class SwaAttachmentContentSignatureTransformTest {
  private val transform = SwaAttachmentContentSignatureTransform()

  @Test fun `init rejects any parameters -- this transform takes none`() {
    assertThrows(java.security.InvalidAlgorithmParameterException::class.java) {
      transform.init(mockk<TransformParameterSpec>())
    }
  }

  @Test fun `init accepts a null spec`() {
    transform.init(null as TransformParameterSpec?)
    // no exception
  }

  @Test fun `has no parameter spec, marshalling and feature support`() {
    assertNull(transform.parameterSpec)
    assertFalse(transform.isFeatureSupported("anything"))
    transform.marshalParams(null, null)
    // no exception, nothing to marshal
  }

  @Test fun `transform(data, context) is an identity passthrough`() {
    val data = mockk<javax.xml.crypto.Data>()
    assertEquals(data, transform.transform(data, null))
  }
}
