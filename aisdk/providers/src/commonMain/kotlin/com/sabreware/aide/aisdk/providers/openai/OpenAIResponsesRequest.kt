package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.Warning
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A request body, everything the mappers need to interpret its response, and what it could not honour. */
internal data class BuiltResponsesRequest(
    val body: JsonObject,
    val warnings: List<Warning>,
    val tools: PreparedTools,
    /** Whether OpenAI is keeping this conversation, which decides when a reasoning block may conclude. */
    val store: Boolean,
)

/**
 * Provider options this request understands, mapped from the caller's camelCase to OpenAI's wire name.
 *
 * A key that is NOT here goes on the wire verbatim — see [buildResponsesRequest]. That is the escape
 * hatch: OpenAI adds request fields faster than any port tracks them, and a client that can only send
 * what its author knew about is one release behind permanently.
 */
private val PassThroughOptions: Map<String, String> = mapOf(
    "conversation" to "conversation",
    "instructions" to "instructions",
    "maxToolCalls" to "max_tool_calls",
    "metadata" to "metadata",
    "parallelToolCalls" to "parallel_tool_calls",
    "previousResponseId" to "previous_response_id",
    "promptCacheKey" to "prompt_cache_key",
    "promptCacheOptions" to "prompt_cache_options",
    "promptCacheRetention" to "prompt_cache_retention",
    "safetyIdentifier" to "safety_identifier",
    "serviceTier" to "service_tier",
    "truncation" to "truncation",
    "user" to "user",
)

/** Options consumed here rather than forwarded; listing them keeps them out of the verbatim spread. */
private val ConsumedOptions: Set<String> = setOf(
    "include", "logprobs", "reasoningContext", "reasoningEffort", "reasoningMode", "reasoningSummary",
    "store", "strictJsonSchema", "systemMessageMode", "textVerbosity", "forceReasoning",
    "passThroughUnsupportedFiles", "contextManagement", "compactionTrigger", "allowedTools",
    "includeWebSearchSources", "reasoningEffortUpdate",
)

