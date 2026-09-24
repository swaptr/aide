package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ToolNameMapping
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The provider id, and the namespace Gemini payloads are filed under in `providerMetadata`. */
public const val GOOGLE_PROVIDER_ID: String = "google"

/**
 * Gemini's encrypted continuity token.
 *
 * Google's own words: *"You MUST always resend all thought blocks exactly as they were received."* It is
 * the direct analogue of Anthropic's `signature`, and losing it degrades multi-turn reasoning silently
 * rather than loudly — which is worse.
 */
public const val GOOGLE_THOUGHT_SIGNATURE_KEY: String = "thoughtSignature"

/**
 * Gemini `generateContent`, streaming.
 *
 * The signature handling is the reason this exists rather than routing Gemini through the
 * OpenAI-compatible path: the compat surface drops `thought_signature` outright, which is a documented
 * upstream bug in every client that does it and produces
 * `Function call is missing a thought_signature` on the second tool round.
 *
 * **A signature belongs to the PART it arrived on, and to nothing else.** A signature may ride a thought
 * part, a text part, an `inlineData` part or a `functionCall` part depending on the model and the
 * surface, so it is captured per part and emitted on the block that carried it. This used to hoist any
 * signature it saw into one stream-scoped variable and stamp that on the reasoning block — so round two
 * sent a thought part carrying a signature Gemini issued for a function call, and the function call with
 * none. That fails with the signature visibly present, which is far harder to diagnose than failing with
 * it absent, and two related cases fell out of the same design: a turn with no thought summary text
 * closed no reasoning block and lost the signature entirely, and a turn with several signed parts kept
 * only the last.
 *
 * Blocks are numbered rather than named for the same reason. One `"reasoning"` id could hold only one
 * reasoning block per turn, which is not what Gemini emits when it thinks, answers, then thinks again.
 */
