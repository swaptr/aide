package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.google.stringOrNull
import com.sabreware.aide.aisdk.util.ProviderJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One call's request body, and everything the caller asked for that could not go on it.
 *
 * The body splits by what it is addressed to. A MODEL call takes `generation_config` — the sampler
 * knobs, thinking, the tool choice — and structured output. An AGENT call takes none of that: the API
 * rejects `generation_config` outright and cannot combine an agent with a response schema, so each is
 * warned about and dropped, and the agent takes `agent_config` and an `environment` instead. Nothing
 * here throws for a mismatch, because a request the API will serve with one field fewer beats a call
 * that never leaves the process.
 */
internal fun buildInteractionsRequest(options: CallOptions, modelId: String, agent: String?): BuiltInteractionsRequest {
    val warnings = mutableListOf<Warning>()
    val google = GoogleInteractionsOptions.of(options)
    val isAgent = agent != null

    if (!isAgent) {
        if (options.frequencyPenalty != null) warnings += Warning.Unsupported(feature = "frequencyPenalty")
        if (options.presencePenalty != null) warnings += Warning.Unsupported(feature = "presencePenalty")
    }

    val prepared = options.tools?.takeIf { it.isNotEmpty() }
        ?.let { prepareGoogleInteractionsTools(it, options.toolChoice) }
    prepared?.let { warnings += it.warnings }

    val responseFormat = responseFormatEntries(options, google, isAgent, warnings)

    val converted = options.prompt.toGoogleInteractionsInput(
        previousInteractionId = google.previousInteractionId,
        store = google.store,
        mediaResolution = google.mediaResolution,
    )
    warnings += converted.warnings

    val generationConfig = if (isAgent) {
        warnAboutDroppedGenerationFields(options, google, warnings)
        null
    } else {
        applyDeprecatedImageConfig(google, responseFormat, warnings)
        generationConfig(options, google, prepared?.toolChoice, warnings)
    }

    val environment = google.environment?.let { env ->
        if (isAgent) {
            environmentBody(env)
        } else {
            warnings += Warning.Other(
                "google.interactions: environment is only supported when an agent is set; environment " +
                    "will be omitted from the request body.",
            )
            null
        }
    }

    val request = InteractionsRequest(
        model = modelId.takeIf { !isAgent },
        agent = agent,
        input = converted.input,
        systemInstruction = resolveSystemInstruction(converted.systemInstruction, google.systemInstruction, warnings),
        tools = prepared?.tools,
        responseFormat = responseFormat.takeIf { it.isNotEmpty() },
        responseModalities = google.responseModalities,
        generationConfig = generationConfig,
        agentConfig = if (isAgent) google.agentConfig?.let(::agentConfigBody) else null,
        previousInteractionId = google.previousInteractionId,
        serviceTier = google.serviceTier,
        store = google.store,
        environment = environment,
        background = google.background,
    )

    return BuiltInteractionsRequest(
        body = ProviderJson.encodeToJsonElement(InteractionsRequest.serializer(), request) as JsonObject,
        warnings = warnings,
        isAgent = isAgent,
        isBackground = google.background == true,
        pollingTimeoutMs = google.pollingTimeoutMs,
    )
}

/**
 * `response_format`, a polymorphic list assembled from two sources in order: the call-level JSON
 * format first, as a `text` entry with `application/json` and the schema, then the caller's own
 * entries re-spelled from camelCase. The schema goes through untouched — this surface takes JSON
 * Schema, not the OpenAPI dialect the classic one needs.
 */
