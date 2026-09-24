package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI's Responses API — `POST /v1/responses`.
 *
 * **This, not Chat Completions, is what `@ai-sdk/openai` means by OpenAI.** The distinction is not
 * stylistic. Chat Completions has no wire representation for reasoning at all, so an o-series or GPT-5
 * turn routed through it comes back as a token count and nothing replayable: the model re-derives its
 * entire chain of thought on every tool round and the caller is billed reasoning tokens for it each time.
 * The Responses API carries reasoning as a first-class `input` item with an id and, under
 * `store: false`, an `encrypted_content` payload — the OpenAI analogue of Anthropic's thinking
 * `signature` and Gemini's `thoughtSignature`, and the reason this port exists.
 *
 * Two request rules follow from that and are enforced in `buildResponsesRequest` rather than left to the
 * caller, because getting either wrong is silent: `store: false` on a reasoning model must be paired with
 * `include: ["reasoning.encrypted_content"]` — the call succeeds without it and returns reasoning with
 * nothing to replay — and the replayed reasoning item must carry back its id and payload verbatim.
 *
 * Both entry points are real. `doGenerate` reads the single JSON document, `doStream` reads the SSE
 * stream, and they share the request builder, the tool preparation and — for every item type that does
 * not stream — the same [toContent] mapper, so the two paths cannot report different content for the
 * same turn.
 */
internal class OpenAIResponsesLanguageModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val headers: Map<String, String> = emptyMap(),
    private val generateId: () -> String = IdGenerator("src_")::next,
    /**
     * The id this model reports, and half of where its options and metadata live — see [namespace].
     * Azure serves this wire as `azure.responses`, xAI as `xai`, an `open-responses` server as
     * `<name>.responses`; the model itself is the same one, which is the reason these are parameters
     * rather than subclasses.
     */
    override val provider: String = OPENAI_PROVIDER_ID,
    /**
     * The `providerOptions`/`providerMetadata` key for this endpoint. Canonical `openai` is ALWAYS
     * read underneath it, custom key winning field by field — the Anthropic-family rule DESIGN.md
     * records — and everything this model EMITS is filed under this key, so a conversation held
     * against Azure replays its items as Azure's rather than as OpenAI's.
     */
    private val namespace: String = OPENAI_PROVIDER_ID,
    /**
     * The complete endpoint, for vendors whose URL is not `{base}/responses` — Azure's carries an
     * `api-version` query. Null keeps the OpenAI shape.
     */
    endpointUrl: String? = null,
    /** The vendor's error envelope; xAI's differs from OpenAI's on one of its three shapes. */
    errorStructure: ProviderErrorStructure = OpenAIErrorStructure,
    private val quirks: ResponsesQuirks = ResponsesQuirks(),
    /**
     * The endpoint's Open Responses extensions — its namespaced tools, items and events, with the
     * codecs for them. Empty for OpenAI and every vendor but an `open-responses` server; see
     * [OpenResponsesExtension] for what one registers.
     */
    private val extensions: OpenResponsesExtensionRegistry = OpenResponsesExtensionRegistry.Empty,
) : LanguageModel {

    private val http = http.withErrorStructure(errorStructure)

    private val url: String = endpointUrl ?: "${baseUrl.trimEnd('/')}/responses"

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildResponsesRequest(modelId, options, stream = false, namespace, quirks, extensions)
        val result = this.http.postJson(url, built.body, requestHeaders(options))
        val response = ProviderJson.decodeFromJsonElement(OpenAIResponse.serializer(), result.value)

        // A 200 carrying an `error` object is OpenAI reporting a refusal it already charged for. Reading
        // it as an empty turn hands the caller a blank answer with no reason for it.
        response.error?.let {
            throw APICallError(
                message = it.message,
                url = url,
                requestBodyValues = result.requestBody,
                statusCode = result.statusCode,
                responseHeaders = result.headers,
                isRetryable = false,
                data = result.value,
            )
        }

        val mapped = response.output.orEmpty().toContent(built.tools, generateId, namespace, extensions)
        return GenerateResult(
            content = mapped.content,
            finishReason = openAIFinishReason(
                response.incompleteDetails?.reason,
                mapped.hasFunctionCall,
                quirks,
            ),
            usage = response.usage.decodeResponsesUsage()
                .toUsage(raw = response.usage, mayExcludeCached = quirks.usageMayExcludeCachedTokens),
            warnings = built.warnings,
            providerMetadata = responseMetadata(response, namespace),
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
            response = result.responseInfo(modelId = response.model ?: modelId, id = response.id),
        )
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildResponsesRequest(modelId, options, stream = true, namespace, quirks, extensions)
        return StreamResult(
            stream = streamParts(built, options),
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
        )
    }

    /**
     * A frame that fails to decode is SKIPPED rather than fatal.
     *
     * OpenAI keeps adding chunk types, and proxies inject keep-alives and error pages mid-stream. One
     * frame this port has never seen should cost nothing; ending the generation over it would make every
     * new event type an outage.
     */
    /**
     * The streaming decoder first — it is the hot path for every text delta — and the tree-building
     * transforming decoder only for what it exists for: an extension frame, whose namespaced `type` the
     * plain decode reports or whose object-valued `delta` makes it fail.
     */
    private fun decodeChunk(data: String, raw: JsonElement?): OpenAIResponsesChunk? {
        val plain = runCatching {
            if (raw != null) ProviderJson.decodeFromJsonElement(OpenAIResponsesChunk.serializer(), raw)
            else ProviderJson.decodeFromString(OpenAIResponsesChunk.serializer(), data)
        }.getOrNull()
        if (plain != null && ':' !in plain.type) return plain
        return runCatching {
            if (raw != null) ProviderJson.decodeFromJsonElement(OpenAIResponsesChunkSerializer, raw)
            else ProviderJson.decodeFromString(OpenAIResponsesChunkSerializer, data)
        }.getOrNull()
    }

    private fun streamParts(
        built: BuiltResponsesRequest,
        options: CallOptions,
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<StreamPart> {
        val frames = http.postSse(url, built.body, requestHeaders(options), onResponse).map { sse ->
            val raw = if (options.includeRawChunks) parseJsonElementOrNull(sse.data) else null
            decodeChunk(sse.data, raw)?.let { OpenAIResponsesFrame(it, raw) }
        }
        return OpenAIResponsesStreamMapper(built.tools, url, generateId, namespace, quirks, extensions)
            .map(frames.filterNotNullFrames(), built.warnings)
    }

    private fun requestHeaders(options: CallOptions): Map<String, String> =
        combineHeaders(headers, options.headers)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
    }
}