internal class GoogleLanguageModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    /**
     * Resolved per call, not per model.
     *
     * Vertex authenticates with an OAuth2 bearer that expires in an hour; a model holding a snapshot
     * works until it rotates and then fails as a 401 that reads like a bad key.
     */
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
    /** Vertex names the method `streamGenerateContent` too, but reaches it by a different path shape. */
    private val streamPath: (modelId: String) -> String = { "models/$it:streamGenerateContent?alt=sse" },
    /** Vertex rejects two fields the Gemini API requires, so the host has to be known here. */
    private val isVertex: Boolean = false,
    /**
     * Whether a tool result's URL files are fetched and inlined before the request goes out.
     *
     * Null on the Gemini API, which never asked for it; Vertex sets it because its function responses
     * take inline data and nothing else — see [ToolResultDownloads].
     */
    private val toolResultDownloads: ToolResultDownloads? = null,
) : LanguageModel {

    override val provider: String = GOOGLE_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    private val ids = IdGenerator(prefix = "gemini_")

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildRequest(options)
        return StreamResult(
            stream = streamParts(built, options),
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
        )
    }

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildRequest(options)
        var meta: HttpResult<Unit>? = null
        val result = assembleGenerateResult(streamParts(built, options) { meta = it })
        return result.copy(
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
            response = meta?.responseInfo(modelId = modelId, id = result.response?.metadata?.id)
                ?: result.response,
        )
    }

    private fun streamParts(
        built: BuiltRequest,
        options: CallOptions,
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<StreamPart> = flow {
        emit(StreamPart.StreamStart(built.warnings))
        val url = "$baseUrl/${streamPath(modelId)}"
        val state = GoogleStreamState(ids, built.toolNameMapping)

        http.postSse(url, built.body, combineHeaders(headers(), options.headers), onResponse)
            .collect { sse ->
                val raw = parseJsonElementOrNull(sse.data)
                if (options.includeRawChunks && raw != null) emit(StreamPart.Raw(raw))

                // An error frame decodes SUCCESSFULLY: every field of the chunk is optional and
                // `ignoreUnknownKeys` is on, so `{"error":{...}}` becomes an empty chunk and the
                // generation ends looking short but fine. Gemini reports a mid-stream rate limit exactly
                // this way, which is why nothing ever retried one.
                if (raw is JsonObject && "error" in raw) {
                    emit(StreamPart.Error(streamError(url, raw)))
                    return@collect
                }
                val chunk = runCatching {
                    ProviderJson.decodeFromString(GoogleResponseChunk.serializer(), sse.data)
                }.getOrElse { failure ->
                    emit(StreamPart.Error(failure))
                    return@collect
                }

                emitAllFor(chunk, state)
            }

        finish(state)
    }

    /**
     * A COMPLETE response — one the batch API stored — mapped through the same part mapper as the stream.
     *
     * The house rule applied to the one Gemini surface that does not stream: a stored result is treated
     * as a stream of exactly one chunk, so a signature, a generated image or a code-execution result
     * decodes identically whether it arrived live or a day later. There is no second decode path to
     * drift from the first.
     */
    internal suspend fun convertResponse(
        chunk: GoogleResponseChunk,
        warnings: List<Warning>,
        toolNameMapping: ToolNameMapping = ToolNameMapping.Identity,
    ): GenerateResult =
        assembleGenerateResult(
            flow {
                emit(StreamPart.StreamStart(warnings))
                val state = GoogleStreamState(ids, toolNameMapping)
                emitAllFor(chunk, state)
                finish(state)
            },
        )

    /** Closes whatever block is open and ends the stream — the one tail both entry points share. */
    private suspend fun FlowCollector<StreamPart>.finish(state: GoogleStreamState) {
        state.closeText(this)
        state.closeReasoning(this)
        emit(
            StreamPart.Finish(
                usage = state.usage,
                // A confirmed prompt block is a content filter whatever the reason is called; the
                // candidate's finish-reason table never sees it.
                finishReason = state.confirmedBlockReason
                    ?.let { FinishReason(FinishReason.Unified.ContentFilter, raw = it) }
                    ?: googleFinishReason(state.finishReason, state.hasToolCalls),
                providerMetadata = state.finishMetadata(),
            ),
        )
    }

    private suspend fun FlowCollector<StreamPart>.emitAllFor(
        chunk: GoogleResponseChunk,
        state: GoogleStreamState,
    ) {
        if (!state.sentMetadata && (chunk.responseId != null || chunk.modelVersion != null)) {
            state.sentMetadata = true
            emit(
                StreamPart.ResponseMetadataPart(
                    ResponseMetadata(id = chunk.responseId, modelId = chunk.modelVersion),
                ),
            )
        }

        chunk.usageMetadata?.let {
            state.usage = it.toUsage()
            state.usageMetadata = it
        }

        // The prompt feedback is frozen once a block is confirmed. Gemini has been seen to follow a
        // `SAFETY` frame with a `BLOCK_REASON_UNSPECIFIED` one, and letting the second overwrite the first
        // reported a blocked prompt as unblocked — with the model's text streamed after the block.
        chunk.promptFeedback?.takeIf { state.confirmedBlockReason == null }?.let { feedback ->
            state.promptFeedback = feedback
            feedback.blockReason?.takeIf(::isConfirmedPromptBlockReason)?.let { state.confirmedBlockReason = it }
        }

        val candidate = chunk.candidates.firstOrNull() ?: return
        // Gemini repeats the same grounding object on every chunk; decoding it again would only find
        // sources the dedupe already knows.
        val newGrounding = candidate.groundingMetadata?.takeIf { it != state.groundingMetadata }
        newGrounding?.let { state.groundingMetadata = it }
        candidate.urlContextMetadata?.let { state.urlContextMetadata = it }
        candidate.safetyRatings?.let { state.safetyRatings = it }
        candidate.finishMessage?.let { state.finishMessage = it }

        // A confirmed prompt block is terminal for generated content, but a later chunk still carries
        // usage and the metadata above — which is why those are read before this return, not after.
        if (state.confirmedBlockReason != null) return

        newGrounding?.let { candidate.groundingChunks().toSources(state).forEach { emit(StreamPart.SourcePart(it)) } }
        candidate.content?.parts?.forEach { part -> emitPart(part, state) }
        candidate.finishReason?.let { state.finishReason = it }
    }

    /**
     * One content part, mapped where it stands.
     *
     * Order is the contract. Gemini interleaves thinking, answers, generated images and calls within a
     * single turn, and the assistant message has to be replayed in the order it was produced — so this
     * closes an open block before opening the next kind rather than accumulating everything and sorting.
     */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun FlowCollector<StreamPart>.emitPart(
        part: GooglePart,
        state: GoogleStreamState,
    ) {
        val metadata = part.thoughtSignature?.takeIf { it.isNotEmpty() }?.let { signatureMetadata(it) }

        when {
            part.executableCode != null -> {
                // Code execution is a tool GOOGLE runs; the call and its result are reported so a caller
                // can show what ran, and never handed to a runtime that would try to execute it again.
                val callId = ids.next()
                state.lastCodeExecutionCallId = callId
                state.hasToolCalls = true
                emit(
                    StreamPart.ToolCallPart(
                        Content.ToolCall(
                            toolCallId = callId,
                            toolName = state.codeExecutionToolName,
                            input = ProviderJson.encodeToString(
                                GoogleExecutableCode.serializer(),
                                part.executableCode,
                            ),
                            providerExecuted = true,
                            providerMetadata = metadata,
                        ),
                    ),
                )
            }

            part.codeExecutionResult != null -> {
                // The result belongs to the most recent executable-code part; Gemini correlates them by
                // adjacency and sends no id of its own.
                state.lastCodeExecutionCallId?.let { callId ->
                    emit(
                        StreamPart.ToolResultPart(
                            Content.ToolResult(
                                toolCallId = callId,
                                toolName = state.codeExecutionToolName,
                                output = ToolOutput.Json(
                                    ProviderJson.encodeToJsonElement(
                                        GoogleCodeExecutionResult.serializer(),
                                        part.codeExecutionResult,
                                    ),
                                ),
                                providerMetadata = metadata,
                            ),
                        ),
                    )
                }
            }

            part.functionCall != null -> {
                val call = part.functionCall
                // Gemini sends structured args and no id; the spec's transport is a JSON string, and a
                // tool result has to reference something, so an id is minted when absent.
                val callId = call.id ?: ids.next()
                val args = call.args?.toString() ?: "{}"
                state.hasToolCalls = true
                emit(StreamPart.ToolInputStart(callId, call.name, providerMetadata = metadata))
                emit(StreamPart.ToolInputDelta(callId, args))
                emit(StreamPart.ToolInputEnd(callId))
                emit(
                    StreamPart.ToolCallPart(
                        Content.ToolCall(
                            toolCallId = callId,
                            toolName = call.name,
                            input = args,
                            // On the call itself, which is where Gemini expects it back.
                            providerMetadata = metadata,
                        ),
                    ),
                )
            }

            part.inlineData != null -> {
                state.closeText(this)
                state.closeReasoning(this)
                val file = part.inlineData
                // Decoded here rather than carried as base64: `FileData.Bytes` is the neutral currency,
                // and a consumer that has to know which provider encoded its bytes is not a neutral one.
                val data = FileData.Bytes(Base64.decode(file.data))
                emit(
                    if (part.thought == true) {
                        StreamPart.ReasoningFilePart(Content.ReasoningFile(file.mimeType, data, metadata))
                    } else {
                        StreamPart.FilePart(Content.File(file.mimeType, data, metadata))
                    },
                )
            }

            part.text != null && part.text.isEmpty() ->
                // A text part with no text and a signature is a real wire shape: Gemini signs the block
                // in a frame of its own. The signature belongs to whichever block is open.
                state.rememberSignature(part.thoughtSignature)

            part.thought == true && part.text != null -> {
                state.closeText(this)
                val id = state.openReasoning(this, metadata)
                state.rememberSignature(part.thoughtSignature)
                emit(StreamPart.ReasoningDelta(id, part.text, providerMetadata = metadata))
            }

            part.text != null -> {
                state.closeReasoning(this)
                val id = state.openText(this, metadata)
                state.rememberSignature(part.thoughtSignature)
                emit(StreamPart.TextDelta(id, part.text, providerMetadata = metadata))
            }
        }
    }

    /**
     * The request a live call would send — shared with the batch model, which submits it unchanged.
     *
     * Suspends for one reason: a Vertex host downloads the URL files in tool results first, and that is
     * part of building the request, not of sending it.
     */
    internal suspend fun buildRequest(options: CallOptions): BuiltRequest {
        val warnings = mutableListOf<Warning>()
        val googleOptions = GoogleOptions.of(options)
        val capabilities = googleModelCapabilities(modelId)
        val prompt = toolResultDownloads
            ?.let { options.prompt.downloadToolResultFiles(http, it.maxBytes) }
            ?: options.prompt
        val (system, contents) = prompt.toGoogleContents(
            supportsFunctionResponseParts = capabilities.usesGemini3Features,
        )
        val prepared = prepareGoogleTools(options.tools, options.toolChoice, modelId, isVertex)
        warnings += prepared.warnings

        val json = options.responseFormat as? ResponseFormat.Json
        // Google does not support every JSON Schema feature in `responseJsonSchema`, so
        // `structuredOutputs: false` stays the escape hatch: the mime type goes, the schema does not.
        val responseJsonSchema = json?.schema
            ?.takeIf { googleOptions.structuredOutputs }
            ?.let { sanitizeResponseJsonSchema(it) }

        val request = GoogleRequest(
            contents = contents,
            systemInstruction = system,
            tools = prepared.tools,
            toolConfig = prepared.toolConfig?.let { config ->
                googleOptions.retrievalConfig?.let { config.copy(retrievalConfig = it) } ?: config
            } ?: googleOptions.retrievalConfig?.let { GoogleToolConfig(retrievalConfig = it) },
            generationConfig = GoogleGenerationConfig(
                temperature = options.temperature,
                topP = options.topP,
                topK = options.topK,
                maxOutputTokens = options.maxOutputTokens,
                // Gemini takes both penalties. They were declared, never read, and never warned about —
                // so a caller tuning repetition was tuning nothing.
                frequencyPenalty = options.frequencyPenalty,
                presencePenalty = options.presencePenalty,
                stopSequences = options.stopSequences?.takeIf { it.isNotEmpty() },
                responseMimeType = json?.let { "application/json" },
                responseJsonSchema = responseJsonSchema,
                responseModalities = googleOptions.responseModalities,
                thinkingConfig = googleOptions.thinkingConfigOrNull() ?: options.reasoning.toThinkingConfig(),
                mediaResolution = googleOptions.mediaResolution,
                imageConfig = googleOptions.imageConfig,
                audioTimestamp = googleOptions.audioTimestamp,
                seed = options.seed,
            ),
            safetySettings = googleOptions.safetySettings,
            cachedContent = googleOptions.cachedContent,
            labels = googleOptions.labels,
            serviceTier = googleOptions.serviceTier,
        )

        val body = ProviderJson.encodeToJsonElement(GoogleRequest.serializer(), request) as JsonObject
        return BuiltRequest(body, warnings, ToolNameMapping.from(options.tools, CODE_EXECUTION_TOOL_NAMES))
    }

    /**
     * [toolNameMapping] carries the caller's name for the code-execution tool back onto the call and
     * result Gemini reports under its own — the one built-in whose parts name a tool at all.
     */
    internal data class BuiltRequest(
        val body: JsonObject,
        val warnings: List<Warning>,
        val toolNameMapping: ToolNameMapping = ToolNameMapping.Identity,
    )

    public companion object {
        private val CODE_EXECUTION_TOOL_NAMES: Map<String, String> =
            mapOf(GoogleTools.codeExecution.id to GoogleTools.codeExecution.wireName)
    }
}

