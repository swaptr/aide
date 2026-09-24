package com.sabreware.aide.core.domain.llm

import com.sabreware.aide.core.domain.model.ModelDefaultConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Locks the sampler-default overlay: null fields keep the base value; set fields override. */
class ChatGenerationConfigTest {

    @Test
    fun nullDefaultsLeaveConfigUntouched() {
        val base = ChatGenerationConfig()
        assertSame(base, base.applyingSampler(null))
    }

    @Test
    fun partialDefaultsOverlayOnlySetFields() {
        val base = ChatGenerationConfig(maxTokens = 1024, topK = 40, topP = 0.95f, temperature = 0.8f)
        val out = base.applyingSampler(
            ModelDefaultConfig(temperature = 0.6f, maxTokens = 4096),
        )
        assertEquals(0.6f, out.temperature, 0f)
        assertEquals(4096, out.maxTokens)
        assertEquals(40, out.topK)            // null in defaults → untouched
        assertEquals(0.95f, out.topP, 0f)     // null in defaults → untouched
    }

    @Test
    fun fullDefaultsOverlayEveryField() {
        val out = ChatGenerationConfig().applyingSampler(
            ModelDefaultConfig(topK = 64, topP = 0.9f, temperature = 0.7f, maxTokens = 2048),
        )
        assertEquals(64, out.topK)
        assertEquals(0.9f, out.topP, 0f)
        assertEquals(0.7f, out.temperature, 0f)
        assertEquals(2048, out.maxTokens)
    }
}