/**
 * Builds the `/v1/responses` body.
 *
 * The reasoning-model parameter rules live here, and each one is a guaranteed 400 if it is got wrong:
 *
 * - **`max_output_tokens`, not `max_tokens`.** The Responses API renamed it and rejects the old name.
 * - **No sampler parameters on a reasoning model.** `temperature` and `top_p` are refused outright,
 *   except on GPT-5.1 and later while reasoning effort is `none` — sampling and reasoning are mutually
 *   exclusive on that family, not jointly unavailable.
 * - **`developer`, not `system`.** A reasoning model wants its instructions under the developer role.
 * - **No `stop`, `seed`, `top_k` or penalties at all.** They do not exist on this API in any form, so
 *   every one of them is a warning rather than a silently dropped field.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun buildResponsesRequest(
    modelId: String,
    options: CallOptions,
    stream: Boolean,
    namespace: String = OPENAI_PROVIDER_ID,
    quirks: ResponsesQuirks = ResponsesQuirks(),
    /** The endpoint's Open Responses extensions — see [OpenResponsesExtension]. */
    extensions: OpenResponsesExtensionRegistry = OpenResponsesExtensionRegistry.Empty,
): BuiltResponsesRequest {
    val warnings = mutableListOf<Warning>()
    val openai = mergedResponsesOptions(options.providerOptions, namespace)
    val capabilities = openAICapabilities(modelId)

    // A gateway serving a reasoning model under an unrecognized id gets the rules applied on request.
    val isReasoningModel = openai["forceReasoning"]?.jsonPrimitive?.booleanOrNull
        ?: capabilities.isReasoningModel

    if (options.topK != null) warnings += Warning.Unsupported("topK", RESPONSES_HAS_NO_FIELD)
    if (options.seed != null) warnings += Warning.Unsupported("seed", RESPONSES_HAS_NO_FIELD)
    if (options.presencePenalty != null) {
        warnings += Warning.Unsupported("presencePenalty", RESPONSES_HAS_NO_FIELD)
    }
    if (options.frequencyPenalty != null) {
        warnings += Warning.Unsupported("frequencyPenalty", RESPONSES_HAS_NO_FIELD)
    }
    if (options.stopSequences != null) {
        warnings += Warning.Unsupported("stopSequences", RESPONSES_HAS_NO_FIELD)
    }

    // An explicit `reasoningEffort` provider option beats the neutral enum: it can name a level the
    // enum has no word for, which is the reason a provider option exists at all.
    val explicitEffort = openai.stringOrNull("reasoningEffort")
    val resolved = when {
        explicitEffort == null ->
            resolveEffort(options.reasoning, capabilities.copy(isReasoningModel = isReasoningModel), modelId)
        // GPT-6 closed its list: a level outside it is a 400, not a word the enum lacks.
        capabilities.enforcesEffortLevels && explicitEffort !in capabilities.effortLevels -> ResolvedEffort(
            effort = null,
            warnings = listOf(
                Warning.Unsupported(
                    feature = "reasoningEffort",
                    details = "$modelId only supports the following reasoning efforts: " +
                        capabilities.effortLevels.joinToString(", "),
                ),
            ),
        )
        else -> ResolvedEffort(explicitEffort, emptyList())
    }
    warnings += resolved.warnings

    val samplingAllowed = !isReasoningModel ||
        (resolved.effort == "none" && capabilities.allowsSamplingWhenEffortNone)
    if (!samplingAllowed) {
        if (options.temperature != null) {
            warnings += Warning.Unsupported("temperature", "Reasoning models reject it.")
        }
        if (options.topP != null) {
            warnings += Warning.Unsupported("topP", "Reasoning models reject it.")
        }
    }

    val tools = prepareTools(
        options.tools,
        options.toolChoice,
        openai["allowedTools"] as? JsonObject,
        functionToolsOnly = quirks.functionToolsOnly,
        vendorToolNames = quirks.providerToolNames,
        vendorToolBody = quirks.providerToolBodies,
        extensions = extensions,
        namespace = namespace,
        supportsAsyncToolCalling = capabilities.supportsAsyncToolCalling,
    )
    warnings += tools.warnings

    val store = openai["store"]?.jsonPrimitive?.booleanOrNull ?: true
    val systemRole = when (openai.stringOrNull("systemMessageMode")) {
        "remove" -> null
        "system" -> "system"
        "developer" -> "developer"
        else -> if (isReasoningModel) "developer" else capabilities.systemRole
    }

    val converted = options.prompt.toOpenAIResponsesInput(
        OpenAIReplayContext(
            systemRole = systemRole,
            store = store,
            mapping = tools.mapping,
            providerToolsPresent = tools.providerToolsPresent,
            customToolNames = tools.customToolNames,
            hasConversation = openai.stringOrNull("conversation") != null,
            hasPreviousResponseId = openai.stringOrNull("previousResponseId") != null,
            passThroughUnsupportedFiles =
            openai["passThroughUnsupportedFiles"]?.jsonPrimitive?.booleanOrNull ?: false,
            namespace = namespace,
            extensions = extensions,
            providerToolsByName = tools.providerToolsByName,
            explicitMessageItemType = quirks.explicitMessageItemType,
            strictResponseInput = quirks.strictResponseInput,
            defaultImageDetail = quirks.defaultImageDetail,
        ),
    )
    warnings += converted.warnings

    var topLogprobs = when (val logprobs = openai["logprobs"]) {
        null -> null
        is JsonPrimitive -> logprobs.intOrNull ?: TOP_LOGPROBS_MAX.takeIf { logprobs.booleanOrNull == true }
        else -> null
    }
    val requestedInclude = openai["include"] as? JsonArray
    // GPT-6 refuses logprobs on a reasoning model — the field and the include value both.
    val dropLogprobs = isReasoningModel && capabilities.enforcesEffortLevels &&
        (topLogprobs != null || requestedInclude.containsString(INCLUDE_LOGPROBS))
    if (dropLogprobs) {
        warnings += Warning.Unsupported("logprobs", "Reasoning models reject it.")
        topLogprobs = null
    }

    val include = buildIncludeList(
        requested = requestedInclude,
        store = store,
        isReasoningModel = isReasoningModel,
        providerToolsPresent = tools.providerToolsPresent,
        wantsLogprobs = topLogprobs != null,
        dropLogprobs = dropLogprobs,
        includeWebSearchSources = quirks.supportsWebSearchSourcesInclude &&
            openai["includeWebSearchSources"]?.jsonPrimitive?.booleanOrNull != false,
    )

    // A reasoning-effort change that applies from this response on. An INPUT ITEM, first in the list,
    // so the request-level `reasoning.effort` — and with it the cached prompt prefix — stays put.
    val configurationUpdate = openai.stringOrNull("reasoningEffortUpdate")?.let { effort ->
        val refusal = when {
            !capabilities.supportsConfigurationUpdate ->
                "reasoningEffortUpdate is only supported by GPT-6 and later models"
            openai.stringOrNull("reasoningMode") == "pro" || openai["contextManagement"].isPresent() ||
                openai.stringOrNull("truncation") == "auto" ->
                "reasoningEffortUpdate requires standard reasoning mode without automatic compaction " +
                    "or automatic truncation"
            else -> null
        }
        if (refusal != null) {
            warnings += Warning.Unsupported("reasoningEffortUpdate", refusal)
            null
        } else {
            buildJsonObject {
                put("type", "configuration_update")
                putJsonObject("reasoning") { put("effort", effort) }
            }
        }
    }

    // GPT-6 replaced the retention flag with `prompt_cache_options`; the old field is a 400 there.
    val dropCacheRetention = capabilities.supportsConfigurationUpdate && openai["promptCacheRetention"].isPresent()
    if (dropCacheRetention) {
        warnings += Warning.Unsupported(
            feature = "promptCacheRetention",
            details = "promptCacheRetention is not supported by GPT-6 and later models; use promptCacheOptions instead",
        )
    }

    val strictJsonSchema = openai["strictJsonSchema"]?.jsonPrimitive?.booleanOrNull ?: true
    val textVerbosity = openai.stringOrNull("textVerbosity")
    val format = options.responseFormat?.toResponsesTextFormat(strictJsonSchema, warnings)

    val body = buildJsonObject {
        put("model", modelId)
        // `compaction_trigger` is an INPUT ITEM, not a body field — no passthrough could express it.
        val compact = openai["compactionTrigger"]?.jsonPrimitive?.booleanOrNull == true
        val items = listOfNotNull(configurationUpdate) + converted.items +
            listOfNotNull(buildJsonObject { put("type", "compaction_trigger") }.takeIf { compact })
        put("input", JsonArray(items))
        if (stream) put("stream", true)
        options.maxOutputTokens?.let { put("max_output_tokens", it) }
        if (samplingAllowed) {
            options.temperature?.let { put("temperature", it) }
            options.topP?.let { put("top_p", it) }
        }

        if (format != null || textVerbosity != null) {
            putJsonObject("text") {
                format?.let { put("format", it) }
                textVerbosity?.let { put("verbosity", it) }
            }
        }

        tools.tools?.let { put("tools", it) }
        tools.toolChoice?.let { put("tool_choice", it) }

        if (isReasoningModel) {
            val summary = openai.stringOrNull("reasoningSummary")
                // Without a summary the reasoning item carries an id and an encrypted payload and no
                // readable text at all, which is a UI with nothing to show for tokens already billed.
                ?: "detailed".takeIf { resolved.effort != null && resolved.effort != "none" }
            val mode = openai.stringOrNull("reasoningMode")
            val reasoningContext = openai.stringOrNull("reasoningContext")
            if (resolved.effort != null || summary != null || mode != null || reasoningContext != null) {
                putJsonObject("reasoning") {
                    resolved.effort?.let { put("effort", it) }
                    summary?.let { put("summary", it) }
                    mode?.let { put("mode", it) }
                    reasoningContext?.let { put("context", it) }
                }
            }
        }

        openai["store"]?.let { put("store", it) }
        include?.let { put("include", it) }
        topLogprobs?.let { put("top_logprobs", it) }

        // Server-side compaction. Each element is rewritten, not passed through: the wire wants
        // `compact_threshold` where the option surface says `compactThreshold`, and a camelCase key
        // here is a request that succeeds while compaction silently never engages.
        (openai["contextManagement"] as? JsonArray)?.let { managements ->
            put(
                "context_management",
                buildJsonArray {
                    managements.forEach { element ->
                        val entry = element as? JsonObject ?: return@forEach
                        add(
                            buildJsonObject {
                                entry["type"]?.let { put("type", it) }
                                entry["compactThreshold"]?.let { put("compact_threshold", it) }
                            },
                        )
                    }
                },
            )
        }

        openai.forEach { (key, value) ->
            when {
                key in ConsumedOptions -> Unit
                key == "promptCacheRetention" && dropCacheRetention -> Unit
                key in PassThroughOptions -> put(PassThroughOptions.getValue(key), value)
                // An option this port has never heard of. Sending it verbatim is the difference between
                // a caller waiting for a release and a caller using a field OpenAI shipped this morning.
                else -> put(key, value)
            }
        }
    }

    return BuiltResponsesRequest(body, warnings, tools, store)
}