/**
 * Whether a `blockReason` says the prompt was blocked.
 *
 * `BLOCK_REASON_UNSPECIFIED` is the enum's zero value — the wire's way of saying nothing — and Vertex
 * spells it `BLOCKED_REASON_UNSPECIFIED`; the reference treats both, and an empty string, as no block at
 * all. Reading them as one turned every unblocked prompt that carried a feedback frame into a
 * content-filter finish.
 */
private fun isConfirmedPromptBlockReason(reason: String): Boolean =
    reason.isNotEmpty() && reason != "BLOCK_REASON_UNSPECIFIED" && reason != "BLOCKED_REASON_UNSPECIFIED"

/**
 * The open blocks of one stream, and the signature each is holding.
 *
 * The signature is per BLOCK, not per stream. That distinction is the whole of P0.2: one variable shared
 * by every block is what let a function call's signature be replayed on a thought part.
 */
private class GoogleStreamState(
    private val ids: IdGenerator,
    private val toolNameMapping: ToolNameMapping,
) {

    var usage: Usage = Usage()
    var finishReason: String? = null
    /** The prompt block Gemini confirmed, which ends generated content and names the finish. */
    var confirmedBlockReason: String? = null
    var hasToolCalls: Boolean = false
    var sentMetadata: Boolean = false
    var lastCodeExecutionCallId: String? = null

    // The response-level payloads that have no neutral part type, kept for the finish metadata. Each is
    // the LAST value seen: grounding metadata repeats on every chunk, and the last is the complete one.
    var usageMetadata: GoogleUsage? = null
    var promptFeedback: GooglePromptFeedback? = null
    var groundingMetadata: JsonObject? = null
    var urlContextMetadata: JsonObject? = null
    var safetyRatings: List<JsonObject>? = null
    var finishMessage: String? = null

    /** What the caller called the code-execution tool, or Gemini's own name when it declared none. */
    val codeExecutionToolName: String = toolNameMapping.toCustomToolName(GoogleTools.codeExecution.wireName)

    /**
     * The `google` block of the finish part's `providerMetadata`, or null when the response carried none
     * of it — a bare finish frame stays a bare finish, so nothing downstream sees an empty namespace.
     *
     * The same seven keys the reference files, so a consumer written against it reads ours unchanged:
     * the prompt feedback, the raw grounding and URL-context metadata, the safety ratings, the usage
     * metadata, the finish message and the service tier that billed the call.
     */
    fun finishMetadata(): ProviderMetadata? {
        val block = buildJsonObject {
            promptFeedback?.let {
                put("promptFeedback", ProviderJson.encodeToJsonElement(GooglePromptFeedback.serializer(), it))
            }
            groundingMetadata?.let { put("groundingMetadata", it) }
            urlContextMetadata?.let { put("urlContextMetadata", it) }
            safetyRatings?.let { put("safetyRatings", JsonArray(it)) }
            usageMetadata?.let { put("usageMetadata", ProviderJson.encodeToJsonElement(GoogleUsage.serializer(), it)) }
            finishMessage?.let { put("finishMessage", it) }
            usageMetadata?.serviceTier?.let { put("serviceTier", it) }
        }
        return if (block.isEmpty()) null else mapOf(GOOGLE_PROVIDER_ID to block)
    }

    private val seenSourceUrls = mutableSetOf<String>()

    /** Grounding metadata repeats on every chunk of the stream; a citation is emitted once. */
    fun emitSource(url: String): Boolean = seenSourceUrls.add(url)

    fun nextSourceId(): String = ids.next()

    private var textId: String? = null
    private var textSignature: String? = null
    private var reasoningId: String? = null
    private var reasoningSignature: String? = null

    /** Remembers a signature against whichever block is currently open. */
    fun rememberSignature(signature: String?) {
        val value = signature?.takeIf { it.isNotEmpty() } ?: return
        when {
            reasoningId != null -> reasoningSignature = value
            textId != null -> textSignature = value
        }
    }

    suspend fun openText(
        collector: FlowCollector<StreamPart>,
        metadata: ProviderMetadata?,
    ): String = textId ?: ids.next().also {
        textId = it
        collector.emit(StreamPart.TextStart(it, providerMetadata = metadata))
    }

    suspend fun openReasoning(
        collector: FlowCollector<StreamPart>,
        metadata: ProviderMetadata?,
    ): String = reasoningId ?: ids.next().also {
        reasoningId = it
        collector.emit(StreamPart.ReasoningStart(it, providerMetadata = metadata))
    }

    // The signature rides the End part because that is where `assembleGenerateResult` reads block
    // metadata from — and because Google emits it as the last delta before the block completes.
    suspend fun closeText(collector: FlowCollector<StreamPart>) {
        val id = textId ?: return
        collector.emit(StreamPart.TextEnd(id, providerMetadata = textSignature?.let { signatureMetadata(it) }))
        textId = null
        textSignature = null
    }

    suspend fun closeReasoning(collector: FlowCollector<StreamPart>) {
        val id = reasoningId ?: return
        collector.emit(
            StreamPart.ReasoningEnd(id, providerMetadata = reasoningSignature?.let { signatureMetadata(it) }),
        )
        reasoningId = null
        reasoningSignature = null
    }
}

