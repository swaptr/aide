package com.sabreware.aide.aisdk.providers.anthropic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capability rows the reference delta touched: Claude Fable 5.1 (`4d25a08`, `c83bc67`) and the
 * dated Google Vertex Claude 4 ids (`65397d7`). Inline snapshots from
 * `anthropic-language-model.test.ts` (`getModelCapabilities`), read against the fields this table has.
 */
class AnthropicModelCapabilitiesTest {

    @Test
    fun `claude-fable-5-1 shares the fable 5 row`() {
        val caps = anthropicModelCapabilities("claude-fable-5-1")

        assertTrue(caps.known)
        assertEquals(128_000, caps.maxOutputTokens)
        assertTrue(caps.rejectsSamplingParameters)
        assertTrue(caps.supportsAdaptiveThinking)
        assertTrue(caps.supportsStructuredOutput)
        assertTrue(caps.supportsXhighEffort)
        // Ours, not the reference's: the family thinks on every turn and 400s on `disabled`.
        assertTrue(caps.rejectsDisabledThinking)
        assertFalse(caps.supportsExtendedThinking)
    }

    @Test
    fun `dated Vertex Claude 4 ids are recognised`() {
        // Vertex spells the date with `@`; a `-`-only match sent these adaptive thinking, a 400.
        listOf("claude-sonnet-4@20250514" to 64_000, "claude-opus-4@20250514" to 32_000).forEach { (id, max) ->
            val caps = anthropicModelCapabilities(id)
            assertTrue(caps.known, id)
            assertEquals(max, caps.maxOutputTokens, id)
            assertFalse(caps.supportsAdaptiveThinking, id)
            assertFalse(caps.supportsStructuredOutput, id)
        }
    }

    @Test
    fun `the dash-dated Claude 4 ids still resolve to the same rows`() {
        assertEquals(64_000, anthropicModelCapabilities("claude-sonnet-4-20250514").maxOutputTokens)
        assertEquals(32_000, anthropicModelCapabilities("claude-opus-4-20250514").maxOutputTokens)
        // And the more specific 4.x rows are not shadowed by the base-4 regex.
        assertEquals(128_000, anthropicModelCapabilities("claude-sonnet-4-6").maxOutputTokens)
        assertTrue(anthropicModelCapabilities("claude-opus-4-1").supportsStructuredOutput)
    }
}