/**
 * `include`, which is how a stateless client keeps its reasoning.
 *
 * `reasoning.encrypted_content` is added automatically whenever `store` is false on a reasoning model,
 * because that combination has exactly one correct answer and forgetting it is silent: the request
 * succeeds, the reasoning comes back with nothing to replay, and the next round re-derives it.
 */
private fun buildIncludeList(
    requested: JsonArray?,
    store: Boolean,
    isReasoningModel: Boolean,
    providerToolsPresent: Set<String>,
    wantsLogprobs: Boolean,
    dropLogprobs: Boolean = false,
    /** See [ResponsesQuirks.supportsWebSearchSourcesInclude] and the `includeWebSearchSources` option. */
    includeWebSearchSources: Boolean = true,
): JsonArray? {
    val values = LinkedHashSet<String>()
    requested?.forEach { (it as? JsonPrimitive)?.contentOrNullIfNotString()?.let(values::add) }
    if (dropLogprobs) values -= INCLUDE_LOGPROBS
    if (!store && isReasoningModel) values += OPENAI_INCLUDE_ENCRYPTED_REASONING
    // Without these the tool's own result comes back empty and the caller sees a search that found
    // nothing rather than a search whose findings were not requested.
    val webSearchPresent = "web_search" in providerToolsPresent || "web_search_preview" in providerToolsPresent
    if (webSearchPresent && includeWebSearchSources) values += "web_search_call.action.sources"
    if ("code_interpreter" in providerToolsPresent) values += "code_interpreter_call.outputs"
    if (wantsLogprobs) values += INCLUDE_LOGPROBS
    if (values.isEmpty()) return null
    return buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}

