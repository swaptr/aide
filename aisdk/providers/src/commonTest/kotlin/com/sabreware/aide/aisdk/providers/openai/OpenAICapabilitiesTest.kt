package com.sabreware.aide.aisdk.providers.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GPT-6 rows of `openai-language-model-capabilities.test.ts`.
 *
 * GPT-6 is one version fact with four wire consequences — a closed effort list, mid-conversation
 * effort updates, async tools, and sampling closed again under `none` — and each is read off the
 * major version rather than a name table, so `gpt-99` gets them too and `gpt-5.6` does not.
 */
class OpenAICapabilitiesTest {

    @Test
    fun `GPT-6 and later support async tool calling`() {
        mapOf(
            "gpt-5.6" to false,
            "gpt-6-astra" to true,
            "gpt-6.1" to true,
            "gpt-7" to true,
            "gpt-99" to true,
            "custom-model" to false,
        ).forEach { (modelId, expected) ->
            assertEquals(expected, openAICapabilities(modelId).supportsAsyncToolCalling, modelId)
        }
    }

    @Test
    fun `GPT-6 and later support configuration updates`() {
        mapOf("gpt-5.6" to false, "gpt-6-astra" to true, "gpt-6.1" to true, "gpt-99" to true)
            .forEach { (modelId, expected) ->
                assertEquals(expected, openAICapabilities(modelId).supportsConfigurationUpdate, modelId)
            }
    }

    @Test
    fun `GPT-6 closes the reasoning effort list`() {
        assertFalse(openAICapabilities("gpt-5.6").enforcesEffortLevels)
        listOf("gpt-6-astra", "gpt-99").forEach { modelId ->
            val capabilities = openAICapabilities(modelId)
            assertTrue(capabilities.enforcesEffortLevels, modelId)
            assertEquals(setOf("low", "medium", "high", "xhigh", "max"), capabilities.effortLevels, modelId)
        }
    }

    @Test
    fun `GPT-6 no longer samples under effort none`() {
        assertTrue(openAICapabilities("gpt-5.6").allowsSamplingWhenEffortNone)
        assertFalse(openAICapabilities("gpt-5").allowsSamplingWhenEffortNone)
        assertFalse(openAICapabilities("gpt-6-astra").allowsSamplingWhenEffortNone)
        assertFalse(openAICapabilities("gpt-99").allowsSamplingWhenEffortNone)
    }

    @Test
    fun `gpt-6-astra is a reasoning model that takes its instructions as a developer`() {
        val capabilities = openAICapabilities("gpt-6-astra")
        assertTrue(capabilities.isReasoningModel)
        assertEquals("developer", capabilities.systemRole)
    }
}
