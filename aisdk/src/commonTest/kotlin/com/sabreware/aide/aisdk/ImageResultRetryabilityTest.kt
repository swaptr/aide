package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reference's `image-model-v4-result.ts` addition: a provider-independent retryability
 * classification on the image result, absent by default.
 */
class ImageResultRetryabilityTest {

    @Test
    fun `an image result is unclassified unless the provider says otherwise`() {
        val empty = ImageResult(images = emptyList(), response = ModalityResponse())

        assertNull(empty.isRetryable)
        assertEquals(false, empty.copy(isRetryable = false).isRetryable)
        assertEquals(true, empty.copy(isRetryable = true).isRetryable)
    }
}