private fun signatureMetadata(signature: String): ProviderMetadata = mapOf(
    GOOGLE_PROVIDER_ID to buildJsonObject { put(GOOGLE_THOUGHT_SIGNATURE_KEY, signature) },
)

/**
 * Gemini's error envelope: `{"error":{"code":…,"message":…,"status":…}}`.
 *
 * `RESOURCE_EXHAUSTED` is Google's quota status and arrives as a 429 that the default already retries;
 * what it cannot know is that Gemini also returns `UNAVAILABLE` with a 200 mid-stream, which is why the
 * status is read from the body rather than from the code alone.
 */
internal val GoogleErrorStructure: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        ((body as? JsonObject)?.get("error") as? JsonObject)?.stringOrNull("message")
    },
    isRetryable = { _, body ->
        when (((body as? JsonObject)?.get("error") as? JsonObject)?.stringOrNull("status")) {
            "UNAVAILABLE", "RESOURCE_EXHAUSTED", "INTERNAL" -> true
            else -> null
        }
    },
)

/**
 * A mid-stream error frame, as the error it actually is.
 *
 * Gemini returns HTTP 200 and reports the failure in the body, so no status-code-based retry policy can
 * see it. Lifting the vendor's own status onto the error is what lets one fire.
 */
private fun streamError(url: String, body: JsonObject): APICallError {
    val error = body["error"] as? JsonObject
    val status = error?.stringOrNull("status")
    return APICallError(
        message = error?.stringOrNull("message") ?: "Gemini reported an error mid-stream",
        url = url,
        statusCode = runCatching { (error?.get("code") as? JsonPrimitive)?.content?.toInt() }.getOrNull(),
        responseBody = body.toString(),
        isRetryable = status in RETRYABLE_STATUSES,
        data = body,
    )
}

