package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.anthropic.ANTHROPIC_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.anthropic.anthropicModelCapabilities
import com.sabreware.aide.aisdk.providers.anthropic.sanitizeAnthropicJsonSchema
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.AwsEventStreamDecoder
import com.sabreware.aide.aisdk.util.AwsEventStreamMessage
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.SigV4
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.isExplicit
import com.sabreware.aide.aisdk.util.mapReasoningToBudget
import com.sabreware.aide.aisdk.util.mapReasoningToEffort
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bedrock's Converse API — the one wire that serves every hosted vendor Bedrock carries.
 *
 * This is the counterpart to [BedrockLanguageModel], and the split is deliberate: Anthropic models keep
 * the native `invoke-with-response-stream` path, whose body is Anthropic's own and whose signature
 * handling is shared with the direct provider, while everything else — Nova, Llama, Mistral, Cohere,
 * Titan, DeepSeek, gpt-oss — speaks Converse here. The provider routes by model id, so this class never
 * sees a plain `anthropic.*` id; the one Anthropic-shaped thing it must still handle is an application
 * inference profile ARN, which hides its model family and is detected two ways — the caller declared
 * [modelFamily], or supplied an Anthropic thinking budget (see [isBedrockAnthropicModel]).
 *
 * Converse quirks that are rejections rather than degradations, all handled in the request builder:
 * temperature is capped at 1.0 (clamped, with a warning); `frequencyPenalty`/`presencePenalty`/`seed`
 * have no field at all; structured output is native only for an Anthropic-shaped model, where
 * `output_config.format` rides in `additionalModelRequestFields` — everywhere else a JSON response
 * format with a schema becomes a forced `json` tool whose input is surfaced as the answer text, and
 * `tool_use` is then reported as [FinishReason.Unified.Stop], because to the caller it WAS a plain
 * answer. `structuredOutputMode` (`outputFormat` | `jsonTool` | `auto`) lets a caller pick.
 *
 * `doGenerate` folds `doStream`, as on the Anthropic path: one wire that is right beats two that drift.
 */
