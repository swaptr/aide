package com.sabreware.aide.aisdk.providers.cohere

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ToolCallTracker
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.isExplicit
import com.sabreware.aide.aisdk.util.mapReasoningToBudget
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Cohere v2 Chat.
 *
 * Its own wire rather than an OpenAI-compatible one, and the differences are not cosmetic:
 *
 * - **Two reasoning channels, not one.** `tool_plan` is the reflection before a tool call;
 *   `content[].thinking` is the visible chain on `command-a-reasoning`. The second arrives through the
 *   same `content-start`/`content-delta` events as the answer and is told apart only by its `type`, so
 *   a converter that assumes content is text opens a TEXT block and renders the model's private
 *   deliberation as its reply.
 * - **`tool_choice` accepts only `REQUIRED` or `NONE`.** A named tool is pinned by sending only that
 *   tool, exactly as on Mistral.
 * - **Documents are a top-level field.** See [CoherePrompt].
 * - Sampling is spelled `p` and `k`, not `top_p` and `top_k`.
 */
internal class CohereLanguageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : LanguageModel {

    override val provider: String = COHERE_PROVIDER_ID

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildRequest(options)
        val body = JsonObject(built.body + ("stream" to JsonPrimitive(true)))
        return StreamResult(
            stream = streamParts(body, built.warnings, combineHeaders(headers, options.headers)),
            request = built.requestInfo(body),
        )
    }

    /**
     * A single non-streamed turn.
     *
     * Not [com.sabreware.aide.aisdk.util.assembleGenerateResult] over the stream, which is what every
     * OpenAI-shaped provider here does: Cohere's streamed events carry no citations that any recorded
     * wire pins down, while the JSON response carries the whole `citations[]` block. Folding the stream
     * would therefore throw away the field that is the entire point of `documents[]`.
     */
    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildRequest(options)
        val result = http.postJson(
            url = "$baseUrl/chat",
            body = built.body,
            headers = combineHeaders(headers, options.headers),
        )
        val response = result.value.jsonObject
        val message = response["message"]?.jsonObject

        val content = buildList {
            message?.get("content")?.jsonArray.orEmpty().forEach { entry ->
                val part = entry.jsonObject
                val text = part["text"]?.jsonPrimitive?.content
                val thinking = part["thinking"]?.jsonPrimitive?.content
                when (part["type"]?.jsonPrimitive?.content) {
                    "text" -> text?.takeIf { it.isNotEmpty() }?.let { add(Content.Text(it)) }
                    "thinking" -> thinking?.takeIf { it.isNotEmpty() }?.let { add(Content.Reasoning(it)) }
                }
            }
            // The reflection before a tool call is reasoning too, and a turn that ends in a tool call
            // carries it here and nowhere else.
            message?.get("tool_plan")?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                ?.let { add(Content.Reasoning(it)) }
            addAll(message?.get("citations")?.jsonArray.orEmpty().toSources())
            message?.get("tool_calls")?.jsonArray.orEmpty().forEach { entry ->
                val call = entry.jsonObject
                val function = call["function"]?.jsonObject
                add(
                    Content.ToolCall(
                        toolCallId = call["id"]?.jsonPrimitive?.content.orEmpty(),
                        toolName = function?.get("name")?.jsonPrimitive?.content.orEmpty(),
                        input = function?.get("arguments")?.jsonPrimitive?.content.orEmpty().orEmptyObject(),
                    ),
                )
            }
        }

        val raw = response["finish_reason"]?.jsonPrimitive?.content
        return GenerateResult(
            content = content,
            finishReason = raw?.toFinishReason() ?: FinishReason(FinishReason.Unified.Other),
            usage = response["usage"]?.jsonObject?.toUsage() ?: Usage(),
            warnings = built.warnings,
            request = built.requestInfo(built.body),
            response = result.responseInfo(
                modelId = modelId,
                id = response["id"]?.jsonPrimitive?.content
                    ?: response["generation_id"]?.jsonPrimitive?.content,
            ),
        )
    }

    private fun streamParts(
        body: JsonObject,
        warnings: List<Warning>,
        requestHeaders: Map<String, String>,
    ): Flow<StreamPart> = flow {
        emit(StreamPart.StreamStart(warnings))
        val tools = ToolCallTracker()
        val blocks = CohereBlocks()
        var finish: FinishReason? = null
        var usage = Usage()

        http.postSse("$baseUrl/chat", body, requestHeaders).collect { sse ->
            val event = runCatching { parseJsonObject(sse.data) }.getOrNull() ?: return@collect
            val delta = event["delta"]?.jsonObject
            val message = delta?.get("message")?.jsonObject
            val index = event["index"]?.jsonPrimitive?.intOrNull

            when (event["type"]?.jsonPrimitive?.content) {
                "message-start" -> event["id"]?.jsonPrimitive?.content?.let {
                    emit(StreamPart.ResponseMetadataPart(ResponseMetadata(id = it, modelId = modelId)))
                }

                // Cohere's chain-of-thought before a tool call. It has no index of its own, so it gets
                // its own block name rather than sharing one with the content stream.
                "tool-plan-delta" -> message?.get("tool_plan")?.jsonPrimitive?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { blocks.delta(this, PLAN_BLOCK, reasoning = true, text = it) }

                // The ONLY place the two content kinds are distinguishable: `content-delta` carries no
                // `type`, so a block that opened as thinking has to be remembered by its index.
                "content-start" -> message?.get("content")?.jsonObject
                    ?.get("type")?.jsonPrimitive?.content
                    ?.let { blocks.start(this, index.blockId(), reasoning = it == "thinking") }

                "content-delta" -> message?.get("content")?.jsonObject?.let { part ->
                    val thinking = part["thinking"]?.jsonPrimitive?.content
                    val text = part["text"]?.jsonPrimitive?.content
                    val value = thinking ?: text
                    if (!value.isNullOrEmpty()) {
                        blocks.delta(this, index.blockId(), reasoning = thinking != null, text = value)
                    }
                }

                "content-end" -> blocks.close(this, index.blockId())

                "tool-call-start" -> {
                    // Reasoning ends where the tool call begins; leaving it open would put the answer's
                    // blocks inside it.
                    blocks.closeAll(this)
                    val call = message?.get("tool_calls")?.firstToolCall()
                    tools.accept(
                        collector = this,
                        index = index,
                        id = call?.get("id")?.jsonPrimitive?.content,
                        name = call?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content,
                        argumentsFragment = null,
                    )
                }

                "tool-call-delta" -> tools.accept(
                    collector = this,
                    index = index,
                    id = null,
                    name = null,
                    argumentsFragment = message?.get("tool_calls")?.firstToolCall()
                        ?.get("function")?.jsonObject?.get("arguments")?.jsonPrimitive?.content,
                )

                "message-end" -> {
                    delta?.get("finish_reason")?.jsonPrimitive?.content?.let { finish = it.toFinishReason() }
                    delta?.get("usage")?.jsonObject?.let { usage = it.toUsage() }
                }

                // `citation-start` and `citation-end` are deliberately dropped. Their payload has no
                // recorded wire to conform against — the reference discards them too — and a parser
                // written from prose would be untestable and silently wrong. Citations come back on the
                // JSON response, which doGenerate reads.
                else -> Unit
            }
        }

        blocks.closeAll(this)
        tools.finish(this)
        emit(StreamPart.Finish(usage, finish ?: FinishReason(FinishReason.Unified.Other)))
    }

    private fun buildRequest(options: CallOptions): BuiltRequest {
        val warnings = mutableListOf<Warning>()
        val prompt = options.prompt.toCohere()
        val prepared = prepareTools(options, warnings)

        val body = buildJsonObject {
            put("model", modelId)
            put("messages", prompt.messages)
            options.maxOutputTokens?.let { put("max_tokens", it) }
            options.temperature?.let { put("temperature", it) }
            // Spelled `p` and `k` here, not top_p and top_k.
            options.topP?.let { put("p", it) }
            options.topK?.let { put("k", it) }
            options.seed?.let { put("seed", it) }
            options.presencePenalty?.let { put("presence_penalty", it) }
            options.frequencyPenalty?.let { put("frequency_penalty", it) }
            options.stopSequences?.takeIf { it.isNotEmpty() }?.let { stops ->
                putJsonArray("stop_sequences") { stops.forEach { add(JsonPrimitive(it)) } }
            }
            (options.responseFormat as? ResponseFormat.Json)?.let { format ->
                putJsonObject("response_format") {
                    put("type", "json_object")
                    format.schema?.let { put("json_schema", it) }
                }
            }
            prepared.tools?.let { tools ->
                putJsonArray("tools") { tools.forEach { add(it) } }
                // A tool_choice with no tools is rejected, so it is only sent alongside them.
                prepared.toolChoice?.let { put("tool_choice", it) }
            }
            if (prompt.documents.isNotEmpty()) {
                putJsonArray("documents") { prompt.documents.forEach { add(it) } }
            }
            thinking(options, warnings)?.let { put("thinking", it) }
        }
        return BuiltRequest(body, warnings)
    }

    /**
     * Cohere's thinking switch.
     *
     * The explicit `providerOptions.cohere.thinking` wins, because a caller that named a token budget
     * knows something the effort ladder cannot express. Otherwise the neutral effort maps onto one: a
     * reasoning model with no `thinking` field thinks at its own default, so [ReasoningEffort.None] has
     * to be transmitted as `disabled` rather than as an omission.
     */
    private fun thinking(options: CallOptions, warnings: MutableList<Warning>): JsonObject? {
        val explicit = options.providerOptions?.get(COHERE_PROVIDER_ID)?.get("thinking") as? JsonObject
        if (explicit != null) {
            return buildJsonObject {
                put("type", explicit["type"]?.jsonPrimitive?.content ?: "enabled")
                (explicit["tokenBudget"] as? JsonPrimitive)?.intOrNull?.let { put("token_budget", it) }
            }
        }
        if (!options.reasoning.isExplicit) return null
        if (options.reasoning == ReasoningEffort.None) {
            return buildJsonObject { put("type", "disabled") }
        }
        val budget = mapReasoningToBudget(
            reasoning = options.reasoning,
            // Cohere's own ceiling, and the reference's: the budget is stated against the thinking
            // allowance rather than against `max_tokens`, which on this API does not bound it.
            maxOutputTokens = MAX_THINKING_TOKENS,
            maxReasoningBudget = MAX_THINKING_TOKENS,
            warnings = warnings,
        ) ?: return null
        return buildJsonObject {
            put("type", "enabled")
            put("token_budget", budget)
        }
    }

    /**
     * The tools and the choice, which are one decision on this wire.
     *
     * There is no named-tool form: `tool_choice` takes `REQUIRED` or `NONE` and nothing else. Sending
     * every tool with `REQUIRED` — which is what "the closest available value" produces — lets the model
     * call any of them, so `ToolChoice.Specific("x")` cheerfully ran `y`. Pinning is expressed by
     * sending only the named tool, which is exact rather than approximate, so there is nothing to warn
     * about.
     */
    private fun prepareTools(options: CallOptions, warnings: MutableList<Warning>): PreparedTools {
        val declared = options.tools.orEmpty()
        declared.filterIsInstance<Tool.ProviderDefined>().forEach {
            warnings += Warning.Unsupported(
                feature = "provider-defined tool ${it.id}",
                details = "Cohere runs no server-side tools; it was dropped.",
            )
        }
        val functions = declared.filterIsInstance<Tool.Function>()
        if (functions.isEmpty()) return PreparedTools(null, null)

        val choice = options.toolChoice
        val kept = if (choice is ToolChoice.Specific) {
            functions.filter { it.name == choice.toolName }
        } else {
            functions
        }
        val wire = kept.map { tool ->
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    tool.description?.let { put("description", it) }
                    put("parameters", tool.inputSchema)
                }
            }
        }
        val toolChoice = when (choice) {
            null, ToolChoice.Auto -> null // Cohere's default; there is no explicit "auto".
            ToolChoice.None -> "NONE"
            ToolChoice.Required, is ToolChoice.Specific -> "REQUIRED"
        }
        return PreparedTools(wire, toolChoice)
    }

    private data class PreparedTools(val tools: List<JsonObject>?, val toolChoice: String?)

    private class BuiltRequest(val body: JsonObject, val warnings: List<Warning>) {
        fun requestInfo(sent: JsonObject) =
            RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), sent))
    }

    private companion object {
        const val PLAN_BLOCK = "tool_plan"
        // Cohere documents no ceiling of its own; this is the reference's number.
        const val MAX_THINKING_TOKENS = 32_768
    }
}

