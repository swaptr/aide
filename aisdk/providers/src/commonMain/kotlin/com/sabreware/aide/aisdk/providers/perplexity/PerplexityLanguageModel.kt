package com.sabreware.aide.aisdk.providers.perplexity

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.providers.openai.OPENAI_RESPONSE_ID_KEY
import com.sabreware.aide.aisdk.providers.openai.OpenAIErrorStructure
import com.sabreware.aide.aisdk.providers.openai.OpenAIResponse
import com.sabreware.aide.aisdk.providers.openai.OpenAIResponsesChunk
import com.sabreware.aide.aisdk.providers.openai.OpenAIResponsesFrame
import com.sabreware.aide.aisdk.providers.openai.OpenAIResponsesStreamMapper
import com.sabreware.aide.aisdk.providers.openai.openAIFinishReason
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Perplexity's Agent API — `POST /v1/agent`, the research loop that replaced Sonar.
 *
 * It speaks OpenAI's Responses wire: the same `input` items, `function_call` / `function_call_output`
 * pair, `message` items with `output_text` and `url_citation` annotations, the same SSE lifecycle. So
 * the request builder, the tool preparation, the content mapper and the stream mapper are all the
 * shared Responses model's, parameterized by [PerplexityResponsesQuirks] the way xAI's and Azure's are.
 *
 * What this class adds is the part the shared model structurally cannot: Perplexity's OWN output items
 * (`search_results`, `fetch_url_results`, `finance_results`, `people_search_results`,
 * `sandbox_results`), its `response.reasoning.*` stream events, its usage block's `cost` and cache
 * names, and a `function_call`'s `thought_signature`. Every one lives in fields the typed Responses
 * item has no slot for, so they must be read off the RAW payload before the decode drops them — which
 * is why this is a thin owner of the transport around the shared pieces rather than a quirk value
 * alone. `PerplexityOutputMapper` holds the vendor mapping; nothing here interprets a wire shape twice.
 *
 * `/v1/agent` rather than the `/v1/responses` alias: the OpenAI-compatibility page (checked
 * 2026-09-02) says the alias exists so an OpenAI SDK pointed at the base URL resolves, that "both
 * endpoints accept identical requests and return the same response structure", and names `/v1/agent`
 * the canonical one — it is also the path the API reference, background polling and cancellation all
 * document. A client that builds its own URL has no reason to take the alias.
 */
