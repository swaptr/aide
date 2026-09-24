package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.providers.openai.normalizeOpenAIJsonSchema
import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.providers.groq.GROQ_BROWSER_SEARCH_MODELS
import com.sabreware.aide.aisdk.providers.groq.supportsGroqBrowserSearch
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Chat Completions, for OpenAI and every server that speaks its wire.
 *
 * One codec reaches Ollama, LM Studio, vLLM, llama.cpp, Groq, Together, Fireworks, DeepSeek, OpenRouter
 * and OpenAI itself — the widest surface per line of code in the whole library.
 *
 * **Three reasoning channels**, because the ecosystem has three and a client that handles one is broken
 * against the other two:
 *
 * 1. `reasoning_details` — OpenRouter's typed blocks, carrying an Anthropic `signature` or encrypted
 *    `data`. Stored opaquely per block and replayed unmodified.
 * 2. `reasoning_content` — DeepSeek-R1 style, a plain string on its own channel. Stored under
 *    [REASONING_CONTENT_KEY] so the next turn can echo it back; a server that requires the echo
 *    otherwise sees a turn its own model did not produce.
 * 3. Inline `<think>` tags — some servers embed reasoning in `content`. Opt-in via
 *    [extractInlineReasoning], because a model that legitimately writes the literal text `<think>` should
 *    not have it eaten.
 *
 * Mistral is a fourth shape rather than a fourth channel: its reasoning models stream `delta.content` as
 * an array of typed parts instead of a string. That is handled in [contentParts] rather than here, so
 * the loop below sees the same two kinds of part from every server.
 *
 * Everything a vendor does differently is [capabilities], [toolChoiceDialect], [convertUsage] or
 * [errorStructure] — DATA, passed in by the table in [Vendors]. Writing a subclass per vendor is how
 * this file would become twenty places to fix the same bug.
 */
