package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.ProviderMetadata
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a finished Anthropic turn knew about itself, accumulated across the stream.
 *
 * This is the payload that makes multi-step code execution work at all: the container id arrives on
 * `message_start`/`message_delta` and a next step that wants the same container has to send it back —
 * a runtime that never surfaces it leaves every step allocating a fresh container and losing the
 * files the previous one wrote. `stop_details`, per-iteration usage and the applied context edits
 * ride along for the same reason: they exist only here, and a caller that cannot see them cannot act
 * on them.
 */
internal class AnthropicFinishMetadata {

    var usage: AnthropicUsage? = null
    var stopSequence: String? = null
    var stopDetails: AnthropicStopDetails? = null
    var container: AnthropicContainer? = null
    var iterations: List<AnthropicUsageIteration>? = null
    var contextManagement: AnthropicContextManagement? = null
    var inputTransformations: List<AnthropicInputTransformation>? = null

    /** message_start: seed the usage and the (skill-less) container. */
    fun onMessageStart(message: AnthropicStreamMessage) {
        usage = message.usage
        container = message.container
        message.inputTransformations?.let { inputTransformations = it }
    }

    /** message_delta: merge usage, overwrite the container, capture the stop and edit details. */
    fun onMessageDelta(event: AnthropicStreamEvent) {
        event.usage?.let { delta -> usage = usage.mergedWith(delta) }
        event.usage?.iterations?.let { iterations = it }
        // Overwritten rather than merged, matching the reference: the delta's container is the
        // complete, current value (now including skills), not a patch.
        event.delta?.container?.let { container = it }
        event.delta?.stopSequence?.let { stopSequence = it }
        event.delta?.stopDetails?.let { stopDetails = it }
        event.contextManagement?.let { contextManagement = it }
        event.inputTransformations?.let { inputTransformations = it }
    }

    /**
     * The finish part's `providerMetadata["anthropic"]`, camelCase keys over the snake wire.
     *
     * Nullable members are emitted as explicit JSON nulls where the reference does (`stopSequence`,
     * `iterations`, `container`, `contextManagement`) and omitted where it omits (`stopDetails`,
     * `inputTransformations`), so a consumer ported against the reference reads the same shape.
     */
    fun toProviderMetadata(): ProviderMetadata = mapOf(
        ANTHROPIC_PROVIDER_ID to buildJsonObject {
            put("usage", usage.toMetadataJson())
            stopSequence.let { if (it == null) put("stopSequence", JsonNull) else put("stopSequence", it) }
            stopDetails?.let { details ->
                put(
                    "stopDetails",
                    buildJsonObject {
                        details.type?.let { put("type", it) }
                        details.category?.let { put("category", it) }
                        details.explanation?.let { put("explanation", it) }
                        details.recommendedModel?.let { put("recommendedModel", it) }
                    },
                )
            }
            inputTransformations?.let { transformations ->
                put(
                    "inputTransformations",
                    buildJsonArray {
                        transformations.forEach { transformation ->
                            add(
                                buildJsonObject {
                                    transformation.type?.let { put("type", it) }
                                    transformation.path?.let { put("path", it) }
                                    transformation.reason?.let { put("reason", it) }
                                },
                            )
                        }
                    },
                )
            }
            put("iterations", iterations.toMetadataJson())
            put("container", container.toMetadataJson())
            put("contextManagement", contextManagement.toMetadataJson())
        },
    )
}

private fun AnthropicUsage?.mergedWith(delta: AnthropicUsage): AnthropicUsage = AnthropicUsage(
    inputTokens = delta.inputTokens ?: this?.inputTokens,
    outputTokens = delta.outputTokens ?: this?.outputTokens,
    cacheCreationInputTokens = delta.cacheCreationInputTokens ?: this?.cacheCreationInputTokens,
    cacheReadInputTokens = delta.cacheReadInputTokens ?: this?.cacheReadInputTokens,
    outputTokensDetails = delta.outputTokensDetails ?: this?.outputTokensDetails,
    iterations = delta.iterations ?: this?.iterations,
)

private fun AnthropicUsage?.toMetadataJson() = if (this == null) {
    JsonNull
} else {
    buildJsonObject {
        inputTokens?.let { put("input_tokens", it) }
        outputTokens?.let { put("output_tokens", it) }
        cacheCreationInputTokens?.let { put("cache_creation_input_tokens", it) }
        cacheReadInputTokens?.let { put("cache_read_input_tokens", it) }
        outputTokensDetails?.thinkingTokens?.let {
            put("output_tokens_details", buildJsonObject { put("thinking_tokens", it) })
        }
    }
}

private fun List<AnthropicUsageIteration>?.toMetadataJson() = if (this == null) {
    JsonNull
} else {
    buildJsonArray {
        forEach { iteration ->
            add(
                buildJsonObject {
                    iteration.type?.let { put("type", it) }
                    iteration.model?.let { put("model", it) }
                    put("inputTokens", iteration.inputTokens ?: 0)
                    put("outputTokens", iteration.outputTokens ?: 0)
                    // Zero omitted, matching the reference's truthiness check.
                    iteration.cacheCreationInputTokens?.takeIf { it != 0 }
                        ?.let { put("cacheCreationInputTokens", it) }
                    iteration.cacheReadInputTokens?.takeIf { it != 0 }
                        ?.let { put("cacheReadInputTokens", it) }
                },
            )
        }
    }
}

private fun AnthropicContainer?.toMetadataJson() = if (this == null) {
    JsonNull
} else {
    buildJsonObject {
        put("expiresAt", expiresAt)
        put("id", id)
        val skillList = skills
        if (skillList == null) {
            put("skills", JsonNull)
        } else {
            put(
                "skills",
                buildJsonArray {
                    skillList.forEach { skill ->
                        add(
                            buildJsonObject {
                                skill.type?.let { put("type", it) }
                                skill.skillId?.let { put("skillId", it) }
                                skill.version?.let { put("version", it) }
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * Each applied edit keeps only the fields its type defines, renamed to camelCase; an edit type this
 * port has never heard of is forwarded with its type alone rather than dropped — a new edit kind
 * should be visible, even if unreadable.
 */
private fun AnthropicContextManagement?.toMetadataJson() = if (this == null) {
    JsonNull
} else {
    buildJsonObject {
        put(
            "appliedEdits",
            buildJsonArray {
                appliedEdits.forEach { edit ->
                    val type = edit.stringOrNull("type") ?: return@forEach
                    add(
                        buildJsonObject {
                            put("type", type)
                            when (type) {
                                "clear_tool_uses_20250919" -> {
                                    edit["cleared_tool_uses"]?.let { put("clearedToolUses", it) }
                                    edit["cleared_input_tokens"]?.let { put("clearedInputTokens", it) }
                                }
                                "clear_thinking_20251015" -> {
                                    edit["cleared_thinking_turns"]?.let { put("clearedThinkingTurns", it) }
                                    edit["cleared_input_tokens"]?.let { put("clearedInputTokens", it) }
                                }
                                else -> Unit
                            }
                        },
                    )
                }
            },
        )
    }
}
