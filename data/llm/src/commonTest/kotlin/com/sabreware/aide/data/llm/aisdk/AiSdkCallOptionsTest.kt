package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.applyingSampler
import com.sabreware.aide.core.domain.model.SamplerOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What reaches the wire from AIDE's sampler config. The rule under test is the one that used to cap
 * every remote answer at 1,024 tokens: a knob still at the on-device baseline is unset, not a value.
 */
class AiSdkCallOptionsTest {

    private fun options(config: ChatGenerationConfig, disabled: Set<String> = emptySet()) =
        buildCallOptions(prompt = emptyList(), config = config, tools = emptyList(), activationState = null, disabledParams = disabled)

    @Test
    fun `an untouched config sends no sampler and no token cap`() {
        val wire = options(ChatGenerationConfig())

        assertNull(wire.maxOutputTokens, "the 1024 baseline is LiteRT's, not a remote model's ceiling")
        assertNull(wire.temperature)
        assertNull(wire.topP)
        assertNull(wire.topK)
    }

    @Test
    fun `a model default or a user override is sent, as the decimal the user typed`() {
        val wire = options(
            ChatGenerationConfig().applyingSampler(SamplerOverride(temperature = 0.7f, topP = 0.9f, topK = 50, maxTokens = 8192)),
        )

        assertEquals(8192, wire.maxOutputTokens)
        // 0.7f.toDouble() is 0.699999988079071; the vendor's logs should show what the user typed.
        assertEquals(0.7, wire.temperature)
        assertEquals(0.9, wire.topP)
        assertEquals(50, wire.topK)
    }

    @Test
    fun `a knob the catalog says the model rejects is omitted even when set`() {
        val wire = options(
            ChatGenerationConfig().applyingSampler(SamplerOverride(temperature = 0.2f, topP = 0.5f)),
            disabled = setOf("temperature", "topP"),
        )

        assertNull(wire.temperature)
        assertNull(wire.topP)
    }
}