internal class OpenAICompatibleLanguageModel(
    override val provider: String,
    override val modelId: String,
    private val http: ProviderHttp,
    /**
     * The COMPLETE chat-completions endpoint, not a base.
     *
     * Azure is why: its deployment name goes in the path and it requires an `api-version` query
     * parameter, so `"$base/chat/completions"` cannot express it. Taking the whole URL makes every
     * vendor's shape expressible without a special case here.
     */
    private val chatUrl: String,
    private val headers: Map<String, String> = emptyMap(),
    private val extractInlineReasoning: Boolean = false,
    private val capabilities: OpenAICompatibleCapabilities = OpenAICompatibleCapabilities(),
    private val toolChoiceDialect: ToolChoiceDialect = ToolChoiceDialect.OpenAI,
    /**
     * Replaces the token arithmetic — see [defaultOpenAICompatibleUsage] for why it takes raw JSON.
     *
     * A function rather than an enum member because every divergent vendor's arithmetic is its own: a
     * new one would otherwise cost a public enum case plus a branch in a shared `when` that every other
     * vendor recompiles past.
     */
    private val convertUsage: ((JsonObject) -> Usage)? = null,
    /**
     * Last word on the request body, applied AFTER the caller's provider options are spread in.
     *
     * Reaches what nothing else can: the keys this MODEL writes. Cerebras wants `max_tokens` spelled
     * `max_completion_tokens`, and Fireworks accepts only three of the five reasoning-effort levels, so
     * both have to rewrite a value the engine chose rather than one the caller passed — which a wrapper
     * around this model cannot do, because at that point the body does not exist yet.
     *
     * It is deliberately NOT a place to rename a caller's own options: this port spreads
     * `providerOptions` verbatim, so a key the caller spelled correctly already reaches the wire.
     */
    private val transformRequestBody: ((JsonObject) -> JsonObject)? = null,
    /** Which of its own server-side tools this vendor extends Chat Completions with, if any. */
    private val providerToolDialect: ProviderToolDialect = ProviderToolDialect.None,
    /** Some servers reject `stream_options`; turn it off and lose only the token counts. */
    private val includeUsage: Boolean = true,
    /**
     * Cerebras: a structured-output turn can return valid JSON text AND repeat a tool call, with
     * `finish_reason: "tool_calls"`. With this set, that mixed response is treated as the final
     * answer — the repeated calls are suppressed and the unified finish reason becomes Stop (the raw
     * string is preserved). Tool parts already emitted before the first text delta are not
     * retracted; the same edge the reference accepts.
     */
    private val jsonToolCallsFinishIsStop: Boolean = false,
    /** How this vendor spells an error, for the `data: {"error":…}` frames that arrive mid-stream. */
    private val errorStructure: ProviderErrorStructure = ProviderErrorStructure.Default,
    private val sourceIds: IdGenerator = IdGenerator("src_"),
) : LanguageModel {

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildRequest(options)
        return StreamResult(
            stream = streamParts(built, options),
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
        )
    }

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildRequest(options)
        // The response headers only exist once the stream has been opened, which is inside the flow.
        var opened: HttpResult<Unit>? = null
        val assembled = assembleGenerateResult(streamParts(built, options) { opened = it })
        return assembled.copy(
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
            response = ResponseInfo(
                metadata = assembled.response?.metadata ?: ResponseMetadata(),
                headers = opened?.headers,
            ),
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun streamParts(
        built: BuiltRequest,
        options: CallOptions,
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<StreamPart> = flow {
        emit(StreamPart.StreamStart(built.warnings))
        val splitter = if (extractInlineReasoning) ReasoningTagSplitter() else null
        val tools = OpenAIToolCallAccumulator(provider)
        val blocks = BlockCursor()
        val seenSources = mutableSetOf<String>()
        var sentMetadata = false
        var failed = false
        var finish: FinishReason? = null
        var usage = Usage()
        var sawText = false
        var sawChoice = false
        // See [jsonToolCallsFinishIsStop]. Evaluated live: text may start mid-stream.
        fun suppressRepeatedToolCalls() = jsonToolCallsFinishIsStop &&
            options.responseFormat is ResponseFormat.Json && sawText

        suspend fun closeReasoning() {
            val id = blocks.reasoning ?: return
            emit(StreamPart.ReasoningEnd(id, providerMetadata = blocks.reasoningMetadata(provider)))
            blocks.endReasoning()
        }

        suspend fun openReasoning(): String = blocks.reasoning
            ?: blocks.startReasoning().also { emit(StreamPart.ReasoningStart(it)) }

        suspend fun text(delta: String) {
            sawText = true
            // Reasoning closes when the answer starts, so a model that thinks, answers, then thinks
            // again produces two blocks in the order it wrote them rather than one merged one.
            closeReasoning()
            val id = blocks.text ?: blocks.startText().also { emit(StreamPart.TextStart(it)) }
            emit(StreamPart.TextDelta(id, delta))
        }

        suspend fun reasoning(delta: String, replayable: Boolean) {
            emit(StreamPart.ReasoningDelta(openReasoning(), delta))
            if (replayable) blocks.recordReasoningText(delta)
        }

        suspend fun fail(error: Throwable) {
            // Latched: a stream that has already failed must not be talked back into success by a
            // later `finish_reason: "stop"`. A proxy that injects an error page and then replays the
            // upstream's tail does exactly that, and the turn would report Stop over a hole in the text.
            failed = true
            finish = FinishReason(FinishReason.Unified.Error)
            emit(StreamPart.Error(error))
        }

        http.postSse(chatUrl, built.body, built.headers, onResponse = onResponse).collect { sse ->
            val frame = parseJsonElementOrNull(sse.data)
            if (frame == null) {
                // Swallowing this is how an upstream 502 on an aggregator, an injected HTML error page
                // and a truncated final frame all presented as a short-but-successful answer.
                fail(JsonParseError(text = sse.data))
                return@collect
            }
            if (options.includeRawChunks) emit(StreamPart.Raw(frame))

            val errorFrame = (frame as? JsonObject)?.get("error")?.takeIf { it != JsonNull }
            if (errorFrame != null) {
                // A rate limit hit mid-generation arrives here, on an HTTP 200, so no retry policy has
                // seen it and `ignoreUnknownKeys` decodes the frame to an empty chunk.
                fail(
                    APICallError(
                        message = errorStructure.extractMessage(frame)
                            ?: "The provider reported an error mid-stream.",
                        url = chatUrl,
                        requestBodyValues = ProviderJson.encodeToString(
                            JsonElement.serializer(),
                            built.body,
                        ),
                        data = frame,
                        // Asked rather than assumed. A mid-stream refusal arrives on an HTTP 200, so no
                        // status code says whether trying again could work: DeepSeek's `insufficient_quota`
                        // fails identically forever where its `rate_limit_exceeded` clears. Vendors that
                        // say nothing keep the old optimistic default.
                        isRetryable = errorStructure.isRetryable(HTTP_OK, frame) ?: true,
                    ),
                )
                return@collect
            }

            val chunk = runCatching {
                ProviderJson.decodeFromJsonElement(OpenAIStreamChunk.serializer(), frame)
            }.getOrElse {
                fail(InvalidResponseDataError("Malformed chunk on the stream.", data = frame, cause = it))
                return@collect
            }

            if (!sentMetadata) {
                sentMetadata = true
                emit(
                    StreamPart.ResponseMetadataPart(
                        ResponseMetadata(
                            id = chunk.id,
                            modelId = chunk.model,
                            // `created` is epoch SECONDS on this wire and epoch MILLISECONDS in the
                            // contract; passing it through put every timestamp in January 1970.
                            timestamp = chunk.created?.times(MILLIS_PER_SECOND),
                        ),
                    ),
                )
            }

            // Read off the RAW frame, not the decoded chunk: a vendor converter needs the fields this
            // wire model has never heard of, and `ignoreUnknownKeys` has already dropped them by here.
            // `x_groq` is Groq's envelope — read unconditionally, since no other server emits it.
            rawUsage(frame)?.let { usage = (convertUsage ?: ::defaultOpenAICompatibleUsage)(it) }

            chunk.citations?.forEach { url ->
                // Perplexity repeats the whole list on every chunk and xAI sends it on the last, so the
                // same source would otherwise be emitted once per chunk of the answer.
                if (seenSources.add(url)) {
                    emit(StreamPart.SourcePart(Content.Source.Url(id = sourceIds.next(), url = url)))
                }
            }

            val choice = chunk.choices.firstOrNull()
            if (choice != null) sawChoice = true
            val delta = choice?.delta

            // 1. Structured reasoning blocks (OpenRouter). Kept whole; never rebuilt.
            delta?.reasoningDetails?.takeIf { it.isNotEmpty() }?.let { details ->
                openReasoning()
                blocks.recordReasoningDetails(details)
                details.forEach { block ->
                    (block["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { reasoning(it, replayable = false) }
                }
            }

            // 2. A dedicated reasoning string channel.
            (delta?.reasoningContent ?: delta?.reasoning)?.takeIf { it.isNotEmpty() }
                ?.let { reasoning(it, replayable = true) }

            // 3. Content, which is text on every server but Mistral's reasoning models.
            delta?.content?.contentParts().orEmpty().forEach { part ->
                when (part) {
                    is OpenAIContentPart.Thinking -> reasoning(part.text, replayable = false)
                    is OpenAIContentPart.Text -> if (splitter == null) {
                        text(part.text)
                    } else {
                        val out = splitter.push(part.text)
                        if (out.thinking.isNotEmpty()) reasoning(out.thinking, replayable = false)
                        if (out.text.isNotEmpty()) text(out.text)
                    }
                }
            }

            // An EMPTY `tool_calls: []` beside a reasoning delta is a chunk with no call in it, so it must
            // not end the reasoning block: a vendor that sends the array on every delta would otherwise
            // split one thought into a block per chunk.
            delta?.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                if (!suppressRepeatedToolCalls()) {
                    closeReasoning()
                    calls.forEach { tools.accept(this, it) }
                }
            }

            if (!failed) choice?.finishReason?.let { finish = it.toFinishReason() }
        }

        splitter?.flush()?.let { out ->
            if (out.thinking.isNotEmpty()) reasoning(out.thinking, replayable = false)
            if (out.text.isNotEmpty()) text(out.text)
        }

        closeReasoning()
        blocks.text?.let { emit(StreamPart.TextEnd(it)) }
        // A partial call accepted before the first text delta must not flush either.
        if (!suppressRepeatedToolCalls()) tools.finish(this)

        if (finish == null && !failed) {
            // A dropped socket ends the stream cleanly with no finish_reason. Reporting `Other` here is
            // what made a truncated answer look complete, so nothing above retried it. A response that
            // never carried a choice at all is named as such: the reference indexes `choices[0]`, and an
            // empty array is a typed error there rather than a crash.
            fail(InvalidResponseDataError(if (sawChoice) NO_FINISH_MESSAGE else NO_CHOICES_MESSAGE))
        }

        val corrected = finish?.let { resolved ->
            if (suppressRepeatedToolCalls() && resolved.raw == "tool_calls") {
                FinishReason(FinishReason.Unified.Stop, raw = resolved.raw)
            } else {
                resolved
            }
        }
        emit(
            StreamPart.Finish(
                usage = usage,
                finishReason = corrected ?: FinishReason(FinishReason.Unified.Error),
            ),
        )
    }

    private fun buildRequest(options: CallOptions): BuiltRequest {
        val warnings = mutableListOf<Warning>()
        val reasoningModel = capabilities.isReasoningModel
        // gpt-5.1 and later take the samplers back when the caller explicitly asks for no reasoning.
        val samplersGated = reasoningModel &&
            !(options.reasoning == ReasoningEffort.None && capabilities.supportsNonReasoningParameters)

        fun <T> gate(param: SamplerParam, value: T?, gatedByReasoning: Boolean = false): T? {
            if (value == null) return null
            if (param in capabilities.unsupportedSamplers) {
                warnings += Warning.Unsupported(param.wireName, "This model rejects it; the value was dropped.")
                return null
            }
            if (gatedByReasoning) {
                warnings += Warning.Unsupported(
                    param.wireName,
                    "${param.wireName} is not supported for reasoning models",
                )
                return null
            }
            return value
        }

        val prepared = prepareTools(
            options, toolChoiceDialect, providerToolDialect, modelId, warnings,
            normalizeSchema = capabilities.normalizesJsonSchema,
        )

        val encodedMessages = options.prompt
            .toOpenAIMessagesWithExtras(provider, systemRole = capabilities.systemRole)
            .also { it.messages.orThrowIfEmpty() }

        val request = OpenAIChatRequest(
            model = modelId,
            messages = encodedMessages.messages,
            stream = true,
            streamOptions = if (includeUsage) OpenAIStreamOptions(includeUsage = true) else null,
            // The o-series and gpt-5 return 400 on `max_tokens`; the limit MOVES rather than being
            // dropped, because a caller that asked for a ceiling still needs one.
            maxTokens = options.maxOutputTokens.takeUnless { reasoningModel },
            maxCompletionTokens = options.maxOutputTokens.takeIf { reasoningModel },
            temperature = gate(SamplerParam.Temperature, options.temperature, samplersGated),
            topP = gate(SamplerParam.TopP, options.topP, samplersGated),
            topK = topK(options.topK, warnings),
            // Rejected for every reasoning model regardless of effort, unlike temperature and top_p.
            presencePenalty = gate(SamplerParam.PresencePenalty, options.presencePenalty, reasoningModel),
            frequencyPenalty = gate(SamplerParam.FrequencyPenalty, options.frequencyPenalty, reasoningModel),
            seed = gate(SamplerParam.Seed, options.seed),
            stop = options.stopSequences?.takeIf { it.isNotEmpty() },
            tools = prepared.tools,
            toolChoice = prepared.toolChoice,
            responseFormat = options.responseFormat?.toOpenAIResponseFormat(
                capabilities.supportsStructuredOutputs,
                warnings,
                normalizeSchema = capabilities.normalizesJsonSchema,
            ),
            reasoningEffort = options.reasoning.toWireEffort(),
        )

        val encoded = (ProviderJson.encodeToJsonElement(OpenAIChatRequest.serializer(), request) as JsonObject)
            .withMessageOptions(encodedMessages.extras)
        return BuiltRequest(
            body = encoded.withProviderOptions(options.providerOptions?.get(provider))
                .withOpenAIEffortRules(capabilities, modelId, warnings)
                .let { transformRequestBody?.invoke(it) ?: it },
            headers = combineHeaders(headers, options.headers),
            warnings = warnings,
        )
    }

    /**
     * `top_k`, which is not in OpenAI's schema.
     *
     * Several self-hosted servers accept it and it is the only way to reach their sampler, but OpenAI
     * itself rejects an unrecognized body parameter outright — so a caller setting `topK` against OpenAI
     * gets a 400 for a knob it never learned was vendor-specific. Whether it goes out is vendor data.
     */
    private fun topK(value: Int?, warnings: MutableList<Warning>): Int? {
        if (value == null) return null
        if (!capabilities.supportsTopK) {
            warnings += Warning.Unsupported("topK", "Chat Completions has no top_k field.")
            return null
        }
        return value
    }

    private data class BuiltRequest(
        val body: JsonObject,
        val headers: Map<String, String>,
        val warnings: List<Warning>,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1000L

        /** The status a mid-stream error actually arrived on: the response itself succeeded. */
        const val HTTP_OK = 200
    }
}

/**
 * Spreads the caller's `providerOptions[provider]` into the request body.
 *
 * One line in the reference, and the single change that makes the vendor table honest: Groq's
 * `reasoning_format`, xAI's `search_parameters`, Mistral's `safe_prompt`, DeepSeek's and Moonshot's
 * `thinking`, Alibaba's `enable_thinking` and OpenRouter's `provider` block all become expressible with
 * no per-vendor class and no new field here.
 *
 * The keys that carry the CALL rather than a setting are protected: a `messages` or `tools` arriving
 * through this door would silently replace the prompt the caller actually passed.
 */
private fun JsonObject.withProviderOptions(extra: JsonObject?): JsonObject {
    if (extra.isNullOrEmpty()) return this
    return JsonObject(this + extra.filterKeys { it !in RESERVED_BODY_KEYS })
}

private val RESERVED_BODY_KEYS = setOf("model", "messages", "stream", "tools", "tool_choice")

/**
 * GPT-6's closed effort list and its retired cache field (the reference's `17e489e`).
 *
 * Applied to the MERGED body so that both a neutral level the engine spelled and a raw
 * `reasoning_effort` the caller filed under the vendor are judged — either one off the list is a 400
 * on the wire, and the caller is told which spellings the model does take.
 */
private fun JsonObject.withOpenAIEffortRules(
    capabilities: OpenAICompatibleCapabilities,
    modelId: String,
    warnings: MutableList<Warning>,
): JsonObject {
    val allowed = capabilities.supportedReasoningEfforts ?: return this
    var body = this
    val effort = (body["reasoning_effort"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (effort != null && effort !in allowed) {
        warnings += Warning.Unsupported(
            "reasoningEffort",
            "$modelId only supports the following reasoning efforts: ${allowed.joinToString(", ")}",
        )
        body = JsonObject(body - "reasoning_effort")
    }
    if (body["prompt_cache_retention"] != null) {
        warnings += Warning.Unsupported(
            "promptCacheRetention",
            "promptCacheRetention is not supported by GPT-6 and later models; use promptCacheOptions instead",
        )
        body = JsonObject(body - "prompt_cache_retention")
    }
    return body
}

/** The reference's wording for a `choices: []` response, shared with the Completions model. */
internal const val NO_CHOICES_MESSAGE: String = "Response did not contain any choices."

internal const val NO_FINISH_MESSAGE: String = "Response stream ended without a finish reason."

/**
 * Merges each message's vendor extras into the encoded `messages` array.
 *
 * Done here, on the encoded body, rather than by widening the wire model: a field per vendor on
 * [OpenAIMessage] is the per-vendor-class shape this file exists to avoid, and the extras are arbitrary
 * JSON the wire model has no business naming. Alignment is by position — [EncodedMessages] guarantees
 * one entry per emitted message, including the several a single tool turn fans out into.
 */
private fun JsonObject.withMessageOptions(extras: List<JsonObject?>): JsonObject {
    if (extras.none { !it.isNullOrEmpty() }) return this
    val messages = (this["messages"] as? JsonArray) ?: return this
    val merged = messages.mapIndexed { index, message ->
        val extra = extras.getOrNull(index)
        if (message !is JsonObject || extra.isNullOrEmpty()) message else JsonObject(message + extra)
    }
    return JsonObject(this + ("messages" to JsonArray(merged)))
}

/**
 * What a given model rejects, and which spelling of the standard fields it takes.
 *
 * Data rather than a subclass. Ported from the reference's `getOpenAILanguageModelCapabilities`, whose
 * family detection is the part worth copying exactly: it parses the version out of the id rather than
 * prefix-matching, so `gpt-5-chat-latest` (not a reasoning model), `o5` (one, when it ships) and a
 * fine-tuned id (conservative defaults) all classify correctly.
 */
public data class OpenAICompatibleCapabilities(
    val isReasoningModel: Boolean = false,
    /**
     * gpt-5.1 and later accept `temperature`, `top_p` and log-probs when the effort is explicitly
     * `none`. Every other reasoning model rejects them at any effort.
     */
    val supportsNonReasoningParameters: Boolean = false,
    /**
     * Whether the server implements `response_format: json_schema`.
     *
     * Off by default because Ollama, llama.cpp and older vLLM reject the shape, and the failure is a
     * 400 on a request that looked well-formed. A vendor that supports it says so.
     */
    val supportsStructuredOutputs: Boolean = false,
    /** Whether `top_k` reaches a sampler here rather than an unrecognized-parameter 400. */
    val supportsTopK: Boolean = false,
    /** Anything else this model rejects, for a host with better information than the table. */
    val unsupportedSamplers: Set<SamplerParam> = emptySet(),
    /**
     * The closed list of `reasoning_effort` values this model accepts, where the vendor enforces one
     * (GPT-6 and later take `low`…`max` and no `none`/`minimal`), or null where any spelling the
     * family knows is accepted. A resolved effort off the list is dropped with a warning, not sent
     * to 400 — and the same models retired `prompt_cache_retention`, which is dropped alongside.
     */
    val supportedReasoningEfforts: Set<String>? = null,
    /**
     * Whether OpenAI's structured-output restrictions apply to every schema sent: `propertyNames` is
     * stripped with a warning, because OpenAI rejects the keyword. Off by default — a self-hosted
     * server that validates it should keep it — and on for the rows OpenAI itself serves.
     */
    val normalizesJsonSchema: Boolean = false,
) {

    /** Reasoning models take `developer` where the rest take `system`. */
    public val systemRole: String get() = if (isReasoningModel) "developer" else "system"
}

/** How a vendor spells `tool_choice`. */
public enum class ToolChoiceDialect {

    /** `auto` / `none` / `required`, and a named tool as `{type:"function",function:{name}}`. */
    OpenAI,

    /**
     * Mistral: `required` is spelled `any`, and there is no named-tool form at all — the way to pin a
     * tool is to send only that tool. Sending OpenAI's spelling here is a 400 on both counts.
     */
    Mistral,
}

/**
 * Which server-side tools a vendor adds to Chat Completions, which by itself has none.
 *
 * Data rather than a branch on the provider id, matching [ToolChoiceDialect] and [UsageDialect]: the
 * shared model stays vendor-agnostic and a new extension is one entry here plus one line in the vendor
 * table. Without it a provider-defined tool is dropped with a warning — correct for a plain
 * OpenAI-compatible server, and wrong for the handful that do serve one.
 */
public enum class ProviderToolDialect {

    /** The default: this server has no server-side tools, so one offered is dropped with a warning. */
    None,

    /**
     * Groq's `browser_search`, a bare `{"type":"browser_search"}` entry in `tools`.
     *
     * Gated on the model: Groq serves it on two ids only, and offering it elsewhere is a 400 rather
     * than a quietly ignored tool.
     */
    Groq,
}

/**
 * One provider-defined tool in this vendor's spelling, or null with a warning saying why not.
 *
 * A warning in every rejecting path is the point: a caller who wired up browser search and saw a
 * perfectly ordinary answer has no way to discover the tool was never sent.
 */
private fun ProviderToolDialect.toWireTool(
    tool: Tool.ProviderDefined,
    modelId: String,
    warnings: MutableList<Warning>,
): OpenAITool? {
    fun unsupported(details: String): OpenAITool? {
        warnings += Warning.Unsupported(feature = "provider-defined tool ${tool.id}", details = details)
        return null
    }
    return when {
        this == ProviderToolDialect.Groq && tool.id == "groq.browser_search" ->
            if (supportsGroqBrowserSearch(modelId)) {
                OpenAITool(type = "browser_search")
            } else {
                unsupported(
                    "Browser search is only supported on the following models: " +
                        "${GROQ_BROWSER_SEARCH_MODELS.joinToString(", ")}. Current model: $modelId",
                )
            }
        else -> unsupported("Chat Completions has no provider-executed tools.")
    }
}

/**
 * How the OpenAI wire counts tokens, and the fallback every vendor starts from.
 *
 * Takes the RAW usage object rather than a decoded type, which is what lets a vendor read a field this
 * one has never heard of: DeepSeek spells its cache count `prompt_cache_hit_tokens`, Moonshot puts
 * `cached_tokens` at the top level, Alibaba adds `cache_creation_input_tokens`, and Mistral ships a
 * misspelled `prompt_token_details`. Forcing all of those into one shared schema would make every
 * vendor's fields every other vendor's problem.
 *
 * @param raw the vendor's `usage` object, exactly as it arrived.
 */
public fun defaultOpenAICompatibleUsage(raw: JsonObject): Usage {
    val prompt = raw.intOrNull("prompt_tokens")
    val cached = (raw["prompt_tokens_details"] as? JsonObject)?.intOrNull("cached_tokens")
    val completion = raw.intOrNull("completion_tokens")
    val reasoning = (raw["completion_tokens_details"] as? JsonObject)?.intOrNull("reasoning_tokens")
        // Perplexity reports its reasoning count here rather than under the details object.
        ?: raw.intOrNull("reasoning_tokens")
    return Usage(
        inputTokens = Usage.InputTokens(
            total = prompt,
            noCache = prompt?.let { it - (cached ?: 0) },
            cacheRead = cached,
        ),
        outputTokens = Usage.OutputTokens(
            total = completion,
            reasoning = reasoning,
            // Clamped at zero: a vendor whose two counters disagree should not produce a negative.
            text = completion?.let { maxOf(0, it - (reasoning ?: 0)) },
        ),
        raw = raw,
    )
}

/** The `usage` object as it arrived, from either the chunk root or Groq's `x_groq` envelope. */
private fun rawUsage(frame: JsonElement): JsonObject? {
    val root = frame as? JsonObject ?: return null
    (root["usage"] as? JsonObject)?.let { return it }
    return ((root["x_groq"] as? JsonObject)?.get("usage")) as? JsonObject
}

/** Reads an integer field, tolerating the string form some servers emit. */
internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toIntOrNull()

/**
 * Tracks which blocks are open and what the open reasoning block will need on close.
 *
 * Blocks are numbered rather than fixed, because a model that reasons, answers, and reasons again
 * produces two reasoning blocks and one shared id would merge them into one out-of-order block.
 */
private class BlockCursor {

    var text: String? = null
        private set
    var reasoning: String? = null
        private set

    private var texts = 0
    private var reasonings = 0
    private var replayText = StringBuilder()
    private var details = mutableListOf<JsonObject>()

    fun startText(): String = "text-${texts++}".also { text = it }

    fun startReasoning(): String = "reasoning-${reasonings++}".also { reasoning = it }

    fun recordReasoningText(delta: String) {
        replayText.append(delta)
    }

    fun recordReasoningDetails(blocks: List<JsonObject>) {
        details += blocks
    }

    /**
     * What this reasoning block has to carry so the next turn can replay it.
     *
     * Both channels, under their own keys: the opaque OpenRouter blocks and the plain
     * `reasoning_content` string. The second was read on replay and written nowhere, which left
     * DeepSeek-R1, Moonshot, Alibaba and xAI reasoning display-only — visible in the UI and gone from
     * the conversation the model actually sees.
     */
    fun reasoningMetadata(provider: String): ProviderMetadata? {
        val text = replayText.toString()
        if (details.isEmpty() && text.isEmpty()) return null
        return mapOf(
            provider to buildJsonObject {
                if (details.isNotEmpty()) {
                    put(
                        REASONING_DETAILS_KEY,
                        // A single block is the common case; several are kept in arrival order.
                        if (details.size == 1) details.single() else buildJsonObject {
                            put("blocks", JsonArray(details))
                        },
                    )
                }
                if (text.isNotEmpty()) put(REASONING_CONTENT_KEY, text)
            },
        )
    }

    fun endReasoning() {
        reasoning = null
        replayText = StringBuilder()
        details = mutableListOf()
    }
}

private data class PreparedTools(
    val tools: List<OpenAITool>?,
    val toolChoice: JsonElement?,
)

/**
 * Maps the neutral tool set and choice onto one vendor's spelling.
 *
 * The two halves are decided together because Mistral pins a tool by FILTERING THE TOOLS ARRAY rather
 * than naming it, so the choice determines what goes in `tools`.
 */
private fun prepareTools(
    options: CallOptions,
    dialect: ToolChoiceDialect,
    providerTools: ProviderToolDialect,
    modelId: String,
    warnings: MutableList<Warning>,
    normalizeSchema: Boolean = false,
): PreparedTools {
    val functions = options.tools.orEmpty().mapNotNull { tool ->
        when (tool) {
            is Tool.Function -> OpenAITool(
                type = "function",
                function = OpenAIFunction(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.inputSchema.forOpenAI(normalizeSchema, warnings),
                    strict = tool.strict,
                ),
            )
            // Chat Completions itself has no provider-executed tools; a vendor that extends it with
            // its own says so through the dialect rather than through a branch on its name here.
            is Tool.ProviderDefined -> providerTools.toWireTool(tool, modelId, warnings)
        }
    }
    if (functions.isEmpty()) {
        // A tool_choice with no tools is a 400 on every vendor.
        return PreparedTools(tools = null, toolChoice = null)
    }

    val choice = options.toolChoice ?: return PreparedTools(functions, null)
    return when (dialect) {
        ToolChoiceDialect.OpenAI -> PreparedTools(functions, choice.toOpenAIToolChoice())
        ToolChoiceDialect.Mistral -> when (choice) {
            ToolChoice.Auto -> PreparedTools(functions, JsonPrimitive("auto"))
            ToolChoice.None -> PreparedTools(functions, JsonPrimitive("none"))
            ToolChoice.Required -> PreparedTools(functions, JsonPrimitive("any"))
            // A vendor's own server-side tool has no function to name, so it is never the one being
            // pinned — and never filtered out either: dropping it here would silently retract a tool
            // the caller offered because an unrelated function was pinned.
            is ToolChoice.Specific -> PreparedTools(
                tools = functions.filter { it.function == null || it.function.name == choice.toolName },
                toolChoice = JsonPrimitive("any"),
            )
        }
    }
}

internal fun ToolChoice.toOpenAIToolChoice(): JsonElement = when (this) {
    ToolChoice.Auto -> JsonPrimitive("auto")
    ToolChoice.None -> JsonPrimitive("none")
    ToolChoice.Required -> JsonPrimitive("required")
    is ToolChoice.Specific -> buildJsonObject {
        put("type", "function")
        putJsonObject("function") { put("name", toolName) }
    }
}

/**
 * `response_format`.
 *
 * A schema only goes out where the server implements `json_schema`. Ollama, llama.cpp and older vLLM
 * reject the shape outright, so the unconditional version turned every structured-output call on a local
 * runtime into a 400 — the caller is downgraded to `json_object` and told, which loses the guarantee
 * rather than the request.
 */
internal fun ResponseFormat.toOpenAIResponseFormat(
    supportsStructuredOutputs: Boolean,
    warnings: MutableList<Warning>,
    normalizeSchema: Boolean = false,
): JsonElement? = when (this) {
    // Omitted rather than sent as `{"type":"text"}`: text is the default everywhere, and the field is
    // one more thing a minimal server can reject.
    ResponseFormat.Text -> null
    is ResponseFormat.Json -> {
        val jsonSchema = schema
        if (jsonSchema == null) {
            buildJsonObject { put("type", "json_object") }
        } else if (!supportsStructuredOutputs) {
            warnings += Warning.Unsupported(
                "responseFormat",
                "JSON response format schema is only supported with structuredOutputs",
            )
            buildJsonObject { put("type", "json_object") }
        } else {
            buildJsonObject {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", name ?: "response")
                    description?.let { put("description", it) }
                    put("schema", jsonSchema.forOpenAI(normalizeSchema, warnings))
                    // Without this the model may return a superset of the schema and call it valid.
                    put("strict", true)
                }
            }
        }
    }
}

/** [normalizeOpenAIJsonSchema] where the row says so; the schema untouched everywhere else. */
private fun JsonObject.forOpenAI(normalize: Boolean, warnings: MutableList<Warning>): JsonObject {
    if (!normalize) return this
    val normalized = normalizeOpenAIJsonSchema(this)
    warnings += normalized.warnings
    return normalized.schema
}

/**
 * `reasoning_effort`.
 *
 * Only an explicit level is sent. Omitting the field is the one value every model accepts: the o-series
 * rejects `"none"`, and most compatible servers reject the field entirely.
 */
internal fun ReasoningEffort.toWireEffort(): String? = when (this) {
    ReasoningEffort.ProviderDefault -> null
    ReasoningEffort.None -> null
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    // OpenAI's vocabulary stops at high; clamping beats a 400.
    ReasoningEffort.High, ReasoningEffort.XHigh -> "high"
}

internal fun String.toFinishReason(): FinishReason = FinishReason(
    unified = when (this) {
        "stop" -> FinishReason.Unified.Stop
        "length" -> FinishReason.Unified.Length
        "tool_calls", "function_call" -> FinishReason.Unified.ToolCalls
        "content_filter" -> FinishReason.Unified.ContentFilter
        else -> FinishReason.Unified.Other
    },
    raw = this,
)

/** The name a warning uses, which is the CALLER's spelling of the knob rather than the wire's. */
private val SamplerParam.wireName: String
    get() = when (this) {
        SamplerParam.Temperature -> "temperature"
        SamplerParam.TopP -> "topP"
        SamplerParam.TopK -> "topK"
        SamplerParam.PresencePenalty -> "presencePenalty"
        SamplerParam.FrequencyPenalty -> "frequencyPenalty"
        SamplerParam.Seed -> "seed"
    }

/** Sampler knobs a given model rejects outright. */
public enum class SamplerParam { Temperature, TopP, TopK, PresencePenalty, FrequencyPenalty, Seed }