private val RETRYABLE_STATUSES = setOf("UNAVAILABLE", "RESOURCE_EXHAUSTED", "INTERNAL")

/**
 * Thinking configuration.
 *
 * `includeThoughts` is always set when thinking is on: Gemini returns NO thought summaries by default, so
 * omitting it yields signatures with no readable reasoning — the trace UI silently shows nothing.
 *
 * Depth rides `thinkingLevel`. [ReasoningEffort.None] maps to a zero budget, which is how the classic
 * surface expresses "do not think"; current models may still think, and that is the vendor's call.
 *
 * **Exactly one depth field is ever set, and that is a requirement rather than a preference:** Gemini
 * documents `thinkingLevel` and the legacy `thinkingBudget` as mutually exclusive, so a config carrying
 * both is refused. Every arm below picks one.
 *
 * Verified against `ai.google.dev/gemini-api/docs/generate-content/thinking` (2026-09-01): on
 * `generateContent` the fields are camelCase `thinkingConfig.includeThoughts` / `thinkingBudget` /
 * `thinkingLevel`. The snake_case `thinking_summaries` / `thinking_level` spellings that appear in
 * Google's newer thinking guide belong to the **Interactions API**, a separate surface this port
 * deliberately does not implement — reading them as a rename of these would break every call.
 */