internal class BedrockConverseLanguageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val credentials: () -> AwsCredentials,
    private val region: String,
    private val now: () -> Long,
    private val generateId: () -> String = IdGenerator("tooluse")::next,
    /** [BEDROCK_MODEL_FAMILY_ANTHROPIC] when the id does not say — an application inference profile ARN. */
    private val modelFamily: String? = null,
    /** The runtime endpoint; defaults to the region's own, and a host with an override passes it. */
    private val baseUrl: String = bedrockBaseUrl(region),
) : LanguageModel {

    override val provider: String = BEDROCK_PROVIDER_ID

    /** Bedrock fetches attachments only from S3; anything else must be sent as bytes. */
    override suspend fun supportedUrls(): Map<String, List<Regex>> = mapOf(
        "image/*" to listOf(S3_URL),
        "video/*" to listOf(S3_URL),
    )

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildRequest(options)
        return StreamResult(
            stream = streamParts(built, options),
            request = RequestInfo(encode(built.body)),
        )
    }

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildRequest(options)
        var meta: HttpResult<Unit>? = null
        val result = assembleGenerateResult(streamParts(built, options) { meta = it })
        return result.copy(
            request = RequestInfo(encode(built.body)),
            response = meta?.responseInfo(modelId = modelId, id = result.response?.metadata?.id)
                ?: result.response,
        )
    }

    // --- Request -----------------------------------------------------------------------------------

    private class Built(
        val body: JsonObject,
        val warnings: List<Warning>,
        val usesJsonResponseTool: Boolean,
    )

    private fun buildRequest(options: CallOptions): Built {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions.bedrockEntry()
        val explicit = vendor?.optObject("reasoningConfig")
        val isAnthropic = isBedrockAnthropicModel(modelId, modelFamily, explicit?.optInt("budgetTokens"))
        // The vendor's own key outranks the canonical `anthropic` one — the rule every rehosted
        // Anthropic surface here follows, so a caller's `anthropic.structuredOutputMode` still counts.
        val structuredOutputMode = vendor?.optString("structuredOutputMode")
            ?: options.providerOptions?.get(ANTHROPIC_PROVIDER_ID)?.optString("structuredOutputMode")
            ?: STRUCTURED_OUTPUT_AUTO

        val additional = LinkedHashMap<String, JsonElement>()
        vendor?.optObject("additionalModelRequestFields")?.forEach { (key, value) -> additional[key] = value }
        if (structuredOutputMode == STRUCTURED_OUTPUT_JSON_TOOL) additional.withoutOutputFormat()
        vendor?.optArray("anthropicBeta")?.let { additional["anthropic_beta"] = it }

        val inference = InferenceConfig(options, warnings)
        resolveReasoning(options, explicit, isAnthropic, warnings, additional, inference)
        val structured = resolveStructuredOutput(options, isAnthropic, structuredOutputMode, explicit, additional)

        val tools = prepareTools(options, warnings, isAnthropic, structured)
        val converted = BedrockConverseMessages.convert(options.prompt, isMistralModel(modelId))

        val body = buildJsonObject {
            put("system", converted.system)
            put("messages", converted.messages)
            if (additional.isNotEmpty()) put("additionalModelRequestFields", JsonObject(additional))
            if (isAnthropic) {
                // Surfaces the matched stop sequence, which Converse otherwise swallows.
                put("additionalModelResponseFieldPaths", buildJsonArray { add("/delta/stop_sequence") })
            }
            inference.toJson()?.let { put("inferenceConfig", it) }
            vendor?.optString("serviceTier")?.let { tier ->
                putJsonObject("serviceTier") { put("type", tier) }
            }
            // Anything else filed under the provider's namespace — guardrailConfig, promptVariables,
            // performanceConfig — rides onto the command verbatim, which is how a Converse field this
            // port has never heard of still reaches the wire.
            vendor?.forEach { (key, value) -> if (key !in CONSUMED_OPTION_KEYS) put(key, value) }
            tools.toolConfig?.let { put("toolConfig", it) }
        }
        return Built(body, warnings, tools.usesJsonResponseTool)
    }

    /**
     * Drops a caller-supplied `output_config.format` when the JSON tool was chosen outright: both
     * mechanisms in one request would ask the model for one object two ways. Sibling fields (`effort`)
     * stay, and an emptied `output_config` goes entirely rather than as `{}`.
     */
    private fun MutableMap<String, JsonElement>.withoutOutputFormat() {
        val config = this["output_config"] as? JsonObject ?: return
        val remaining = JsonObject(config - "format")
        if (remaining.isEmpty()) remove("output_config") else this["output_config"] = remaining
    }

    private class StructuredOutput(val schema: JsonObject?, val native: Boolean)

    /**
     * Native `output_config.format` or the JSON tool, for a JSON response format with a schema.
     *
     * Native needs an Anthropic-shaped model and either the caller forcing `outputFormat` or `auto`
     * landing on a model that serves it: one Bedrock's schema accepts the field for and serves reliably
     * ([bedrockSupportsNativeStructuredOutput]) that also has the capability by Anthropic's own table —
     * or is thinking, which implies a current model — or was declared Anthropic by the caller, since an
     * ARN reveals nothing to the table. Everything else is served by the JSON tool, which
     * [prepareTools] adds when [StructuredOutput.native] is false.
     */
    private fun resolveStructuredOutput(
        options: CallOptions,
        isAnthropic: Boolean,
        mode: String,
        explicitReasoning: JsonObject?,
        additional: MutableMap<String, JsonElement>,
    ): StructuredOutput {
        val schema = (options.responseFormat as? ResponseFormat.Json)?.schema
            ?: return StructuredOutput(schema = null, native = false)
        val thinkingEnabled = explicitReasoning?.optString("type").let { it == "enabled" || it == "adaptive" }
        val modelSupportsNative = bedrockSupportsNativeStructuredOutput(modelId) &&
            (
                anthropicModelCapabilities(modelId).supportsStructuredOutput ||
                    thinkingEnabled ||
                    modelFamily == BEDROCK_MODEL_FAMILY_ANTHROPIC
                )
        val native = isAnthropic &&
            (mode == STRUCTURED_OUTPUT_FORMAT || (mode == STRUCTURED_OUTPUT_AUTO && modelSupportsNative))
        if (native) {
            additional.merge("output_config") {
                put(
                    "format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put("schema", sanitizeAnthropicJsonSchema(schema))
                    },
                )
            }
        }
        return StructuredOutput(schema, native)
    }

    /** The sampler knobs Converse takes, minus the ones it does not, each absence said out loud. */
    private class InferenceConfig(options: CallOptions, warnings: MutableList<Warning>) {
        var maxTokens: Int? = options.maxOutputTokens
        var temperature: Double? = options.temperature
        var topP: Double? = options.topP
        var topK: Int? = options.topK
        val stopSequences: List<String>? = options.stopSequences

        init {
            options.frequencyPenalty?.let { warnings += Warning.Unsupported("frequencyPenalty") }
            options.presencePenalty?.let { warnings += Warning.Unsupported("presencePenalty") }
            options.seed?.let { warnings += Warning.Unsupported("seed") }
            val t = temperature
            if (t != null && t > MAX_TEMPERATURE) {
                warnings += Warning.Compatibility(
                    feature = "temperature",
                    details = "$t exceeds bedrock maximum of 1.0. clamped to 1.0",
                )
                temperature = MAX_TEMPERATURE
            } else if (t != null && t < 0.0) {
                warnings += Warning.Compatibility(
                    feature = "temperature",
                    details = "$t is below bedrock minimum of 0. clamped to 0",
                )
                temperature = 0.0
            }
        }

        fun toJson(): JsonObject? = buildJsonObject {
            maxTokens?.let { put("maxTokens", it) }
            temperature?.let { put("temperature", it) }
            topP?.let { put("topP", it) }
            topK?.let { put("topK", it) }
            stopSequences?.takeIf { it.isNotEmpty() }?.let { stops ->
                put("stopSequences", buildJsonArray { stops.forEach { add(JsonPrimitive(it)) } })
            }
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * One reasoning story for three model families.
     *
     * The explicit `reasoningConfig` option wins field-by-field over what the neutral effort derives —
     * a caller that named a token budget knows something the effort ladder cannot express. Where the
     * effort lands then depends on the family: Anthropic wants `output_config.effort`, OpenAI's gpt-oss
     * wants a flat `reasoning_effort`, its GPT-5.x line a nested `reasoning.effort`, and everything
     * else (Nova) the `reasoningConfig` object. Getting the address wrong is not degraded behaviour —
     * the field is simply ignored, and the depth control silently does nothing.
     */
    private fun resolveReasoning(
        options: CallOptions,
        explicit: JsonObject?,
        isAnthropic: Boolean,
        warnings: MutableList<Warning>,
        additional: MutableMap<String, JsonElement>,
        inference: InferenceConfig,
    ) {
        var type = explicit?.optString("type")
        var budget = explicit?.optInt("budgetTokens")
        var effort = explicit?.optString("maxReasoningEffort")
        val display = explicit?.optString("display")

        if (options.reasoning.isExplicit) {
            if (isAnthropic) {
                if (options.reasoning == ReasoningEffort.None) {
                    if (type == null) type = "disabled"
                } else if (budget == null && type != "adaptive") {
                    // The capability table lives with the native Anthropic path; an ARN reveals no
                    // model, so the budget is derived from the caller's own output ceiling.
                    budget = mapReasoningToBudget(
                        reasoning = options.reasoning,
                        maxOutputTokens = options.maxOutputTokens ?: DEFAULT_MAX_TOKENS,
                        maxReasoningBudget = options.maxOutputTokens ?: DEFAULT_MAX_TOKENS,
                        warnings = warnings,
                    )
                    if (budget != null && type == null) type = "enabled"
                }
            } else if (options.reasoning != ReasoningEffort.None && effort == null) {
                effort = mapReasoningToEffort(options.reasoning, CONVERSE_EFFORT_MAP, warnings)
            }
        }

        if (type == "disabled") {
            budget = null
            effort = null
        }

        val thinking = isAnthropic && (type == "enabled" || type == "adaptive")
        when {
            thinking && budget != null -> {
                inference.maxTokens = (inference.maxTokens ?: DEFAULT_MAX_TOKENS) + budget
                additional["thinking"] = buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
            }
            thinking && type == "adaptive" -> additional["thinking"] = buildJsonObject {
                put("type", "adaptive")
                display?.let { put("display", it) }
            }
            !isAnthropic -> {
                if (budget != null) {
                    warnings += Warning.Unsupported(
                        feature = "budgetTokens",
                        details = "budgetTokens applies only to Anthropic models on Bedrock " +
                            "and will be ignored for this model.",
                    )
                }
                if (type == "adaptive") {
                    warnings += Warning.Unsupported(
                        feature = "adaptive thinking",
                        details = "adaptive thinking type applies only to Anthropic models on Bedrock.",
                    )
                }
            }
        }

        if (effort != null) {
            val openAiId = OPENAI_MODEL_ID.find(modelId)?.groupValues?.get(1)
            when {
                isAnthropic -> additional.merge("output_config") { put("effort", effort) }
                openAiId?.startsWith("openai.gpt-oss-") == true ->
                    additional["reasoning_effort"] = JsonPrimitive(effort)
                openAiId != null -> additional.merge("reasoning") { put("effort", effort) }
                else -> additional["reasoningConfig"] = buildJsonObject {
                    type?.takeIf { it != "adaptive" }?.let { put("type", it) }
                    budget?.let { put("budgetTokens", it) }
                    put("maxReasoningEffort", effort)
                }
            }
        }

        if (thinking) {
            inference.temperature?.let {
                warnings += Warning.Unsupported("temperature", "temperature is not supported when thinking is enabled")
                inference.temperature = null
            }
            inference.topP?.let {
                warnings += Warning.Unsupported("topP", "topP is not supported when thinking is enabled")
                inference.topP = null
            }
            inference.topK?.let {
                warnings += Warning.Unsupported("topK", "topK is not supported when thinking is enabled")
                inference.topK = null
            }
        }
    }

    /** Overlays [build] onto whatever object already sits under [key], keeping unrelated fields. */
    private fun MutableMap<String, JsonElement>.merge(
        key: String,
        build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        val existing = this[key] as? JsonObject
        this[key] = buildJsonObject {
            existing?.forEach { (k, v) -> put(k, v) }
            build()
        }
    }

    private class PreparedTools(val toolConfig: JsonObject?, val usesJsonResponseTool: Boolean)

    private fun prepareTools(
        options: CallOptions,
        warnings: MutableList<Warning>,
        isAnthropic: Boolean,
        structured: StructuredOutput,
    ): PreparedTools {
        val declared = options.tools.orEmpty()
        // Provider-defined tools belong to a vendor's own API surface; Converse has no field for them
        // on a non-Anthropic model, and the Anthropic models that do take them are routed natively.
        // The web tools Bedrock does not serve at all are refused by name, as the reference does.
        declared.filterIsInstance<Tool.ProviderDefined>().forEach { tool ->
            if (tool.id in UNSUPPORTED_ANTHROPIC_WEB_TOOLS) {
                val toolType = tool.id.removePrefix("$ANTHROPIC_PROVIDER_ID.")
                warnings += Warning.Unsupported(
                    feature = "$toolType tool",
                    details = "The $toolType tool is not supported on Amazon Bedrock.",
                )
            } else {
                warnings += Warning.Unsupported(feature = "tool ${tool.id}")
            }
        }

        // Native structured output constrains the decoder itself; the JSON tool serves the rest.
        val schema = structured.schema?.takeIf { !structured.native }
        if (options.responseFormat is ResponseFormat.Json && structured.schema == null) {
            warnings += Warning.Unsupported(
                feature = "responseFormat",
                details = "JSON response format requires a schema on the Bedrock Converse API; it was ignored.",
            )
        }

        val choice = options.toolChoice
        var functions = declared.filterIsInstance<Tool.Function>()
        if (choice is ToolChoice.Specific) functions = functions.filter { it.name == choice.toolName }
        if (choice is ToolChoice.None) functions = emptyList()

        // Bedrock takes `strict` on a Claude model's tool definition — except the newest families,
        // whose schema copy rejects it. The gate is Anthropic-only where the reference reads the table
        // for every model: Converse documents the field for Claude alone, and the old warning is kept
        // for the rest rather than sending a field their validator may refuse.
        val supportsStrict = isAnthropic && bedrockSupportsStrictTools(modelId)
        val specs = buildList {
            functions.forEach { tool ->
                if (tool.strict != null && !supportsStrict) {
                    warnings += Warning.Unsupported(
                        feature = "strict",
                        details = "Tool '${tool.name}' has strict: ${tool.strict}, but strict mode is " +
                            "not supported by this model on Amazon Bedrock. " +
                            "The strict property will be ignored.",
                    )
                }
                add(toolSpec(tool.name, tool.description, tool.inputSchema, strict = tool.strict?.takeIf { supportsStrict }))
            }
            if (schema != null) {
                add(toolSpec(JSON_TOOL, "Respond with a JSON object.", schema))
            }
        }
        if (specs.isEmpty()) return PreparedTools(null, usesJsonResponseTool = false)

        val wireChoice = when {
            schema != null -> buildJsonObject { putJsonObject("any") {} }
            choice is ToolChoice.Required -> buildJsonObject { putJsonObject("any") {} }
            choice is ToolChoice.Specific -> buildJsonObject {
                putJsonObject("tool") { put("name", choice.toolName) }
            }
            choice is ToolChoice.Auto -> buildJsonObject { putJsonObject("auto") {} }
            else -> null
        }
        val config = buildJsonObject {
            put("tools", JsonArray(specs))
            wireChoice?.let { put("toolChoice", it) }
        }
        return PreparedTools(config, usesJsonResponseTool = schema != null)
    }

    private fun toolSpec(name: String, description: String?, schema: JsonObject, strict: Boolean? = null): JsonObject =
        buildJsonObject {
            putJsonObject("toolSpec") {
                put("name", name)
                description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                strict?.let { put("strict", it) }
                putJsonObject("inputSchema") { put("json", schema) }
            }
        }

    // --- Stream ------------------------------------------------------------------------------------

    private fun streamParts(
        built: Built,
        options: CallOptions,
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<StreamPart> = flow {
        emit(StreamPart.StreamStart(built.warnings))
        val url = "$baseUrl/model/${modelId.encodeURLParameter()}/converse-stream"
        val payload = encode(built.body).encodeToByteArray()

        // The caller's headers are signed with everything else: SigV4 covers the header set, so a
        // header added after signing is a 403 rather than an extra header.
        val callerHeaders = combineHeaders(mapOf("content-type" to "application/json"), options.headers)
        val signed = SigV4.signedHeaders(
            method = "POST",
            url = url,
            headers = callerHeaders,
            payload = payload,
            credentials = credentials(),
            region = region,
            service = SERVICE,
            timestampMillis = now(),
        )

        val decoder = AwsEventStreamDecoder()
        val state = ConverseStreamState(
            usesJsonResponseTool = built.usesJsonResponseTool,
            isMistral = isMistralModel(modelId),
            includeRawChunks = options.includeRawChunks,
            generateId = generateId,
            url = url,
        )
        var meta: HttpResult<Unit>? = null
        var metaEmitted = false
        http.postBytes(url, payload, callerHeaders + signed) { result ->
            meta = result
            onResponse(result)
        }.collect { chunk ->
            if (!metaEmitted) {
                metaEmitted = true
                meta?.let { emit(StreamPart.ResponseMetadataPart(it.responseInfo(modelId = modelId).metadata)) }
            }
            decoder.feed(chunk).forEach { state.handle(this, it) }
        }
        if (decoder.hasPartialMessage()) throw APICallError("Bedrock stream ended mid-frame", url = url)
        state.finish(this)
    }

    private fun encode(body: JsonObject): String =
        ProviderJson.encodeToString(JsonElement.serializer(), body)

    private companion object {
        const val SERVICE = "bedrock"
        const val JSON_TOOL = "json"
        const val MAX_TEMPERATURE = 1.0
        const val STRUCTURED_OUTPUT_AUTO = "auto"
        const val STRUCTURED_OUTPUT_FORMAT = "outputFormat"
        const val STRUCTURED_OUTPUT_JSON_TOOL = "jsonTool"

        /** The Anthropic web tools Bedrock does not serve; the reference refuses exactly these three. */
        val UNSUPPORTED_ANTHROPIC_WEB_TOOLS = setOf(
            "anthropic.web_search_20250305", "anthropic.web_search_20260318", "anthropic.web_fetch_20260318",
        )

        /** The reference's default when a thinking budget arrives with no output ceiling to add to. */
        const val DEFAULT_MAX_TOKENS = 4096

        val S3_URL = Regex("^s3://")
        val OPENAI_MODEL_ID = Regex("^(?:[^.]+\\.)?(openai\\..+)$")

        /** Call-level option keys the builder consumes; the rest pass through onto the command. */
        val CONSUMED_OPTION_KEYS = setOf(
            "reasoningConfig", "additionalModelRequestFields", "serviceTier", "anthropicBeta", "structuredOutputMode",
        )

        /** Bedrock's effort vocabulary. `minimal` rounds up to `low`, `xhigh` to `max` — with a warning. */
        val CONVERSE_EFFORT_MAP = mapOf(
            ReasoningEffort.Minimal to "low",
            ReasoningEffort.Low to "low",
            ReasoningEffort.Medium to "medium",
            ReasoningEffort.High to "high",
            ReasoningEffort.XHigh to "max",
        )
    }
}

/**
 * The per-stream state a Converse stream needs: which kind each content block opened as, so a delta
 * lands in the block it belongs to, plus the finish/usage/metadata that only emit once the stream ends.
 *
 * Frames arrive as AWS event-stream messages whose `:event-type` header names the event and whose
 * payload is that event's JSON — unlike the invoke path, nothing is base64-wrapped. An
 * `:exception-type` frame is an error the model hit mid-stream; it becomes a [StreamPart.Error] rather
 * than an exception, because the parts already emitted are real output the caller may keep.
 */
private class ConverseStreamState(
    private val usesJsonResponseTool: Boolean,
    private val isMistral: Boolean,
    private val includeRawChunks: Boolean,
    private val generateId: () -> String,
    private val url: String,
) {

    private sealed interface Block {
        /** Announced by a bare `contentBlockStart`; its kind is unknown until the first delta. */
        data object Pending : Block
        data object Text : Block
        class Reasoning : Block {
            var opened = false
            var signature: String? = null
            var redactedData: String? = null
            var redactedContent: String? = null
        }
        class ToolUse(val id: String, val name: String, val isJsonTool: Boolean) : Block {
            val input = StringBuilder()
        }
    }

    private val blocks = mutableMapOf<Int, Block>()
    private var finishReason: FinishReason? = null
    private var stopSequence: String? = null
    private var usage: JsonObject? = null
    private var metadataPayload: JsonObject? = null
    private var jsonToolAnswered = false

    suspend fun handle(collector: FlowCollector<StreamPart>, message: AwsEventStreamMessage) {
        message.exceptionType?.let { type ->
            collector.emitException(type, message.payload.decodeToString())
            return
        }
        val eventType = message.eventType ?: return
        val payload = runCatching { parseJsonObject(message.payload.decodeToString()) }.getOrNull() ?: return
        if (includeRawChunks) {
            collector.emit(StreamPart.Raw(buildJsonObject { put(eventType, payload) }))
        }
        when (eventType) {
            "contentBlockStart" -> onBlockStart(collector, payload)
            "contentBlockDelta" -> onBlockDelta(collector, payload)
            "contentBlockStop" -> onBlockStop(collector, payload)
            "messageStop" -> onMessageStop(payload)
            "metadata" -> onMetadata(payload)
            else -> Unit
        }
    }

    /**
     * A mid-stream failure keeps the output that preceded it: the part is emitted, the finish reason
     * turns to error, and the stream carries on to its natural end. Retryability follows the exception
     * type — throttling and 5xx-shaped failures may succeed on a retry, a validation failure never will.
     */
    private suspend fun FlowCollector<StreamPart>.emitException(type: String, body: String) {
        finishReason = FinishReason(FinishReason.Unified.Error)
        val extracted = runCatching { parseJsonObject(body) }.getOrNull()?.optString("message")
        val (status, retryable) = when (type.replaceFirstChar { it.lowercaseChar() }) {
            "internalServerException" -> 500 to true
            "modelStreamErrorException" -> 424 to true
            "serviceUnavailableException" -> 503 to true
            "throttlingException" -> 429 to true
            "validationException" -> 400 to false
            else -> null to false
        }
        emit(
            StreamPart.Error(
                APICallError(
                    message = extracted ?: "Amazon Bedrock stream failed with $type",
                    url = url,
                    statusCode = status,
                    responseBody = body,
                    isRetryable = retryable,
                ),
            ),
        )
    }

    private suspend fun onBlockStart(collector: FlowCollector<StreamPart>, payload: JsonObject) {
        val index = payload.optInt("contentBlockIndex") ?: return
        val toolUse = payload.optObject("start")?.optObject("toolUse")
        if (toolUse == null) {
            // A bare start does not reveal the block's kind — Anthropic reasoning on Converse opens
            // this way too. The block is registered but not announced; the first delta decides whether
            // a TextStart or a ReasoningStart goes out, so no empty text block ever reaches a consumer.
            blocks[index] = Block.Pending
            return
        }
        val id = normalizeToolCallId(toolUse.optString("toolUseId") ?: generateId(), isMistral)
        val name = toolUse.optString("name") ?: "tool-${generateId()}"
        val isJsonTool = usesJsonResponseTool && name == JSON_TOOL_NAME
        blocks[index] = Block.ToolUse(id, name, isJsonTool)
        // The forced json tool is presentation, not a call; its input surfaces as text at block stop.
        if (!isJsonTool) collector.emit(StreamPart.ToolInputStart(id = id, toolName = name))
    }

    private suspend fun onBlockDelta(collector: FlowCollector<StreamPart>, payload: JsonObject) {
        val index = payload.optInt("contentBlockIndex") ?: 0
        val delta = payload.optObject("delta") ?: return

        delta.optString("text")?.let { text ->
            if (blocks[index] == null || blocks[index] == Block.Pending) {
                blocks[index] = Block.Text
                collector.emit(StreamPart.TextStart(index.toString()))
            }
            if (text.isNotEmpty()) collector.emit(StreamPart.TextDelta(index.toString(), text))
            return
        }

        delta.optObject("reasoningContent")?.let { reasoning ->
            onReasoningDelta(collector, index, reasoning)
            return
        }

        delta.optObject("toolUse")?.optString("input")?.let { fragment ->
            val block = blocks[index] as? Block.ToolUse ?: return
            if (!block.isJsonTool) collector.emit(StreamPart.ToolInputDelta(block.id, fragment))
            block.input.append(fragment)
        }
    }

    private suspend fun onReasoningDelta(
        collector: FlowCollector<StreamPart>,
        index: Int,
        reasoning: JsonObject,
    ) {
        val block = when (val existing = blocks[index]) {
            is Block.Reasoning -> existing
            is Block.ToolUse -> return
            Block.Text -> Block.Reasoning().also {
                // The block already opened as text — close it rather than leak reasoning into it.
                collector.emit(StreamPart.TextEnd(index.toString()))
                blocks[index] = it
            }
            Block.Pending, null -> Block.Reasoning().also { blocks[index] = it }
        }
        if (!block.opened) {
            block.opened = true
            collector.emit(StreamPart.ReasoningStart(index.toString()))
        }
        val id = index.toString()
        reasoning.optString("text")?.let { collector.emit(StreamPart.ReasoningDelta(id, it)) }
        // The replayable payloads are held back and attached ONCE on ReasoningEnd — that is where this
        // library's contract puts them (see StreamPart.ReasoningEnd), and where the assembler reads
        // them; a payload on a delta would be carried past the assembly and lost.
        reasoning.optString("signature")?.let { block.signature = it }
        reasoning.optString("data")?.let { block.redactedData = (block.redactedData ?: "") + it }
        reasoning.optString("redactedContent")?.let {
            block.redactedContent = (block.redactedContent ?: "") + it
        }
    }

    private suspend fun onBlockStop(collector: FlowCollector<StreamPart>, payload: JsonObject) {
        val index = payload.optInt("contentBlockIndex") ?: return
        when (val block = blocks.remove(index)) {
            Block.Pending -> Unit
            Block.Text -> collector.emit(StreamPart.TextEnd(index.toString()))
            is Block.Reasoning -> collector.emit(
                StreamPart.ReasoningEnd(
                    id = index.toString(),
                    providerMetadata = if (
                        block.signature != null || block.redactedData != null || block.redactedContent != null
                    ) {
                        bedrockMetadata {
                            block.signature?.let { put(BEDROCK_SIGNATURE_KEY, it) }
                            block.redactedData?.let { put(BEDROCK_REDACTED_DATA_KEY, it) }
                            block.redactedContent?.let { put(BEDROCK_REDACTED_CONTENT_KEY, it) }
                        }
                    } else {
                        null
                    },
                ),
            )
            is Block.ToolUse -> {
                val input = block.input.toString().ifEmpty { "{}" }
                if (block.isJsonTool) {
                    jsonToolAnswered = true
                    val id = index.toString()
                    collector.emit(StreamPart.TextStart(id))
                    collector.emit(StreamPart.TextDelta(id, input))
                    collector.emit(StreamPart.TextEnd(id))
                } else {
                    collector.emit(StreamPart.ToolInputEnd(block.id))
                    collector.emit(
                        StreamPart.ToolCallPart(
                            Content.ToolCall(toolCallId = block.id, toolName = block.name, input = input),
                        ),
                    )
                }
            }
            null -> Unit
        }
    }

    private fun onMessageStop(payload: JsonObject) {
        val raw = payload.optString("stopReason")
        finishReason = FinishReason(
            // The full set Converse documents is `end_turn | tool_use | max_tokens | stop_sequence |
            // guardrail_intervened | content_filtered | malformed_model_output | malformed_tool_use |
            // model_context_window_exceeded` (checked 2026-09-01,
            // docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html). The last
            // three used to fall through to `Other`: nothing was lost, since `raw` is always kept, but
            // a loop that branches on the unified reason saw three distinct outcomes — two of them
            // failures — as one unclassified stop.
            unified = when (raw) {
                "stop_sequence", "end_turn" -> FinishReason.Unified.Stop
                // Both are a ceiling: `max_tokens` is the one the caller set, and
                // `model_context_window_exceeded` is the model's own. A caller that retries shorter
                // wants to hear about both the same way.
                "max_tokens", "model_context_window_exceeded" -> FinishReason.Unified.Length
                "content_filtered", "guardrail_intervened" -> FinishReason.Unified.ContentFilter
                // The model produced something unusable — not a stop, and not a filter. Reporting
                // these as `Other` invited a loop to treat a broken turn as a finished one.
                "malformed_model_output", "malformed_tool_use" -> FinishReason.Unified.Error
                // The forced json tool call IS the answer; reporting tool-calls would send any loop
                // keyed on the finish reason off to look for results that do not exist.
                "tool_use" -> if (jsonToolAnswered) FinishReason.Unified.Stop else FinishReason.Unified.ToolCalls
                else -> FinishReason.Unified.Other
            },
            raw = raw,
        )
        stopSequence = payload.optObject("additionalModelResponseFields")
            ?.optObject("delta")?.optString("stop_sequence")
    }

    private fun onMetadata(payload: JsonObject) {
        payload.optObject("usage")?.let { usage = it }
        val cache = usage?.let { u ->
            val write = u.optInt("cacheWriteInputTokens")
            val details = u.optArray("cacheDetails")
            if (write != null || details != null) {
                buildJsonObject {
                    write?.let { put("cacheWriteInputTokens", it) }
                    details?.let { put("cacheDetails", it) }
                }
            } else {
                null
            }
        }
        val extras = buildJsonObject {
            cache?.let { put("usage", it) }
            payload["trace"]?.let { put("trace", it) }
            payload.optObject("performanceConfig")?.let { put("performanceConfig", it) }
            payload.optObject("serviceTier")?.let { put("serviceTier", it) }
        }
        if (extras.isNotEmpty()) metadataPayload = extras
    }

    suspend fun finish(collector: FlowCollector<StreamPart>) {
        val payload = buildJsonObject {
            metadataPayload?.forEach { (key, value) -> put(key, value) }
            if (jsonToolAnswered) put("isJsonResponseFromTool", true)
            stopSequence?.let { put("stopSequence", it) }
        }
        collector.emit(
            StreamPart.Finish(
                usage = usage.toConverseUsage(),
                finishReason = finishReason ?: FinishReason(FinishReason.Unified.Other),
                providerMetadata = payload.takeIf { it.isNotEmpty() }
                    ?.let { mapOf(BEDROCK_PROVIDER_ID to it) },
            ),
        )
    }

    private fun bedrockMetadata(
        build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Map<String, JsonObject> = mapOf(BEDROCK_PROVIDER_ID to buildJsonObject(build))

    private companion object {
        const val JSON_TOOL_NAME = "json"
    }
}

/**
 * Converse usage → [Usage]. The total re-adds the cache tokens Converse reports separately, matching
 * the reference; a count the wire never sent stays null rather than being invented as zero.
 */
private fun JsonObject?.toConverseUsage(): Usage {
    this ?: return Usage()
    val input = optInt("inputTokens")
    val output = optInt("outputTokens")
    val cacheRead = optInt("cacheReadInputTokens")
    val cacheWrite = optInt("cacheWriteInputTokens")
    return Usage(
        inputTokens = Usage.InputTokens(
            total = input?.let { it + (cacheRead ?: 0) + (cacheWrite ?: 0) },
            noCache = input,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
        ),
        outputTokens = Usage.OutputTokens(total = output, text = output),
        raw = this,
    )
}
