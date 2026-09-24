package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ToolNameMapping
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One Anthropic request, plus everything the response half needs to interpret its answer.
 *
 * [toolNames] and [usesJsonResponseTool] travel with the body because they are decisions made here that
 * the stream mapper cannot re-derive: the vendor calls its search tool `web_search` whatever the client
 * named it, and a structured-output turn arrives as a tool call that has to be read back as text.
 */
internal data class BuiltAnthropicRequest(
    val body: JsonObject,
    val warnings: List<Warning>,
    /** Betas every part of the request asked for, merged into ONE `anthropic-beta` header by the model. */
    val betas: Set<String>,
    val toolNames: ToolNameMapping,
    val usesJsonResponseTool: Boolean,
    /**
     * Whether a `code_execution` call must be accepted although no such tool was offered — see
     * [hasDynamicFilteringWebToolWithoutCodeExecution]. Decided here, acted on by the stream mapper.
     */
    val markCodeExecutionDynamic: Boolean = false,
)

/**
 * Builds an Anthropic Messages request from the neutral call options.
 *
 * Shared with Bedrock and Vertex, which speak the same body over a different transport. Keeping one
 * builder means the thinking-mode table, the tool-choice restrictions under extended thinking and the
 * per-model token ceilings are decided once — rules that are individually easy to get wrong and each of
 * which fails as a 400 that names none of them.
 */
internal object AnthropicRequestBuilder {

    /**
     * @param supportsNativeStructuredOutput false where the host cannot pass the structured-output beta
     *   through — Vertex, notably — so `responseFormat` falls back to the JSON tool instead of 400ing.
     * @param optionsNamespace the hosting provider's id (`amazon-bedrock`, `google-vertex`,
     *   `minimax`), read as a second `providerOptions` key merged over `anthropic`. See
     *   [AnthropicOptions.of].
     */
    fun build(
        modelId: String,
        options: CallOptions,
        supportsNativeStructuredOutput: Boolean = true,
        optionsNamespace: String? = null,
    ): BuiltAnthropicRequest {
        val warnings = mutableListOf<Warning>()
        val betas = mutableSetOf<String>()
        val caps = anthropicModelCapabilities(modelId)
        val anthropicOptions = AnthropicOptions.of(options, optionsNamespace)
        betas += anthropicOptions.betas()

        val resolved = resolveThinking(modelId, caps, options.reasoning, options.maxOutputTokens, anthropicOptions, warnings)
        betas += resolved.betas
        val samplers = resolveSamplers(modelId, caps, options, resolved, warnings)

        val cacheControls = AnthropicCacheControlBudget()
        val toolNames = ToolNameMapping.from(options.tools, anthropicProviderToolNames)

        // Structured output has two implementations and the model decides which. `output_config.format`
        // constrains the decoder directly; where the model or the host cannot do that, the schema is
        // offered as a single tool the model is forced to call and whose input IS the answer.
        val schema = (options.responseFormat as? ResponseFormat.Json)?.schema
        val nativeStructuredOutput = schema != null &&
            useNativeStructuredOutput(anthropicOptions, caps, supportsNativeStructuredOutput)
        val jsonTool = schema?.takeIf { !nativeStructuredOutput }?.let { jsonResponseTool(it) }
        if (nativeStructuredOutput) betas += "structured-outputs-2025-11-13"

        // `tool_choice: none` is expressed by sending no tools: Anthropic has no "none" choice, and a
        // tools array with no way to decline it is not what the caller asked for.
        val offered = if (options.toolChoice is ToolChoice.None && jsonTool == null) {
            emptyList()
        } else {
            options.tools.orEmpty() + listOfNotNull(jsonTool)
        }
        // The builder always streams (doGenerate folds the stream), so the reference's `stream &&`
        // guard on the eager-streaming default collapses to the option alone.
        val prepared = prepareAnthropicTools(
            tools = offered,
            cacheControls = cacheControls,
            defaultEagerInputStreaming = anthropicOptions.toolStreaming,
        )
        betas += prepared.betas
        warnings += prepared.warnings
        // Only sent where it does something: a 4.5-era model with tools, which otherwise thinks once at
        // the start of the turn and never reasons about a tool result.
        if (resolved.interleavedBeta && resolved.thinkingActive && prepared.tools != null) {
            betas += ANTHROPIC_INTERLEAVED_THINKING_BETA
        }

        val promptContext = AnthropicPromptContext(
            warnings = warnings,
            cacheControls = cacheControls,
            toolNames = toolNames,
            sendReasoning = anthropicOptions.sendReasoning,
            betas = betas,
        )
        val conversation = options.prompt.toAnthropic(promptContext)

        val request = AnthropicChatRequest(
            model = modelId,
            maxTokens = resolved.maxTokens,
            messages = conversation.messages,
            system = conversation.system,
            tools = prepared.tools,
            toolChoice = resolveToolChoice(options, anthropicOptions, resolved, prepared, jsonTool != null, warnings),
            thinking = resolved.thinking,
            temperature = samplers.temperature,
            topP = samplers.topP,
            topK = samplers.topK,
            stopSequences = options.stopSequences?.takeIf { it.isNotEmpty() },
            outputConfig = outputConfig(resolved, anthropicOptions, schema.takeIf { nativeStructuredOutput }),
            stream = true,
        )

        if (options.seed != null) warnings += Warning.Unsupported("seed")
        if (options.presencePenalty != null) warnings += Warning.Unsupported("presencePenalty")
        if (options.frequencyPenalty != null) warnings += Warning.Unsupported("frequencyPenalty")

        val body = ProviderJson.encodeToJsonElement(AnthropicChatRequest.serializer(), request) as JsonObject
        return BuiltAnthropicRequest(
            body = JsonObject(body + anthropicOptions.extraBody()),
            warnings = warnings,
            betas = betas,
            toolNames = toolNames,
            usesJsonResponseTool = jsonTool != null,
            markCodeExecutionDynamic = hasDynamicFilteringWebToolWithoutCodeExecution(prepared.tools),
        )
    }

