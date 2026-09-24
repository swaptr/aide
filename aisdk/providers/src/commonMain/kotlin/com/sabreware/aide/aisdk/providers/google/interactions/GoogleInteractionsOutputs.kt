package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.providers.google.GOOGLE_PROVIDER_ID
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderJson
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The content of one non-streaming response, and whether the model asked the client to run a tool. */
internal data class ParsedInteractionsOutputs(
    val content: List<Content>,
    val hasFunctionCall: Boolean,
)

/**
 * The `steps[]` of a response as content, in order.
 *
 * `model_output` yields text (with its annotations as sources) and generated files; `thought` yields one
 * reasoning part whose text is the summary items joined; `function_call` yields a tool call; the built-in
 * `*_call` / `*_result` pairs yield a provider-executed call and its result, plus whatever the result
 * cites. `user_input` steps — the server echoing the client's own input on a `GET` — are skipped.
 *
 * Every part is stamped with [interactionId] so the converter can recognise it as already held by the
 * server on the next stateful turn; a `thought` or `function_call` also carries its signature, which the
 * API demands back verbatim.
 */
@Suppress("CyclomaticComplexMethod")
internal fun parseGoogleInteractionsOutputs(
    steps: List<InteractionsStep>?,
    ids: IdGenerator,
    interactionId: String?,
): ParsedInteractionsOutputs {
    val content = mutableListOf<Content>()
    var hasFunctionCall = false
    val stamp = interactionsPartMetadata(signature = null, interactionId = interactionId)

    steps.orEmpty().forEach { step ->
        when (step.type) {
            USER_INPUT -> Unit

            MODEL_OUTPUT -> step.content.orEmpty().forEach { block ->
                when (block.type) {
                    TEXT -> {
                        content += Content.Text(block.text ?: "", stamp)
                        content += block.annotations.toSources(ids)
                    }
                    "image" -> fileContent(block.data, block.uri, block.mimeType, IMAGE_DEFAULT, stamp)?.let { content += it }
                    "video" -> fileContent(block.data, block.uri, block.mimeType, VIDEO_DEFAULT, stamp)?.let { content += it }
                    else -> Unit
                }
            }

            THOUGHT -> content += Content.Reasoning(
                text = step.summary.orEmpty().filter { it.type == TEXT }.mapNotNull { it.text }.joinToString("\n"),
                providerMetadata = interactionsPartMetadata(step.signature, interactionId),
            )

            FUNCTION_CALL -> {
                hasFunctionCall = true
                content += Content.ToolCall(
                    toolCallId = step.id ?: ids.next(),
                    toolName = step.name ?: "unknown",
                    input = step.arguments?.toString() ?: "{}",
                    providerMetadata = interactionsPartMetadata(step.signature, interactionId),
                )
            }

            // Agentic video: the model's own timeline exploration, reported as a call and its result.
            // Neither has a neutral shape, so each is a custom part whose ids and signature ride the
            // metadata — which is exactly what the prompt converter replays as the step it came from.
            PROCESSING_CALL -> content += Content.Custom(
                kind = PROCESSING_CALL_KIND,
                providerMetadata = interactionsPartMetadata(
                    step.signature,
                    interactionId,
                    processingId = step.id?.takeIf { it.isNotEmpty() } ?: ids.next(),
                ),
            )

            PROCESSING_RESULT -> content += Content.Custom(
                kind = PROCESSING_RESULT_KIND,
                providerMetadata = interactionsPartMetadata(
                    step.signature,
                    interactionId,
                    processingCallId = step.callId?.takeIf { it.isNotEmpty() } ?: ids.next(),
                ),
            )

            in BUILTIN_TOOL_CALL_TYPES -> content += Content.ToolCall(
                toolCallId = step.id?.takeIf { it.isNotEmpty() } ?: ids.next(),
                toolName = builtinToolName(step.type, step.name),
                input = step.arguments?.toString() ?: "{}",
                providerExecuted = true,
            )

            in BUILTIN_TOOL_RESULT_TYPES -> {
                content += Content.ToolResult(
                    toolCallId = step.callId?.takeIf { it.isNotEmpty() } ?: ids.next(),
                    toolName = builtinToolName(step.type, step.name),
                    output = ToolOutput.Json(step.result ?: JsonNull),
                    isError = step.isError ?: false,
                )
                content += builtinToolResultToSources(step.type, step.result, ids)
            }

            else -> Unit
        }
    }

    return ParsedInteractionsOutputs(content, hasFunctionCall)
}

