package com.sabreware.aide.aisdk.providers.openai

/**
 * Which of OpenAI's two realtime protocols a model is served on.
 *
 * `gpt-live-1` is the first model of the Live API — a different WebSocket protocol from the Realtime
 * API's, not a new model on the old one. The reference routes it by EXACT id: no prefix, no suffix, no
 * case folding, so `gpt-live-1-preview` and `GPT-LIVE-1` stay on Realtime until OpenAI says otherwise.
 * Everything unknown keeps the Realtime default, which is what an early-access Live id needs the
 * explicit [OpenAIRealtimeApi] override for.
 */
public enum class OpenAIRealtimeApi {
    /** The Live API — `/live/sessions`, server WebSocket, client-delegated tools. */
    Live,

    /** The Realtime API — `/realtime`, client secrets, the original protocol. */
    Realtime,
}

/** The protocol for [modelId]: the caller's [api] when given, else the known-Live table, else Realtime. */
public fun resolveOpenAIRealtimeApi(modelId: String, api: OpenAIRealtimeApi? = null): OpenAIRealtimeApi =
    api ?: if (modelId in KNOWN_LIVE_MODEL_IDS) OpenAIRealtimeApi.Live else OpenAIRealtimeApi.Realtime

private val KNOWN_LIVE_MODEL_IDS: Set<String> = setOf("gpt-live-1")
