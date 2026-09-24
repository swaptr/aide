package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.applyingSampler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** Locks the override document (keyed by model id, empties dropped) + the base ← default ← override order. */
class SamplerOverridesTest {

    @Test
    fun documentRoundTripsMapKeyedByModelId() {
        val doc = SamplerOverrides(
            mapOf(
                "litert:gemma" to SamplerOverride(temperature = 0.6f, maxTokens = 2048),
                "ollama:llama3" to SamplerOverride(topK = 64),
            ),
        )
        val serializer = ModelDocuments.SamplerOverrides.serializer
        assertEquals(doc, Json.decodeFromString(serializer, Json.encodeToString(serializer, doc)))
    }

    @Test
    fun withUpsertsAndAnEmptyOverrideRemoves() {
        val set = SamplerOverrides().with("m", SamplerOverride(topK = 8))
        assertEquals(mapOf("m" to SamplerOverride(topK = 8)), set.byModel)
        assertEquals(SamplerOverride(topP = 0.5f), set.with("m", SamplerOverride(topP = 0.5f)).byModel["m"])
        assertEquals(emptyMap(), set.with("m", SamplerOverride()).byModel)
    }

    @Test
    fun overrideWinsOverModelDefaultWhichWinsOverBase() {
        val resolved = ChatGenerationConfig()
            .applyingSampler(ModelDefaultConfig(temperature = 0.5f, topK = 32))
            .applyingSampler(SamplerOverride(temperature = 0.9f))
        assertEquals(0.9f, resolved.temperature, 0f) // override wins
        assertEquals(32, resolved.topK)              // model default (no override) wins over base 40
        assertEquals(0.95f, resolved.topP, 0f)       // neither set → base
    }
}