/**
 * Which content block each index opened as, so a `content-delta` lands in the right one.
 *
 * The state has to exist: `content-delta` carries no `type` and the answer's index is not fixed, so
 * without it a thinking block and a text block are indistinguishable mid-stream. Reading `text` off the
 * thinking events is what published the model's private deliberation as its answer.
 */
private class CohereBlocks {

    /** Block id to whether it opened as reasoning. */
    private val open = mutableMapOf<String, Boolean>()

    suspend fun start(collector: FlowCollector<StreamPart>, id: String, reasoning: Boolean) {
        if (id in open) return
        open[id] = reasoning
        collector.emit(if (reasoning) StreamPart.ReasoningStart(id) else StreamPart.TextStart(id))
    }

    /**
     * A delta into [id], opening the block if the server sent no `content-start` for it.
     *
     * The kind comes from the block, not from this delta: `content-delta` carries no `type`, and a
     * thinking chunk that happens to arrive with an empty `thinking` and a populated `text` would
     * otherwise re-open the same index as an answer.
     */
    suspend fun delta(
        collector: FlowCollector<StreamPart>,
        id: String,
        reasoning: Boolean,
        text: String,
    ) {
        start(collector, id, reasoning)
        collector.emit(
            if (open[id] == true) StreamPart.ReasoningDelta(id, text) else StreamPart.TextDelta(id, text),
        )
    }

    suspend fun close(collector: FlowCollector<StreamPart>, id: String) {
        val reasoning = open.remove(id) ?: return
        collector.emit(if (reasoning) StreamPart.ReasoningEnd(id) else StreamPart.TextEnd(id))
    }

    suspend fun closeAll(collector: FlowCollector<StreamPart>) {
        open.keys.toList().forEach { close(collector, it) }
    }
}

/** The block name for a content index. A missing index is one unnamed stream, not a new block per event. */
private fun Int?.blockId(): String = this?.toString() ?: "0"

/** `tool_calls` is an array on start and an object on delta; both shapes appear on the same wire. */
private fun JsonElement.firstToolCall(): JsonObject? = when (this) {
    is JsonArray -> firstOrNull()?.jsonObject
    is JsonObject -> this
    else -> null
}

/**
 * Cohere returns the string `"null"` as the arguments of a tool it defined as taking none.
 *
 * Replayed as-is it is not an object, so every consumer that parses tool input gets a null where it
 * expects a record. The reference carries the same substitution, against the same recorded fixture.
 */
private fun String.orEmptyObject(): String = if (isEmpty() || this == "null") "{}" else this

/**
 * Citations, as document sources.
 *
 * The span and the quoted text ride in provider metadata rather than being dropped to fit the neutral
 * shape: a citation whose offsets are gone cannot be rendered as a footnote against the answer, which is
 * the only thing a caller wanted it for.
 */
private fun List<JsonElement>.toSources(): List<Content.Source> {
    val ids = IdGenerator("cite_")
    return map { entry ->
        val citation = entry.jsonObject
        val document = citation["sources"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("document")?.jsonObject
        Content.Source.Document(
            id = ids.next(),
            mediaType = "text/plain",
            title = document?.get("title")?.jsonPrimitive?.content ?: "Document",
            providerMetadata = mapOf(COHERE_PROVIDER_ID to citation),
        )
    }
}

internal fun JsonObject.toUsage(): Usage {
    val tokens = this["tokens"]?.jsonObject ?: this
    return Usage(
        inputTokens = Usage.InputTokens(
            total = tokens["input_tokens"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt(),
        ),
        outputTokens = Usage.OutputTokens(
            total = tokens["output_tokens"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt(),
        ),
    )
}

/**
 * Cohere's finish reasons.
 *
 * `STOP_SEQUENCE` is a normal stop — the model produced one of the caller's own stop strings. Mapped to
 * `Other` it looks like a failure to every loop that switches on the unified reason, so a turn that
 * ended exactly as asked reads as one that ended for an unknown cause.
 */
internal fun String.toFinishReason(): FinishReason = FinishReason(
    unified = when (this) {
        "COMPLETE", "STOP_SEQUENCE" -> FinishReason.Unified.Stop
        "MAX_TOKENS" -> FinishReason.Unified.Length
        "TOOL_CALL" -> FinishReason.Unified.ToolCalls
        "ERROR", "ERROR_TOXIC", "ERROR_LIMIT" -> FinishReason.Unified.Error
        else -> FinishReason.Unified.Other
    },
    raw = this,
)
