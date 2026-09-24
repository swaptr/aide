package com.sabreware.aide.aisdk.providers.mistral

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import com.sabreware.aide.aisdk.providers.openaicompatible.ToolChoiceDialect
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Mistral options file under. */
public const val MISTRAL_PROVIDER_ID: String = "mistral"

/**
 * Mistral: chat, embeddings, speech, and transcription from one object.
 *
 * Chat speaks the OpenAI wire and is wrapped rather than re-implemented — the `Vendors.mistral` row this
 * replaces is gone, because the wrap adds what a table row could not: the three cache-field spellings
 * Mistral emits, `image_url` as a bare string rather than an object, assistant reasoning replayed as a
 * `thinking` part (which Mistral requires), the automatic trailing-assistant `prefix`, and the
 * `model_length` finish reason. `tool_choice: "required"` is spelled `any` with no named-tool form, so a
 * pinned tool is expressed by narrowing the tool list.
 *
 * The other three endpoints do not speak that wire at all. Speech
 * wants `voice_id` where OpenAI says `voice` and answers JSON where OpenAI answers bytes
 * ([MistralSpeechModel]); transcription's timing and vocabulary options are its own multipart fields
 * ([MistralTranscriptionModel]); and the embedding endpoint differs in two ways that only show up
 * under load:
 *
 * - **32 values per call**, against the 2048 an OpenAI-compatible model assumes. A batch of 100 is a
 *   400 from the server rather than a partial result.
 * - **Parallel calls are not supported.** Mistral rate-limits embeddings per key hard enough that
 *   fanning a corpus out across coroutines makes indexing slower, not faster, once the 429s and their
 *   backoff are counted.
 *
 * `encoding_format: "float"` is sent unconditionally because Mistral's default is not documented, and a
 * server that decided to answer base64 would hand back strings where the caller expects numbers.
 *
 * Errors are `{"object": "error", "message": …}`, which `defaultErrorMessage` already reads.
 */
public class MistralProvider(
    client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Provider {

    override val providerId: String = MISTRAL_PROVIDER_ID

    private val http = ProviderHttp(client)

    private val compat = OpenAICompatibleProvider(
        client = client,
        providerId = MISTRAL_PROVIDER_ID,
        baseUrl = baseUrl,
        apiKey = apiKey,
        extraHeaders = extraHeaders,
        // `required` is spelled `any` here, and a named tool is expressed by narrowing the tool list.
        toolChoiceDialect = ToolChoiceDialect.Mistral,
        convertUsage = ::mistralUsage,
        transformRequestBody = ::mistralRequestBody,
    )

    /**
     * Mistral chat — see [MistralLanguageModel] for the four places it departs from the shared wire.
     *
     * Serving it here rather than only from the vendor table means one object per vendor: a caller that
     * needs chat and transcription no longer has to know that the two live in different places.
     */
    override fun languageModel(modelId: String): LanguageModel =
        MistralLanguageModel(requireNotNull(compat.languageModel(modelId)))

    override fun embeddingModel(modelId: String): EmbeddingModel = MistralEmbeddingModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = authHeaders(),
    )

    override fun speechModel(modelId: String): SpeechModel = MistralSpeechModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = authHeaders(),
    )

    override fun transcriptionModel(modelId: String): TranscriptionModel = MistralTranscriptionModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        headers = authHeaders(),
    )

    private fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey") + extraHeaders

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.mistral.ai/v1"
    }
}

internal class MistralEmbeddingModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : EmbeddingModel {

    override val provider: String = MISTRAL_PROVIDER_ID

    override suspend fun maxEmbeddingsPerCall(): Int = MAX_PER_CALL

    override suspend fun supportsParallelCalls(): Boolean = false

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        if (options.values.size > MAX_PER_CALL) {
            throw TooManyEmbeddingValuesForCallError(provider, modelId, MAX_PER_CALL, options.values)
        }
        val mistral = options.providerOptions?.get(MISTRAL_PROVIDER_ID)

        val body = buildJsonObject {
            put("model", modelId)
            put("input", buildJsonArray { options.values.forEach { add(JsonPrimitive(it)) } })
            mistral?.get("metadata")?.let { put("metadata", it) }
            mistral?.int("outputDimension")?.let { put("output_dimension", it) }
            mistral?.string("outputDtype")?.let { put("output_dtype", it) }
            put("encoding_format", "float")
        }

        val result = http.postJson(
            url = "$baseUrl/embeddings",
            body = body,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject

        return EmbeddingResult(
            // Mistral returns `data` in submission order and the reference does not re-sort it. Adding a
            // sort would be a guess about a promise the wire does not make either way.
            embeddings = response["data"]?.jsonArray.orEmpty().map { entry ->
                entry.jsonObject["embedding"]?.jsonArray?.map { it.jsonPrimitive.content.toDouble() }
                    .orEmpty()
            },
            usage = response["usage"]?.jsonObject?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
            response = result.modalityResponse(modelId = modelId),
        )
    }

    private companion object {
        /** Mistral's documented per-request ceiling, two orders of magnitude below OpenAI's. */
        const val MAX_PER_CALL = 32
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