/**
 * The Responses API's `text.format`, which is where structured output lives.
 *
 * Note the shape difference from Chat Completions: the schema sits directly under `text.format` rather
 * than nested one level deeper in a `json_schema` object. Sending the Chat Completions shape here is a
 * 400 that names neither field.
 */
private fun ResponseFormat.toResponsesTextFormat(strict: Boolean, warnings: MutableList<Warning>): JsonElement =
    when (this) {
        ResponseFormat.Text -> buildJsonObject { put("type", "text") }
        is ResponseFormat.Json -> {
            val jsonSchema = schema
            if (jsonSchema == null) {
                buildJsonObject { put("type", "json_object") }
            } else {
                // The same `propertyNames` rule a tool's parameters get; see normalizeOpenAIJsonSchema.
                val normalized = normalizeOpenAIJsonSchema(jsonSchema)
                warnings += normalized.warnings
                buildJsonObject {
                    put("type", "json_schema")
                    put("name", name ?: "response")
                    description?.let { put("description", it) }
                    // Without strict decoding the model may return a superset of the schema and call
                    // it valid, which is a parse that succeeds and a value that is wrong.
                    put("strict", strict)
                    put("schema", normalized.schema)
                }
            }
        }
    }

private fun JsonPrimitive.contentOrNullIfNotString(): String? = takeIf { it.isString }?.content

private fun JsonArray?.containsString(value: String): Boolean =
    this?.any { (it as? JsonPrimitive)?.contentOrNullIfNotString() == value } == true

/** Set, and not to JSON `null` — the reference's `!= null`, which a `JsonNull` would otherwise pass. */
private fun JsonElement?.isPresent(): Boolean = this != null && this != JsonNull

private const val INCLUDE_LOGPROBS = "message.output_text.logprobs"

private const val RESPONSES_HAS_NO_FIELD = "The Responses API has no such field."
private const val TOP_LOGPROBS_MAX = 20