private fun responseFormatEntries(
    options: CallOptions,
    google: GoogleInteractionsOptions,
    isAgent: Boolean,
    warnings: MutableList<Warning>,
): MutableList<JsonObject> {
    val entries = mutableListOf<JsonObject>()
    (options.responseFormat as? ResponseFormat.Json)?.let { json ->
        if (isAgent) {
            warnings += Warning.Other(
                "google.interactions: structured output (responseFormat) is not supported when an agent " +
                    "is set; responseFormat will be ignored.",
            )
        } else {
            entries += buildJsonObject {
                put("type", "text")
                put("mime_type", "application/json")
                json.schema?.let { put("schema", it) }
            }
        }
    }
    google.responseFormat?.forEach { entry ->
        when (entry.stringOrNull("type")) {
            "text" -> entries += buildJsonObject {
                put("type", "text")
                entry.valueOrNull("mimeType")?.let { put("mime_type", it) }
                entry.valueOrNull("schema")?.let { put("schema", it) }
            }
            "image" -> entries += buildJsonObject {
                put("type", "image")
                entry.valueOrNull("mimeType")?.let { put("mime_type", it) }
                entry.valueOrNull("aspectRatio")?.let { put("aspect_ratio", it) }
                entry.valueOrNull("imageSize")?.let { put("image_size", it) }
            }
            "audio" -> entries += buildJsonObject {
                put("type", "audio")
                entry.valueOrNull("mimeType")?.let { put("mime_type", it) }
            }
            "video" -> entries += buildJsonObject {
                put("type", "video")
                entry.valueOrNull("aspectRatio")?.let { put("aspect_ratio", it) }
                entry.valueOrNull("resolution")?.let { put("resolution", it) }
                entry.valueOrNull("duration")?.let { put("duration", it) }
                entry.valueOrNull("delivery")?.let { put("delivery", it) }
                entry.valueOrNull("gcsUri")?.let { put("gcs_uri", it) }
            }
            else -> Unit
        }
    }
    return entries
}

/**
 * The deprecated `imageConfig` shorthand, as the `response_format` image entry it stands for.
 *
 * It contributes only when no image entry was given the proper way, and warns either way so a caller
 * migrates; the entry it produces defaults to `image/png`, which is what the shorthand always meant.
 */
private fun applyDeprecatedImageConfig(
    google: GoogleInteractionsOptions,
    entries: MutableList<JsonObject>,
    warnings: MutableList<Warning>,
) {
    val config = google.imageConfig ?: return
    val alreadyHasImage = entries.any { it["type"] == JsonPrimitive("image") }
    warnings += Warning.Deprecated(
        setting = "providerOptions.google.imageConfig",
        message = if (alreadyHasImage) {
            "google.interactions: providerOptions.google.imageConfig is deprecated and was ignored because " +
                "providerOptions.google.responseFormat already supplies an image entry. Use responseFormat exclusively."
        } else {
            "google.interactions: providerOptions.google.imageConfig is deprecated. Use " +
                "providerOptions.google.responseFormat with a { type: \"image\", ... } entry instead."
        },
    )
    if (alreadyHasImage) return
    entries += buildJsonObject {
        put("type", "image")
        put("mime_type", "image/png")
        config.valueOrNull("aspectRatio")?.let { put("aspect_ratio", it) }
        config.valueOrNull("imageSize")?.let { put("image_size", it) }
    }
}

/**
 * `generation_config`, or null when nothing in it was set — the API takes an absent object better than
 * an empty one.
 *
 * Thinking depth comes from an explicit `thinkingLevel` first and the neutral reasoning effort second.
 * When the NEUTRAL knob asks for thinking, `thinking_summaries` is switched on with it unless the caller
 * chose: the API returns no thought text by default, so a caller who asked to see the model think would
 * otherwise get signatures and nothing readable — the same rule the classic surface applies to
 * `includeThoughts`.
 */
private fun generationConfig(
    options: CallOptions,
    google: GoogleInteractionsOptions,
    toolChoice: JsonElement?,
    warnings: MutableList<Warning>,
): InteractionsGenerationConfig? {
    val neutralLevel = if (google.thinkingLevel == null) options.reasoning.toThinkingLevel(warnings) else null
    val config = InteractionsGenerationConfig(
        temperature = options.temperature,
        topP = options.topP,
        topK = options.topK,
        seed = options.seed,
        stopSequences = options.stopSequences?.takeIf { it.isNotEmpty() },
        maxOutputTokens = options.maxOutputTokens,
        thinkingLevel = google.thinkingLevel ?: neutralLevel,
        thinkingSummaries = google.thinkingSummaries ?: "auto".takeIf { neutralLevel != null },
        toolChoice = toolChoice,
    )
    val encoded = ProviderJson.encodeToJsonElement(InteractionsGenerationConfig.serializer(), config) as JsonObject
    return config.takeIf { encoded.isNotEmpty() }
}

/**
 * The neutral effort as this surface's `thinking_level`.
 *
 * There is no level that turns thinking OFF — `minimal` is the floor — so [ReasoningEffort.None] is
 * refused with a warning rather than quietly rounded up to a level the caller did not ask for.
 */
private fun ReasoningEffort.toThinkingLevel(warnings: MutableList<Warning>): String? = when (this) {
    ReasoningEffort.ProviderDefault -> null
    ReasoningEffort.None -> {
        warnings += Warning.Unsupported(
            feature = "reasoning effort None",
            details = "The Interactions API has no thinking_level that disables thinking; minimal is the floor.",
        )
        null
    }
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    ReasoningEffort.High, ReasoningEffort.XHigh -> "high"
}

