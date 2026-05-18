package com.swaptr.aide.domain.llm

import com.swaptr.aide.data.catalog.ModelBackend
import kotlinx.serialization.json.JsonObject

data class GenerationConfig(
    val maxTokens: Int = 1024,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val temperature: Float = 0.8f,
    val randomSeed: Int = 0,
    val backend: ModelBackend = ModelBackend.GPU,
    val thinking: ThinkingRequest = ThinkingRequest.Off,
    val responseSchema: JsonObject? = null,
    val stopSequences: List<String> = emptyList(),
) {
    sealed interface ThinkingRequest {
        data object Off : ThinkingRequest
        data object On : ThinkingRequest
        data class Level(val level: String) : ThinkingRequest
    }
}