internal fun ReasoningEffort.toThinkingConfig(): GoogleThinkingConfig? = when (this) {
    ReasoningEffort.ProviderDefault -> null
    ReasoningEffort.None -> GoogleThinkingConfig(includeThoughts = false, thinkingBudget = 0)
    ReasoningEffort.Minimal -> GoogleThinkingConfig(includeThoughts = true, thinkingLevel = "minimal")
    ReasoningEffort.Low -> GoogleThinkingConfig(includeThoughts = true, thinkingLevel = "low")
    ReasoningEffort.Medium -> GoogleThinkingConfig(includeThoughts = true, thinkingLevel = "medium")
    ReasoningEffort.High, ReasoningEffort.XHigh ->
        GoogleThinkingConfig(includeThoughts = true, thinkingLevel = "high")
}

/**
 * Gemini's token counts, without the subtraction that made them wrong.
 *
 * `candidatesTokenCount` ALREADY excludes `thoughtsTokenCount`. Deriving the text share by subtracting
 * one from the other therefore double-subtracted: on a turn that thought more than it answered the text
 * count went NEGATIVE, and the total under-reported by the whole thinking bill. The reference adds them
 * for the total and takes the candidate count as the text share unchanged.
 */
internal fun GoogleUsage.toUsage(): Usage {
    val prompt = promptTokenCount ?: 0
    val candidates = candidatesTokenCount ?: 0
    val cached = cachedContentTokenCount ?: 0
    val thoughts = thoughtsTokenCount ?: 0
    return Usage(
        inputTokens = Usage.InputTokens(total = prompt, noCache = prompt - cached, cacheRead = cached),
        outputTokens = Usage.OutputTokens(
            total = candidates + thoughts,
            text = candidates,
            reasoning = thoughts,
        ),
        raw = ProviderJson.encodeToJsonElement(GoogleUsage.serializer(), this) as? JsonObject,
    )
}

/**
 * Gemini's finish reason, with the case that breaks every agent loop.
 *
 * Gemini answers `STOP` for a turn that IS a tool call — there is no `tool-calls` value on the wire — so
 * a runtime keyed on the unified reason stopped the loop before executing the tool the model asked for.
 * `IMAGE_SAFETY` was unmapped and fell through to `other`, which reads as a normal completion.
 */
internal fun googleFinishReason(raw: String?, hasToolCalls: Boolean): FinishReason = FinishReason(
    unified = when (raw) {
        "STOP" -> if (hasToolCalls) FinishReason.Unified.ToolCalls else FinishReason.Unified.Stop
        "MAX_TOKENS" -> FinishReason.Unified.Length
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" ->
            FinishReason.Unified.ContentFilter
        "MALFORMED_FUNCTION_CALL" -> FinishReason.Unified.Error
        else -> FinishReason.Unified.Other
    },
    raw = raw,
)

/**
 * What the model grounded its answer on, as sources.
 *
 * Only the grounding chunks become content. A web or image chunk is a URL source — Google requires
 * attribution to the PAGE an image was found on rather than to the image file — and a retrieved-context
 * chunk is a document unless its URI is an http(s) one, which is the old Google Search shape.
 *
 * Deduplicated by URL: grounding metadata is re-sent on every chunk of the stream, so emitting each
 * occurrence would give a caller the same citation a dozen times.
 */
private fun List<GoogleGroundingChunk>.toSources(state: GoogleStreamState): List<Content.Source> =
    mapNotNull { chunk ->
        when {
            chunk.web != null -> urlSource(chunk.web.uri, chunk.web.title, state)
            chunk.image != null -> urlSource(chunk.image.sourceUri, chunk.image.title, state)
            chunk.maps != null -> chunk.maps.uri?.let { urlSource(it, chunk.maps.title, state) }
            chunk.retrievedContext != null -> {
                val uri = chunk.retrievedContext.uri
                when {
                    uri == null -> null
                    uri.startsWith("http://") || uri.startsWith("https://") ->
                        urlSource(uri, chunk.retrievedContext.title, state)
                    !state.emitSource(uri) -> null
                    else -> Content.Source.Document(
                        id = state.nextSourceId(),
                        mediaType = documentMediaType(uri),
                        title = chunk.retrievedContext.title ?: "Unknown Document",
                        filename = uri.substringAfterLast('/').takeIf { it.isNotEmpty() },
                    )
                }
            }
            else -> null
        }
    }