/** One warning naming every sampler and thinking field an agent call had to drop. */
@Suppress("CyclomaticComplexMethod")
private fun warnAboutDroppedGenerationFields(
    options: CallOptions,
    google: GoogleInteractionsOptions,
    warnings: MutableList<Warning>,
) {
    val dropped = buildList {
        if (options.temperature != null) add("temperature")
        if (options.topP != null) add("topP")
        if (options.topK != null) add("topK")
        if (options.frequencyPenalty != null) add("frequencyPenalty")
        if (options.presencePenalty != null) add("presencePenalty")
        if (options.seed != null) add("seed")
        if (!options.stopSequences.isNullOrEmpty()) add("stopSequences")
        if (options.maxOutputTokens != null) add("maxOutputTokens")
        if (options.reasoning != ReasoningEffort.ProviderDefault) add("reasoning")
        if (google.thinkingLevel != null) add("thinkingLevel")
        if (google.thinkingSummaries != null) add("thinkingSummaries")
        if (google.imageConfig != null) add("imageConfig")
    }
    if (dropped.isEmpty()) return
    val verb = if (dropped.size == 1) "is" else "are"
    warnings += Warning.Other(
        "google.interactions: ${dropped.joinToString(", ")} $verb not supported when an agent is set; " +
            "use providerOptions.google.agentConfig instead. Dropped from the request body.",
    )
}

/** The system message wins over the option when both are set, and says so. */
private fun resolveSystemInstruction(
    fromPrompt: String?,
    fromOptions: String?,
    warnings: MutableList<Warning>,
): String? {
    if (fromPrompt != null && fromOptions != null) {
        warnings += Warning.Other(
            "google.interactions: both AI SDK system message and providerOptions.google.systemInstruction " +
                "were set; using the AI SDK system message.",
        )
    }
    return fromPrompt ?: fromOptions
}

/** `agent_config`: the deep-research knobs re-spelled to snake_case, or the bare `dynamic` type. */
private fun agentConfigBody(config: JsonObject): JsonObject? = when (config.stringOrNull("type")) {
    "deep-research" -> buildJsonObject {
        put("type", "deep-research")
        config.valueOrNull("thinkingSummaries")?.let { put("thinking_summaries", it) }
        config.valueOrNull("visualization")?.let { put("visualization", it) }
        config.valueOrNull("collaborativePlanning")?.let { put("collaborative_planning", it) }
    }
    "dynamic" -> buildJsonObject { put("type", "dynamic") }
    else -> null
}

/**
 * The agent's sandbox: a string passes through, the object form is rebuilt field by field so only the
 * keys the API documents go out — an `inline` source has `content` and `target`, the others `source`
 * and an optional `target`, and the network is `disabled` or an allow-list with per-domain transforms.
 */
private fun environmentBody(environment: JsonElement): JsonElement {
    val obj = environment as? JsonObject ?: return environment
    return buildJsonObject {
        put("type", "remote")
        (obj["sources"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.map { source ->
                buildJsonObject {
                    source.valueOrNull("type")?.let { put("type", it) }
                    if (source.stringOrNull("type") == "inline") {
                        source.valueOrNull("content")?.let { put("content", it) }
                        source.valueOrNull("target")?.let { put("target", it) }
                    } else {
                        source.valueOrNull("source")?.let { put("source", it) }
                        source.valueOrNull("target")?.let { put("target", it) }
                    }
                }
            }
            ?.takeIf { it.isNotEmpty() }
            ?.let { put("sources", JsonArray(it)) }
        when (val network = obj["network"]) {
            is JsonObject -> put(
                "network",
                buildJsonObject {
                    val allowlist = (network["allowlist"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                    put(
                        "allowlist",
                        JsonArray(
                            allowlist.map { entry ->
                                buildJsonObject {
                                    entry.valueOrNull("domain")?.let { put("domain", it) }
                                    entry.valueOrNull("transform")?.let { put("transform", it) }
                                }
                            },
                        ),
                    )
                },
            )
            is JsonPrimitive -> if (network.isString && network.content == "disabled") put("network", "disabled")
            else -> Unit
        }
    }
}

/** A value that is present and not JSON null — the caller's "unset" spelled either way. */
private fun JsonObject.valueOrNull(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }
