package com.sabreware.aide.aisdk.providers.openai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The routing rows of `openai-realtime-factory.test.ts`.
 *
 * Exact-id routing is the point: the near misses (`gpt-live`, `gpt-live-1-preview`, a prefixed or
 * upper-cased id) all stay on Realtime, and only the caller's explicit selector moves anything.
 */
class OpenAIRealtimeApiTest {

    @Test
    fun `known Live ids route to Live, everything else to Realtime`() {
        mapOf(
            "gpt-live-1" to OpenAIRealtimeApi.Live,
            "gpt-realtime" to OpenAIRealtimeApi.Realtime,
            "unknown" to OpenAIRealtimeApi.Realtime,
            "gpt-live" to OpenAIRealtimeApi.Realtime,
            "gpt-live-1-preview" to OpenAIRealtimeApi.Realtime,
            "prefix-gpt-live-1" to OpenAIRealtimeApi.Realtime,
            "GPT-LIVE-1" to OpenAIRealtimeApi.Realtime,
        ).forEach { (modelId, expected) ->
            assertEquals(expected, resolveOpenAIRealtimeApi(modelId), modelId)
        }
    }

    @Test
    fun `an explicit selector wins over the table in both directions`() {
        assertEquals(OpenAIRealtimeApi.Live, resolveOpenAIRealtimeApi("not-yet-released", OpenAIRealtimeApi.Live))
        assertEquals(OpenAIRealtimeApi.Live, resolveOpenAIRealtimeApi("gpt-realtime", OpenAIRealtimeApi.Live))
        assertEquals(OpenAIRealtimeApi.Realtime, resolveOpenAIRealtimeApi("gpt-live-1", OpenAIRealtimeApi.Realtime))
    }
}