private fun urlSource(url: String, title: String?, state: GoogleStreamState): Content.Source.Url? =
    if (state.emitSource(url)) Content.Source.Url(id = state.nextSourceId(), url = url, title = title) else null

private fun documentMediaType(uri: String): String = when {
    uri.endsWith(".pdf") -> "application/pdf"
    uri.endsWith(".txt") -> "text/plain"
    uri.endsWith(".md") || uri.endsWith(".markdown") -> "text/markdown"
    uri.endsWith(".docx") ->
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    uri.endsWith(".doc") -> "application/msword"
    else -> "application/octet-stream"
}

/**
 * Converts the neutral prompt into Gemini contents, hoisting the system instruction.
 *
 * Reasoning parts are replayed as `thought` parts carrying their `thoughtSignature`, and a tool call
 * replays with the signature it arrived with. Google's rule is that thought blocks go back exactly as
 * received, so nothing here rebuilds them.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun Prompt.toGoogleContents(
    /** Gemini 3 reads a tool result's files under `functionResponse.parts`; earlier generations reject it. */
    supportsFunctionResponseParts: Boolean = false,
): Pair<GoogleContent?, List<GoogleContent>> {
    var system: GoogleContent? = null
    val contents = mutableListOf<GoogleContent>()

    fun append(role: String, parts: List<GooglePart>) {
        if (parts.isEmpty()) return
        val last = contents.lastOrNull()
        // Gemini requires alternating roles; several tool results for one round merge into one turn.
        if (last != null && last.role == role) {
            contents[contents.lastIndex] = last.copy(parts = last.parts + parts)
        } else {
            contents += GoogleContent(role = role, parts = parts)
        }
    }

    forEach { message ->
        when (message) {
            is ModelMessage.System -> {
                val existing = system?.parts.orEmpty()
                system = GoogleContent(parts = existing + GooglePart(text = message.content))
            }

            is ModelMessage.User -> append(
                "user",
                message.content.map { part ->
                    when (part) {
                        is UserPart.Text -> GooglePart(text = part.text)
                        is UserPart.File -> when (val d = part.data) {
                            is FileData.Url -> GooglePart(
                                fileData = GoogleFileData(mimeType = part.mediaType, fileUri = d.url),
                            )
                            is FileData.Bytes -> GooglePart(
                                inlineData = GoogleInlineData(
                                    mimeType = part.mediaType,
                                    data = Base64.encode(d.bytes),
                                ),
                            )
                            // A file already uploaded to the Files API is addressed by its URI, which is
                            // what the reference stores under this provider's key.
                            is FileData.Reference -> GooglePart(
                                fileData = d.reference[GOOGLE_PROVIDER_ID]?.let {
                                    GoogleFileData(mimeType = part.mediaType, fileUri = it)
                                },
                            )
                            // Text that is already characters goes as text: Gemini reads it directly,
                            // and base64-ing it would make the model decode what it could have read.
                            is FileData.Text -> GooglePart(text = d.text)
                        }
                    }
                },
            )

            is ModelMessage.Assistant -> append(
                "model",
                message.content.mapNotNull { it.toGooglePart(supportsFunctionResponseParts) },
            )

            // Gemini has no tool role: results go back as a user turn of functionResponse parts. An
            // approval response has no representation here; the decision is already reflected in the
            // result that accompanies it.
            is ModelMessage.Tool -> append(
                "user",
                message.content.filterIsInstance<ToolPart.Result>().map {
                    functionResponsePart(it.toolName, it.output, supportsFunctionResponseParts)
                },
            )
        }
    }

    return system to contents
}

