package com.sabreware.aide.data.llm.ollama

import kotlinx.coroutines.CancellationException
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.MetadataSource
import com.sabreware.aide.core.domain.model.ModelDefaultConfig
import com.sabreware.aide.core.domain.model.ModelDetail
import com.sabreware.aide.core.domain.model.ModelMetadata
import com.sabreware.aide.core.domain.model.ModelModality
import com.sabreware.aide.data.net.KtorClientFactory
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Live metadata source for Ollama-backed OpenAI-compatible servers, via Ollama's native `POST /api/show`.
 * Returns REAL capabilities for the actual served model — including exotic local tags (e.g. `gemma4:31b`)
 * that the models.dev registry can't know. Best-effort: if the server isn't Ollama (no `/api/show`) or the
 * call fails, returns null and the caller falls back to models.dev.
 *
 * The provider's base URL is the OpenAI-compatible one (`…/v1`); the native API lives one level up, so we
 * strip the `/v1` suffix and POST to `{root}/api/show`. The optional API key (Ollama Cloud) is forwarded
 * as a Bearer token.
 */
object OllamaMetadataProbe {

    suspend fun probe(baseUrl: String?, apiKey: String?, modelId: String): ModelMetadata? {
        val root = baseUrl?.trim()?.trimEnd('/')?.removeSuffix("/v1")?.trimEnd('/')
        if (root.isNullOrBlank()) return null
        val client = KtorClientFactory.finite()
        return try {
            val resp = client.post("$root/api/show") {
                contentType(ContentType.Application.Json)
                if (!apiKey.isNullOrBlank()) headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                setBody(ShowRequest(modelId))
            }
            if (!resp.status.isSuccess()) null else resp.body<ShowResponse>().toMetadata()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null // not an Ollama server / unreachable — caller falls back to the registry
        } finally {
            client.close()
        }
    }

    @Serializable
    private data class ShowRequest(val model: String, val verbose: Boolean = false)

    @Serializable
    private data class ShowResponse(
        val capabilities: List<String> = emptyList(),
        val details: Details = Details(),
        @SerialName("model_info") val modelInfo: JsonObject = JsonObject(emptyMap()),
        val license: String? = null,
        val parameters: String? = null,
    )

    @Serializable
    private data class Details(
        val family: String? = null,
        @SerialName("parameter_size") val parameterSize: String? = null,
        @SerialName("quantization_level") val quantizationLevel: String? = null,
    )

    private fun ShowResponse.toMetadata(): ModelMetadata {
        val caps = capabilities.map { it.lowercase() }.toSet()
        val arch = (modelInfo["general.architecture"] as? JsonPrimitive)?.content
        val contextTokens = arch?.let { (modelInfo["$it.context_length"] as? JsonPrimitive)?.intOrNull }
        val paramCount = (modelInfo["general.parameter_count"] as? JsonPrimitive)?.longOrNull

        val capabilities = ChatCapabilities(
            visionIn = "vision" in caps,
            audioIn = false, // Ollama exposes no audio capability flag
            toolsLocal = "tools" in caps,
            // Ollama supports structured output broadly via the `format` (json-schema) param.
            structuredOutput = ChatCapabilities.StructuredOutput.JsonSchema,
            thinking = if ("thinking" in caps) {
                ChatCapabilities.ThinkingMode.Toggle
            } else {
                ChatCapabilities.ThinkingMode.None
            },
            embeddings = "embedding" in caps,
            maxContext = contextTokens ?: 8192,
            // Ollama has no separate output cap (num_predict defaults unbounded) — the context window is
            // the only real ceiling.
            maxOutput = contextTokens ?: 8192,
            disabledParams = emptySet(), // local inference accepts temperature
        )
        val detail = ModelDetail(
            family = details.family,
            parameterSize = details.parameterSize,
            quantization = details.quantizationLevel,
            parameterCount = paramCount,
            architecture = arch,
            contextTokens = contextTokens,
            inputModalities = buildSet {
                add(ModelModality.Text)
                if ("vision" in caps) add(ModelModality.Image)
            },
            // license blobs can be huge — keep the first non-blank line as a label.
            license = license?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(80),
            source = MetadataSource.OLLAMA,
        )
        return ModelMetadata(capabilities, detail, parseSamplerDefaults(parameters))
    }

    // Ollama's `parameters` is a text block ("num_ctx 4096\ntemperature 0.7\n…") of the model's configured
    // defaults — map the sampler knobs to aide's per-model ModelDefaultConfig (fed through applyingSampler).
    private fun parseSamplerDefaults(parameters: String?): ModelDefaultConfig? {
        if (parameters.isNullOrBlank()) return null
        val kv = parameters.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1].trim().trim('"') else null
        }.toList()
        fun f(k: String) = kv.firstOrNull { it.first == k }?.second?.toFloatOrNull()
        fun i(k: String) = kv.firstOrNull { it.first == k }?.second?.toIntOrNull()
        val cfg = ModelDefaultConfig(
            topK = i("top_k"),
            topP = f("top_p"),
            temperature = f("temperature"),
            maxTokens = i("num_predict"),
            maxContextLength = i("num_ctx"),
        )
        val empty = cfg.topK == null && cfg.topP == null && cfg.temperature == null &&
            cfg.maxTokens == null && cfg.maxContextLength == null
        return if (empty) null else cfg
    }
}