/**
 * A generated file, from inline base64 or from a URL the service hosts.
 *
 * Decoded here rather than carried as base64: `FileData.Bytes` is the neutral currency, and a consumer
 * that has to know which provider encoded its bytes is not a neutral one. A block carrying neither is
 * nothing to show and is skipped.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun fileContent(
    data: String?,
    uri: String?,
    mimeType: String?,
    defaultMediaType: String,
    metadata: ProviderMetadata?,
): Content.File? = when {
    !data.isNullOrEmpty() -> Content.File(mimeType ?: defaultMediaType, FileData.Bytes(Base64.decode(data)), metadata)
    !uri.isNullOrEmpty() -> Content.File(mimeType ?: defaultMediaType, FileData.Url(uri), metadata)
    else -> null
}

/**
 * `providerMetadata.google` for one part: the signature, the interaction id and — on an agentic-video
 * part — the processing id or the id of the call it answers, whichever are known.
 */
internal fun interactionsPartMetadata(
    signature: String?,
    interactionId: String?,
    processingId: String? = null,
    processingCallId: String? = null,
): ProviderMetadata? {
    if (signature == null && interactionId == null && processingId == null && processingCallId == null) return null
    return mapOf(
        GOOGLE_PROVIDER_ID to buildJsonObject {
            signature?.let { put(GOOGLE_INTERACTIONS_SIGNATURE_KEY, it) }
            interactionId?.let { put(GOOGLE_INTERACTIONS_ID_KEY, it) }
            processingId?.let { put(GOOGLE_INTERACTIONS_PROCESSING_ID_KEY, it) }
            processingCallId?.let { put(GOOGLE_INTERACTIONS_PROCESSING_CALL_ID_KEY, it) }
        },
    )
}

/**
 * `providerMetadata.google` for the whole response — the finish part, or the generate result.
 *
 * Always present, even when empty: a consumer reads `google.interactionId` off it to chain the next
 * turn, and an absent map and an absent key should read the same way.
 */
internal fun interactionsResponseMetadata(
    interactionId: String?,
    serviceTier: String?,
    outputTokensByModality: Map<String, Int>?,
): ProviderMetadata = mapOf(
    GOOGLE_PROVIDER_ID to buildJsonObject {
        interactionId?.let { put(GOOGLE_INTERACTIONS_ID_KEY, it) }
        serviceTier?.let { put("serviceTier", it) }
        outputTokensByModality?.let { modalities ->
            put("outputTokensByModality", buildJsonObject { modalities.forEach { (key, value) -> put(key, value) } })
        }
    },
)

/**
 * The response's token counts.
 *
 * `total_output_tokens` EXCLUDES `total_thought_tokens`, so the two are added for the total and the
 * output count is the text share unchanged. `total_cached_tokens` is subtracted from the input to get
 * the full-rate share. The whole object rides `raw`, so the per-modality breakdowns and the grounding
 * counters this layer does not model are not lost.
 */
internal fun JsonObject?.toUsage(): Usage {
    if (this == null) return Usage()
    val usage = ProviderJson.decodeFromJsonElement(InteractionsUsage.serializer(), this)
    val cached = usage.totalCachedTokens ?: 0
    return Usage(
        inputTokens = Usage.InputTokens(
            total = usage.totalInputTokens,
            noCache = usage.totalInputTokens?.let { it - cached },
            cacheRead = usage.totalCachedTokens,
        ),
        outputTokens = Usage.OutputTokens(
            total = if (usage.totalOutputTokens == null && usage.totalThoughtTokens == null) {
                null
            } else {
                (usage.totalOutputTokens ?: 0) + (usage.totalThoughtTokens ?: 0)
            },
            text = usage.totalOutputTokens,
            reasoning = usage.totalThoughtTokens,
        ),
        raw = this,
    )
}

/** `output_tokens_by_modality` as a map, e.g. `{video: 57920, text: 12}`; null when unreported. */
internal fun JsonObject?.outputTokensByModality(): Map<String, Int>? {
    val usage = this?.let { ProviderJson.decodeFromJsonElement(InteractionsUsage.serializer(), it) }
    return usage?.outputTokensByModality
        ?.mapNotNull { entry -> entry.modality?.let { m -> entry.tokens?.let { m to it } } }
        ?.toMap()
        ?.takeIf { it.isNotEmpty() }
}

/**
 * The interaction's status as a finish reason.
 *
 * `requires_action` is how the API says the client owes a function result, but `completed` with a
 * `function_call` step also happens in practice, so the step list is consulted too — a runtime keyed on
 * the unified reason would otherwise stop the loop before running the tool the model asked for.
 */
internal fun interactionsFinishReason(status: String?, hasFunctionCall: Boolean): FinishReason = FinishReason(
    unified = when (status) {
        "completed" -> if (hasFunctionCall) FinishReason.Unified.ToolCalls else FinishReason.Unified.Stop
        "requires_action" -> FinishReason.Unified.ToolCalls
        "failed" -> FinishReason.Unified.Error
        "incomplete" -> FinishReason.Unified.Length
        else -> FinishReason.Unified.Other
    },
    raw = status,
)

/** An RFC 3339 `created` as epoch millis, or null for anything that does not parse. */
internal fun parseCreatedMillis(created: String?): Long? =
    created?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

internal const val IMAGE_DEFAULT: String = "image/png"
internal const val VIDEO_DEFAULT: String = "video/mp4"