/** `service_tier` and the response id, which is what `previous_response_id` on the next turn needs. */
private fun responseMetadata(response: OpenAIResponse, namespace: String) = mapOf(
    namespace to buildJsonObject {
        response.id?.let { put(OPENAI_RESPONSE_ID_KEY, it) }
        response.serviceTier?.let { put("serviceTier", it) }
    },
).takeIf { response.id != null || response.serviceTier != null }

private fun Flow<OpenAIResponsesFrame?>.filterNotNullFrames(): Flow<OpenAIResponsesFrame> = flow {
    collect { frame -> frame?.let { emit(it) } }
}

/**
 * OpenAI's error envelope: `{"error":{"message":…,"type":…,"code":…}}`.
 *
 * Without it every failure reads as the raw JSON of the body inside a sentence, and `insufficient_quota`
 * — the one failure a caller must NOT retry, because every attempt fails identically — is indistinguishable
 * from the 429 that means "slow down", which is the one it must.
 */
internal val OpenAIErrorStructure: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        ((body as? JsonObject)?.get("error") as? JsonObject)?.get("message")?.asStringOrNull()
    },
    isRetryable = { _, body ->
        val error = (body as? JsonObject)?.get("error") as? JsonObject
        when (error?.get("code")?.asStringOrNull() ?: error?.get("type")?.asStringOrNull()) {
            "insufficient_quota", "invalid_api_key" -> false
            else -> null
        }
    },
)

private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content