    /**
     * The body a HOSTED Anthropic deployment wants: Bedrock or Vertex.
     *
     * Both identify the model in the URL and reject a body that also names one, and both replace it with
     * an `anthropic_version` naming the host's own contract rather than the model. They differ on
     * [includeStream] — Bedrock's endpoint implies streaming and rejects the field, Vertex's
     * `streamRawPredict` requires it — which is exactly the kind of one-word difference that is cheaper
     * to state here than to rediscover from a 400.
     *
     * Betas move into the body as `anthropic_beta`. Neither host forwards the `anthropic-beta` header to
     * Anthropic, so a tool that needs one — computer use, code execution, memory — would otherwise be
     * sent and then rejected for a header the caller never had a way to set.
     */
    fun buildHosted(
        modelId: String,
        options: CallOptions,
        anthropicVersion: String,
        includeStream: Boolean,
        supportsNativeStructuredOutput: Boolean = true,
        optionsNamespace: String? = null,
        /**
         * A host-specific rewrite of the finished tools array, run BEFORE betas are serialized so any
         * beta the rewrite adds still makes it into `anthropic_beta`. Bedrock is the consumer: it
         * requires newer tool versions than the direct API and spells their betas differently.
         */
        rewriteTools: ((tools: JsonArray, betas: MutableSet<String>) -> JsonArray)? = null,
    ): BuiltAnthropicRequest {
        val built = build(modelId, options, supportsNativeStructuredOutput, optionsNamespace)
        val betas = built.betas.toMutableSet()
        val rewrittenTools = rewriteTools?.let { rewrite ->
            (built.body["tools"] as? JsonArray)?.let { rewrite(it, betas) }
        }
        val rewritten = buildJsonObject {
            built.body.forEach { (key, value) ->
                when {
                    key == "model" -> Unit
                    key == "stream" && !includeStream -> Unit
                    key == "tools" && rewrittenTools != null -> put(key, rewrittenTools)
                    else -> put(key, value)
                }
            }
            put("anthropic_version", anthropicVersion)
            if (betas.isNotEmpty()) {
                put("anthropic_beta", JsonArray(betas.map(::JsonPrimitive)))
            }
        }
        return built.copy(body = rewritten, betas = emptySet())
    }

    /**
     * Which sampler parameters survive, and why the others did not.
     *
     * Two independent rules, both of which are a hard 400 rather than a degradation. Anthropic rejects
     * `temperature`, `top_p` and `top_k` outright on the 4.7-and-later families, and rejects all three
     * again on ANY model while thinking is enabled. This code used to send them unconditionally and warn
     * about `seed` instead — which is to say it warned about the one parameter that costs nothing and
     * stayed silent on the three that end the request.
     */
    private fun resolveSamplers(
        modelId: String,
        caps: AnthropicModelCapabilities,
        options: CallOptions,
        resolved: ResolvedThinking,
        warnings: MutableList<Warning>,
    ): Samplers {
        var temperature = options.temperature
        var topP = options.topP
        var topK = options.topK

        fun drop(feature: String, reason: String) {
            warnings += Warning.Unsupported(feature = feature, details = reason)
        }

        if (caps.rejectsSamplingParameters) {
            val reason = { name: String -> "$name is not supported by $modelId and will be ignored" }
            if (temperature != null) { drop("temperature", reason("temperature")); temperature = null }
            if (topK != null) { drop("topK", reason("topK")); topK = null }
            if (topP != null) { drop("topP", reason("topP")); topP = null }
        }

        if (resolved.thinkingActive) {
            val reason = { name: String -> "$name is not supported when thinking is enabled" }
            if (temperature != null) { drop("temperature", reason("temperature")); temperature = null }
            if (topK != null) { drop("topK", reason("topK")); topK = null }
            if (topP != null) { drop("topP", reason("topP")); topP = null }
        } else if (topP != null && temperature != null && "claude-" in modelId) {
            // Anthropic documents the two as mutually exclusive. The check is scoped to Claude ids
            // because a vendor serving an Anthropic-shaped API — MiniMax — may require both.
            drop("topP", "topP is not supported when temperature is set. topP is ignored.")
            topP = null
        }

        return Samplers(temperature, topP, topK)
    }

