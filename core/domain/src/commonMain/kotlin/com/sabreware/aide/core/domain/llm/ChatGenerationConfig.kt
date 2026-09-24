package com.sabreware.aide.core.domain.llm

import com.sabreware.aide.core.domain.model.ModelBackend
import com.sabreware.aide.core.domain.model.SamplerFields
import kotlinx.serialization.json.JsonObject

data class ChatGenerationConfig(
    val maxTokens: Int = 1024,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    // 1.0 is the neutral default shared by LiteRT (local) and OpenAI; a model's allowlist default or
    // the user's override overlays it via [applyingSampler].
    val temperature: Float = 1.0f,
    val backend: ModelBackend = ModelBackend.GPU,
    val thinking: ThinkingRequest = ThinkingRequest.Off,
    val responseSchema: JsonObject? = null,
    val stopSequences: List<String> = emptyList(),
    // Tool-call policy for the turn. Auto (default) omits the wire field; remote codecs map the rest
    // to their vendor `tool_choice` vocabulary. See [ToolChoice] for per-provider enforcement fidelity.
    val toolChoice: ToolChoice = ToolChoice.Auto,
) {
    sealed interface ThinkingRequest {
        data object Off : ThinkingRequest
        data object On : ThinkingRequest
        data class Level(val level: String) : ThinkingRequest
    }
}

/**
 * Overlays per-field sampling values onto this config; null fields keep the current value. Apply the
 * model's allowlist `defaultConfig` then the user's per-model override (override wins) so every provider
 * — local and remote — sees one resolved sampler.
 *
 * C8: `ModelDefaultConfig.maxContextLength` is intentionally NOT threaded here — it is a load-time
 * context-window size, not a per-turn sampling knob, and [ChatGenerationConfig] carries no context-length
 * field. Add one here only if a provider ever needs the window passed per request.
 */
fun ChatGenerationConfig.applyingSampler(fields: SamplerFields?): ChatGenerationConfig {
    if (fields == null) return this
    return copy(
        maxTokens = fields.maxTokens ?: maxTokens,
        topK = fields.topK ?: topK,
        topP = fields.topP ?: topP,
        temperature = fields.temperature ?: temperature,
    )
}