internal class PerplexityLanguageModel(
    override val modelId: String,
    http: ProviderHttp,
    private val url: String,
    private val headers: Map<String, String>,
    private val generateId: () -> String = IdGenerator("src_")::next,
) : LanguageModel {

    override val provider: String = PERPLEXITY_PROVIDER_ID

    // `{"error":{"message":…,"type":…,"code":…}}` is the documented failure shape, and it is OpenAI's.
    private val http = http.withErrorStructure(OpenAIErrorStructure)

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildPerplexityRequest(modelId, options, stream = false)
        val result = http.postJson(url, built.body, combineHeaders(headers, options.headers))
        val raw = result.value as? JsonObject
            ?: throw InvalidResponseDataError("The Agent API answered with something other than a response.", data = result.value)
        val response = ProviderJson.decodeFromJsonElement(OpenAIResponse.serializer(), raw)

        // A 200 whose `status` is `failed` carries the reason in `error`; reading it as an empty turn
        // hands the caller a blank answer they were charged for.
        response.error?.let { error ->
            throw APICallError(
                message = error.message,
                url = url,
                requestBodyValues = result.requestBody,
                statusCode = result.statusCode,
                responseHeaders = result.headers,
                isRetryable = false,
                data = raw,
            )
        }

        val mapped = PerplexityOutputMapper(built.tools, generateId).map(raw["output"] as? JsonArray)
        val usage = raw.optObject("usage")
        return GenerateResult(
            content = mapped.content,
            finishReason = openAIFinishReason(
                response.incompleteDetails?.reason ?: raw.terminalStatus(),
                mapped.hasFunctionCall,
                PerplexityResponsesQuirks,
            ),
            usage = perplexityAgentUsage(usage),
            warnings = built.warnings,
            providerMetadata = responseMetadata(response.id, usage),
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
            response = result.responseInfo(modelId = response.model ?: modelId, id = response.id),
        )
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildPerplexityRequest(modelId, options, stream = true)
        return StreamResult(
            stream = streamParts(built, options),
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
        )
    }

    /**
     * The shared stream mapper over the frames it understands, with Perplexity's own frames answered
     * beside it.
     *
     * A frame the mapper would drop — a `response.reasoning.*` event, an `output_item.done` carrying a
     * vendor item — is turned into parts here and queued; the queue drains ahead of the mapper's next
     * emission, which keeps a source in front of the text that cites it. The terminal frame's raw
     * usage is kept so the mapper's `Finish` can be re-priced on the way out.
     */
    private fun streamParts(built: PerplexityRequest, options: CallOptions): Flow<StreamPart> = flow {
        val mapper = PerplexityOutputMapper(built.tools, generateId)
        val extras = ArrayDeque<StreamPart>()
        var usage: JsonObject? = null

        val frames: Flow<OpenAIResponsesFrame> = flow {
            http.postSse(url, built.body, combineHeaders(headers, options.headers)).collect { sse ->
                val raw = parseJsonElementOrNull(sse.data) as? JsonObject ?: return@collect
                val type = raw.optString("type") ?: return@collect
                val item = raw.optObject("item")
                when {
                    type.startsWith(REASONING_EVENT_PREFIX) -> {
                        if (options.includeRawChunks) extras += StreamPart.Raw(raw)
                        extras += mapper.reasoningParts(raw)
                        return@collect
                    }

                    type == OUTPUT_ITEM_DONE && item != null && mapper.isVendorItem(item) -> {
                        if (options.includeRawChunks) extras += StreamPart.Raw(raw)
                        extras += mapper.itemContent(item).mapNotNull { it.asPerplexityStreamPart() }
                        return@collect
                    }

                    type == OUTPUT_ITEM_DONE && item != null -> mapper.noteSignature(item)

                    type == "response.completed" || type == "response.incomplete" ->
                        usage = raw.optObject("response")?.optObject("usage")

                    else -> Unit
                }
                val chunk = runCatching {
                    ProviderJson.decodeFromJsonElement(OpenAIResponsesChunk.serializer(), raw)
                }.getOrNull() ?: return@collect
                // Perplexity puts a failure's `error` at the top of the frame, where the shared mapper
                // reads it from the nested `response` and would report "an error with no message".
                val patched = if (type == "response.failed") {
                    chunk.copy(message = chunk.error?.message ?: chunk.message)
                } else {
                    chunk
                }
                emit(OpenAIResponsesFrame(patched, raw.takeIf { options.includeRawChunks }))
            }
        }

        OpenAIResponsesStreamMapper(built.tools, url, generateId, PERPLEXITY_PROVIDER_ID, PerplexityResponsesQuirks)
            .map(frames, built.warnings)
            .collect { part ->
                while (extras.isNotEmpty()) emit(extras.removeFirst())
                mapper.adjust(part, usage)?.let { emit(it) }
            }
        while (extras.isNotEmpty()) emit(extras.removeFirst())
    }

    /** The response id (for `previousResponseId` chaining) and the priced call, under `perplexity`. */
    private fun responseMetadata(id: String?, usage: JsonObject?): ProviderMetadata? {
        val cost = usage.perplexityCost()
        if (id == null && cost == null) return null
        return mapOf(
            PERPLEXITY_PROVIDER_ID to buildJsonObject {
                id?.let { put(OPENAI_RESPONSE_ID_KEY, it) }
                cost?.let { put("cost", it) }
            },
        )
    }
}

/**
 * The terminal `status` values the API reference names beside `completed`, used as a finish reason
 * only when the response carries no `incomplete_details` — which this API does not document, so the
 * status is usually all there is.
 */
private fun JsonObject.terminalStatus(): String? =
    optString("status")?.takeIf { it == "failed" || it == "incomplete" || it == "cancelled" }