@OptIn(ExperimentalEncodingApi::class)
private fun AssistantPart.toGooglePart(supportsFunctionResponseParts: Boolean): GooglePart? = when (this) {
    is AssistantPart.Text -> GooglePart(text = text, thoughtSignature = signature())

    is AssistantPart.Reasoning -> GooglePart(
        text = text,
        thought = true,
        thoughtSignature = signature(),
    )

    is AssistantPart.ToolCall -> GooglePart(
        functionCall = GoogleFunctionCall(
            name = toolName,
            args = runCatching { ProviderJson.parseToJsonElement(input) as? JsonObject }.getOrNull(),
            id = toolCallId.takeIf { !it.startsWith("gemini_") },
        ),
        // Where the compat path loses it, producing "Function call is missing a thought_signature".
        thoughtSignature = signature(),
    )

    is AssistantPart.ToolResult -> functionResponsePart(toolName, output, supportsFunctionResponseParts)

    // Gemini returns generated media as inlineData and takes it back the same way, so a model that
    // produced an image can be shown its own output on the next round.
    is AssistantPart.File -> when (val d = data) {
        is FileData.Bytes -> GooglePart(
            inlineData = GoogleInlineData(mimeType = mediaType, data = Base64.encode(d.bytes)),
            thoughtSignature = signature(),
        )
        is FileData.Url -> GooglePart(fileData = GoogleFileData(mimeType = mediaType, fileUri = d.url))
        is FileData.Reference -> d.reference[GOOGLE_PROVIDER_ID]?.let {
            GooglePart(fileData = GoogleFileData(mimeType = mediaType, fileUri = it))
        }
        is FileData.Text -> GooglePart(text = d.text)
    }

    // A reasoning artifact replays as a thought part carrying its own signature — the same replay path
    // as reasoning text, which is what keeps the signature attached to the block that was signed.
    is AssistantPart.ReasoningFile -> when (val d = data) {
        is FileData.Bytes -> GooglePart(
            inlineData = GoogleInlineData(mimeType = mediaType, data = Base64.encode(d.bytes)),
            thought = true,
            thoughtSignature = signature(),
        )
        else -> null
    }

    // A custom part carries no content of its own; whatever it holds is namespaced under a provider
    // that is not this one, so there is nothing here Gemini could be told.
    is AssistantPart.Custom -> null

    // Runtime-to-approver bookkeeping. Gemini has no representation for it and needs none: the model
    // sees the call and, a turn later, either its result or the denial.
    is AssistantPart.ApprovalRequest -> null
}

/**
 * A tool result as a `functionResponse` part.
 *
 * `response` is the `Struct` Gemini requires, so a bare string is wrapped rather than sent raw. A
 * multipart result's files ride `parts` as inline data on Gemini 3 — the generation that reads them
 * there, and the reason Vertex downloads a URL file first — and are named by media type in the text on
 * the generations that would reject the field.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun functionResponsePart(toolName: String, output: ToolOutput, supportsParts: Boolean): GooglePart {
    val inline = (output as? ToolOutput.Multipart)?.value.orEmpty()
        .takeIf { supportsParts }.orEmpty()
        .filterIsInstance<ToolOutput.Multipart.Item.File>()
        .mapNotNull { file ->
            (file.data as? FileData.Bytes)?.let {
                GoogleFunctionResponsePart(GoogleInlineData(mimeType = file.mediaType, data = Base64.encode(it.bytes)))
            }
        }
    return GooglePart(
        functionResponse = GoogleFunctionResponse(
            name = toolName,
            response = output.toGoogleResponse(inlineFiles = supportsParts),
            parts = inline.takeIf { it.isNotEmpty() },
        ),
    )
}

/** Gemini requires an object, so a bare string is wrapped rather than sent raw. */
private fun ToolOutput.toGoogleResponse(inlineFiles: Boolean): JsonObject = when (this) {
    is ToolOutput.Json -> this.value as? JsonObject ?: buildJsonObject { put("result", value.toString()) }
    is ToolOutput.ErrorJson -> buildJsonObject { put("error", value.toString()) }
    is ToolOutput.Text -> buildJsonObject { put("result", value) }
    is ToolOutput.ErrorText -> buildJsonObject { put("error", value) }
    is ToolOutput.ExecutionDenied -> buildJsonObject { put("error", reason ?: "Execution denied.") }
    is ToolOutput.Multipart -> buildJsonObject {
        val text = value.mapNotNull { item ->
            when (item) {
                is ToolOutput.Multipart.Item.Text -> item.text
                // Inline bytes ride `parts` where the model reads them; anything else is named here.
                is ToolOutput.Multipart.Item.File ->
                    if (inlineFiles && item.data is FileData.Bytes) null else "[${item.mediaType}]"
                // A custom item carries no content of its own — only providerOptions.
                is ToolOutput.Multipart.Item.Custom -> null
            }
        }
        // The reference's own words for a result that was all files: the model needs a sentence, not "".
        put("result", JsonPrimitive(text.joinToString("\n").ifEmpty { "Tool executed successfully." }))
    }
}

private fun AssistantPart.signature(): String? = providerOptions
    ?.get(GOOGLE_PROVIDER_ID)
    ?.get(GOOGLE_THOUGHT_SIGNATURE_KEY)
    ?.stringOrNull()
    ?.takeIf { it.isNotEmpty() }
