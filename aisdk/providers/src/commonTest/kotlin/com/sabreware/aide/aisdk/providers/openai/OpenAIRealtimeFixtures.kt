package com.sabreware.aide.aisdk.providers.openai

/**
 * The reference's realtime fixtures, byte-for-byte.
 *
 * `openai/src/realtime/` has no `__fixtures__` directory: its two test files hold their expectations
 * inline as object literals, and those are reproduced here as the JSON they serialize to. Server-frame
 * fixtures are absent upstream — the mapper is tested only through the session builder — so the frames
 * in `OpenAIRealtimeEventMapperTest` are built from the mapper's own wire type
 * (`OpenAIRealtimeWireEvent`, `openai-realtime-event-mapper.ts:7`) and the documented examples.
 */
internal object OpenAIRealtimeFixtures {

    /** `openai-realtime-model.test.ts:16` — the mocked client-secret response. */
    const val CLIENT_SECRET_RESPONSE: String = """{"value":"secret","expires_at":123}"""

    /** `openai-realtime-model.test.ts:40` — what `expires_after` must serialize to for a 60 s ttl. */
    const val EXPIRES_AFTER_60S: String = """{"anchor":"created_at","seconds":60}"""

    /** `openai-realtime-event-mapper.test.ts:11` — `audio` when input transcription is enabled bare. */
    const val AUDIO_TRANSCRIPTION_DEFAULT_MODEL: String =
        """{"input":{"transcription":{"model":"gpt-realtime-whisper"}}}"""

    /** `openai-realtime-event-mapper.test.ts:32` — `audio` with every transcription option set. */
    const val AUDIO_TRANSCRIPTION_OPTIONS: String =
        """{"input":{"transcription":{"model":"gpt-4o-mini-transcribe","language":"en",""" +
            """"prompt":"Transcribe short voice chat messages."}}}"""
}
