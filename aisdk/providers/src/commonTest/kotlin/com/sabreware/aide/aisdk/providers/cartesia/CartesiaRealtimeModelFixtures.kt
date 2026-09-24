package com.sabreware.aide.aisdk.providers.cartesia

/**
 * The inline bodies of `cartesia/src/cartesia-realtime-model.test.ts`, as JSON.
 *
 * The reference writes its frames as object literals and has no `__fixtures__` file for this model;
 * each literal is rendered here with the same keys, values and order, and nothing else. A fixture that
 * "improves" on the recorded wire pins our reading of it rather than the vendor's.
 */
internal object CartesiaRealtimeModelFixtures {

    /** `JSON.stringify({ token: 'access-token' })` — the mocked `/access-token` reply. */
    const val TOKEN_RESPONSE: String = """{"token":"access-token"}"""

    /** `createModel`'s pinned clock, `new Date('2026-07-08T12:00:00.000Z')`, as epoch millis. */
    const val NOW_MILLIS: Long = 1_783_512_000_000

    /** The `expiresAt` the reference expects for a 60-second token minted at [NOW_MILLIS]. */
    const val EXPIRES_AT: Long = 1_783_512_060

    /** 'creates an Ink 2 auto-finalize client secret' — the URL, verbatim. */
    const val TURNS_URL: String =
        "wss://api.cartesia.ai/stt/turns/websocket?model=ink-2&encoding=pcm_s16le&sample_rate=16000&cartesia_version=2026-03-01"

    /** 'uses the manual-finalize endpoint when turn detection is disabled' — the URL, verbatim. */
    const val MANUAL_URL: String =
        "wss://api.cartesia.ai/stt/websocket?model=ink-2&encoding=pcm_mulaw&sample_rate=8000&cartesia_version=2026-03-01&language=en"

    // 'maps Ink 2 turn events to normalized realtime events'
    const val CONNECTED: String = """{"type":"connected","request_id":"request-1"}"""
    const val TURN_START: String = """{"type":"turn.start","request_id":"request-1"}"""
    const val TURN_END: String = """{"type":"turn.end","request_id":"request-1","transcript":"Hello world"}"""

    // 'assembles manual transcript chunks when a flush completes'
    const val TRANSCRIPT_FIRST: String =
        """{"type":"transcript","request_id":"request-1","text":"Hello ","is_final":true,"duration":0.5}"""
    const val TRANSCRIPT_SECOND: String =
        """{"type":"transcript","request_id":"request-1","text":"world","is_final":true,"duration":0.4}"""
    const val FLUSH_DONE: String = """{"type":"flush_done","request_id":"request-1"}"""

    // 'emits one custom event when a manual stream closes without text'
    const val DONE: String = """{"type":"done","request_id":"request-1"}"""

    // 'maps structured Cartesia errors'
    const val ERROR: String =
        """{"type":"error","message":"Invalid model","error_code":"model_not_found","request_id":"request-1"}"""
}
