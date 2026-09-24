package com.sabreware.aide.data.catalog

import com.sabreware.aide.core.common.storage.BundledAssetReader
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.MetadataSource
import com.sabreware.aide.core.domain.model.ModelCost
import com.sabreware.aide.core.domain.model.ModelDetail
import com.sabreware.aide.core.domain.model.ModelMetadata
import com.sabreware.aide.core.domain.model.ModelModality
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Capability + metadata source-of-truth for remote (cloud) models, from **models.dev** — the largest
 * community model registry (145+ providers). Capabilities are DATA, not code: a model id is matched against
 * the registry and a full [ModelMetadata] is derived (caps for gating + detail for the model sheet). Both
 * the `:aisdk` seam and the UI consume it, so upstream changes need no edits here. NO per-model markers /
 * per-provider guesses — unknown ids (exotic / local / custom tags) return null and the caller falls back.
 *
 * Common (KMP): the ~2 MB registry ships as a bundled application asset ([RESOURCE_PATH]) and is read through
 * an injected [BundledAssetReader], so every platform (Android/Desktop) reads the same source with no
 * filesystem or `Context` — and the data layer does not reach up into the UI module that packages it.
 * A bad/missing payload yields an empty index (defensive — lookups just fall back to defaults).
 *
 * NOTE — the previous Android build also refreshed this out-of-band from [REMOTE_URL] into a filesDir cache
 * (applied next launch, via the now-deleted `ModelsDevBootstrap`). That network-refresh override was dropped
 * in the KMP move; the bundled snapshot is the baseline. Re-add later behind an injected `suspend () -> String?`
 * override seam if it becomes load-bearing again.
 */
object RemoteModelMetadata {

    private const val TAG = "RemoteModelMetadata"

    // Bundled snapshot, resolved through the injected reader (composeResources/.../files/models_dev.json).
    private const val RESOURCE_PATH = "files/models_dev.json"

    /** Live models.dev registry (provenance / future background-refresh source). */
    const val REMOTE_URL = "https://models.dev/api.json"

    // Lenient + coercing so one malformed entry can't fail the whole ~2 MB parse (anti-brick).
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val loadMutex = Mutex()

    // null until loaded; empty map on load failure (both make lookups fall back to defaults).
    private var index: Map<String, ModelMetadata>? = null

    /**
     * Idempotent, concurrency-safe. Reads + parses the bundled registry on first call; subsequent calls are
     * a cheap no-op (and ignore [assets]). Suspends — the ~2 MB parse must not run on the UI thread, and the
     * bundled read is itself suspending.
     */
    suspend fun ensureLoaded(assets: BundledAssetReader) {
        if (index != null) return
        loadMutex.withLock {
            if (index != null) return
            index = runCatching { parse(assets.readBytes(RESOURCE_PATH).decodeToString()) }
                .onFailure { AideLog.w(TAG, "models.dev registry load failed; lookups fall back to defaults", it) }
                .getOrNull().orEmpty()
            AideLog.i(TAG, "models.dev registry loaded (${index?.size ?: 0} models)")
        }
    }

    /** Full metadata for [modelId] as models.dev reports it, or null when unknown / not yet loaded. */
    fun metadataFor(modelId: String): ModelMetadata? {
        val map = index ?: return null
        val id = modelId.lowercase()
        return map[id]
            ?: map[id.substringAfterLast('/')] // "openai/gpt-4o" → "gpt-4o"
            ?: map[id.substringBefore(':')]     // ollama-style "gemma3:27b" → "gemma3"
    }

    /** Capabilities only (catalog spec minting); delegates to [metadataFor]. */
    fun capabilitiesFor(modelId: String): ChatCapabilities? = metadataFor(modelId)?.capabilities

    private fun parse(text: String): Map<String, ModelMetadata> {
        val providers = json.decodeFromString(MapSerializer(String.serializer(), WireProvider.serializer()), text)
        val out = HashMap<String, ModelMetadata>(providers.values.sumOf { it.models.size })
        providers.values.forEach { provider ->
            provider.models.forEach { (id, model) ->
                val key = id.lowercase()
                if (key !in out) out[key] = model.toMetadata() // first provider wins on dup id (same caps)
            }
        }
        return out
    }

    // models.dev api.json wire shape (only the fields we map; the rest are ignored).
    @Serializable
    private data class WireProvider(val models: Map<String, WireModel> = emptyMap())

    @Serializable
    private data class WireModel(
        val family: String? = null,
        val reasoning: Boolean = false,
        val temperature: Boolean = true,
        @SerialName("tool_call") val toolCall: Boolean = true,
        @SerialName("structured_output") val structuredOutput: Boolean = false,
        @SerialName("reasoning_options") val reasoningOptions: List<WireReasoningOption> = emptyList(),
        val knowledge: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        @SerialName("open_weights") val openWeights: Boolean? = null,
        val modalities: WireModalities = WireModalities(),
        val limit: WireLimit = WireLimit(),
        val cost: WireCost? = null,
    )

    @Serializable
    // `values` element is nullable: models.dev lists `null` as the "effort off" option
    // (e.g. sarvam: ["values":[null,"low","medium","high"]]). A non-nullable `List<String>` makes the
    // whole ~2 MB parse throw on that null (coerceInputValues can't fix a null list element) — see filterNotNull below.
    private data class WireReasoningOption(val type: String = "", val values: List<String?> = emptyList())

    @Serializable
    private data class WireModalities(val input: List<String> = emptyList(), val output: List<String> = emptyList())

    @Serializable
    private data class WireLimit(val context: Int? = null, val output: Int? = null)

    @Serializable
    private data class WireCost(
        val input: Double? = null,
        val output: Double? = null,
        @SerialName("cache_read") val cacheRead: Double? = null,
    )

    private fun WireModel.toMetadata(): ModelMetadata {
        val caps = ChatCapabilities(
            visionIn = "image" in modalities.input,
            audioIn = "audio" in modalities.input,
            // models.dev spells native document support as "pdf" (some entries "document") — the same
            // token ModelModality.Document parses, so this data was already being fetched and dropped.
            documentIn = "pdf" in modalities.input || "document" in modalities.input,
            toolsLocal = toolCall,
            structuredOutput = if (structuredOutput) {
                ChatCapabilities.StructuredOutput.JsonSchema
            } else {
                ChatCapabilities.StructuredOutput.None
            },
            thinking = if (reasoning) ChatCapabilities.ThinkingMode.Toggle else ChatCapabilities.ThinkingMode.None,
            embeddings = false,
            maxContext = limit.context ?: 8192,
            maxOutput = limit.output ?: 4096,
            // models.dev `temperature: false` = the model rejects a custom temperature (o-series / gpt-5).
            disabledParams = if (temperature) emptySet() else setOf(ChatCapabilities.SamplerParam.Temperature),
        )
        val detail = ModelDetail(
            family = family,
            contextTokens = limit.context,
            maxOutputTokens = limit.output,
            inputModalities = modalities.input.mapNotNull { ModelModality.fromWire(it) }.toSet(),
            reasoningEfforts = reasoningOptions.firstOrNull { it.type == "effort" }?.values?.filterNotNull().orEmpty(),
            knowledgeCutoff = knowledge,
            releaseDate = releaseDate,
            openWeights = openWeights,
            cost = cost?.let { ModelCost(it.input, it.output, it.cacheRead) },
            source = MetadataSource.MODELS_DEV,
        )
        return ModelMetadata(capabilities = caps, detail = detail)
    }
}
