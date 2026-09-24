package com.sabreware.aide.core.domain.model

/**
 * Rich, on-demand metadata a provider serves for one model: [capabilities] (gating — drives the engines + UI),
 * [detail] (display — drives the model sheet), and optional [samplerDefaults]. Sourced fluidly per provider
 * (Ollama `/api/show`, models.dev, the local allowlist — see [ModelDetail.source]); never per-model hardcoded.
 */
data class ModelMetadata(
    val capabilities: ChatCapabilities,
    val detail: ModelDetail,
    val samplerDefaults: ModelDefaultConfig? = null,
)

/** Display-only superset; all-nullable — each source fills what it knows. Shown in the model detail sheet. */
data class ModelDetail(
    val family: String? = null,
    val parameterSize: String? = null,   // Ollama details.parameter_size, e.g. "31B"
    val quantization: String? = null,    // Ollama details.quantization_level, e.g. "Q4_K_M"
    val parameterCount: Long? = null,     // Ollama model_info["general.parameter_count"]
    val architecture: String? = null,     // Ollama model_info["general.architecture"]
    val contextTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputModalities: Set<ModelModality> = emptySet(),
    val reasoningEfforts: List<String> = emptyList(), // models.dev reasoning_options effort values (display + future)
    val license: String? = null,
    val knowledgeCutoff: String? = null,  // models.dev "2025-07-31"
    val releaseDate: String? = null,
    val openWeights: Boolean? = null,
    val cost: ModelCost? = null,           // cloud only
    val sizeBytes: Long? = null,           // local download size
    val source: MetadataSource,
)

/** Cost per million tokens (cloud). */
data class ModelCost(
    val inputPerMTok: Double? = null,
    val outputPerMTok: Double? = null,
    val cacheReadPerMTok: Double? = null,
)

/** Input modality a model accepts. Captures all of models.dev's set; the codec gates which are wired. */
enum class ModelModality {
    Text, Image, Audio, Video, Document;

    companion object {
        fun fromWire(token: String): ModelModality? = when (token.lowercase()) {
            "text" -> Text
            "image" -> Image
            "audio" -> Audio
            "video" -> Video
            "pdf", "document" -> Document
            else -> null
        }
    }
}

/** Where a model's metadata came from — shown as provenance in the sheet. */
enum class MetadataSource { OLLAMA, MODELS_DEV, ALLOWLIST, NEUTRAL }