    private data class Samplers(val temperature: Double?, val topP: Double?, val topK: Int?)

    private fun useNativeStructuredOutput(
        options: AnthropicOptions,
        caps: AnthropicModelCapabilities,
        hostSupports: Boolean,
    ): Boolean = when (options.structuredOutputMode) {
        "outputFormat" -> true
        "jsonTool" -> false
        else -> hostSupports && caps.supportsStructuredOutput
    }

    /**
     * The fallback for a model with no constrained decoder: one tool whose input is the answer.
     *
     * The model is then forced to call it, and the mapper reads the tool input back out as text — so a
     * caller asking for JSON gets JSON either way, and never has to know which mechanism served it.
     */
    private fun jsonResponseTool(schema: JsonObject) = Tool.Function(
        name = JSON_RESPONSE_TOOL,
        description = "Respond with a JSON object.",
        inputSchema = schema,
    )

    private fun outputConfig(
        resolved: ResolvedThinking,
        options: AnthropicOptions,
        structuredSchema: JsonObject?,
    ): JsonObject? {
        val config = buildJsonObject {
            resolved.effort?.let { put("effort", it) }
            options.taskBudget?.let { put("task_budget", it.snakeCasedKeys()) }
            structuredSchema?.let {
                put(
                    "format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put("schema", sanitizeAnthropicJsonSchema(it))
                    },
                )
            }
        }
        return config.takeIf { it.isNotEmpty() }
    }

    /**
     * `tool_choice`, and the two cases where the caller's choice cannot be honoured.
     *
     * Manual extended thinking permits only auto/none — forcing `any` or a named tool is a 400 — and a
     * structured-output turn has to force the JSON tool or the model may answer in prose instead.
     */
    private fun resolveToolChoice(
        options: CallOptions,
        anthropicOptions: AnthropicOptions,
        resolved: ResolvedThinking,
        prepared: PreparedTools,
        usesJsonResponseTool: Boolean,
        warnings: MutableList<Warning>,
    ): JsonObject? {
        if (prepared.tools == null) return null
        if (usesJsonResponseTool) {
            return buildJsonObject {
                put("type", "tool")
                put("name", JSON_RESPONSE_TOOL)
                // Parallel calls would produce two candidate answers; the caller asked for one object.
                put("disable_parallel_tool_use", true)
            }
        }

        val choice = options.toolChoice
        val forcedChoiceBlocked = resolved.extended && resolved.thinking != null
        if (forcedChoiceBlocked && choice != null && choice !is ToolChoice.Auto && choice !is ToolChoice.None) {
            warnings += Warning.Compatibility(
                feature = "toolChoice",
                details = "Extended thinking permits only auto/none; the forced choice was dropped.",
            )
            return ToolChoice.Auto.toAnthropicToolChoice(anthropicOptions.disableParallelToolUse)
        }
        return (choice ?: ToolChoice.Auto).toAnthropicToolChoice(anthropicOptions.disableParallelToolUse)
    }
}

/** The tool a structured-output turn is served through when the model has no constrained decoder. */
internal const val JSON_RESPONSE_TOOL: String = "json"

/**
 * Whether a `code_execution` call the model makes must be accepted although no such tool was offered.
 *
 * The dynamic-filtering web tools — the 2026-02-09 and 2026-03-18 versions of web fetch and web search —
 * run code over their results before the results enter the context window, and to do that the API
 * provisions a code-execution tool implicitly. Its `server_tool_use` blocks then arrive under a name the
 * caller never declared, and a runtime that validates calls against the offered tools would refuse them.
 * Marking those calls `dynamic` is how the reference bypasses that check. An explicitly declared
 * code-execution tool makes the mark unnecessary, since the call then matches a real tool.
 *
 * `code_execution_20260521` is this port's vendor-documented addition and counts as an explicit
 * declaration for the same reason the three the reference lists do.
 */
internal fun hasDynamicFilteringWebToolWithoutCodeExecution(tools: List<JsonObject>?): Boolean {
    if (tools == null) return false
    var hasDynamicFilteringWebTool = false
    var hasCodeExecutionTool = false
    for (tool in tools) {
        val type = tool["type"]?.stringOrNull() ?: continue
        if (type in DYNAMIC_FILTERING_WEB_TOOL_TYPES) {
            hasDynamicFilteringWebTool = true
            continue
        }
        if (type in CODE_EXECUTION_TOOL_TYPES) {
            hasCodeExecutionTool = true
            break
        }
    }
    return hasDynamicFilteringWebTool && !hasCodeExecutionTool
}

private val DYNAMIC_FILTERING_WEB_TOOL_TYPES = setOf(
    "web_fetch_20260209", "web_fetch_20260318", "web_search_20260209", "web_search_20260318",
)

private val CODE_EXECUTION_TOOL_TYPES = setOf(
    "code_execution_20250522", "code_execution_20250825", "code_execution_20260120", "code_execution_20260521",
)
