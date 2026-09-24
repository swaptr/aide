package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.providers.google.GOOGLE_PROVIDER_ID
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.IdGenerator
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The open steps of one Interactions stream, and what each has accumulated.
 *
 * The API frames concurrent steps by `index` — a thought at 0 while text streams at 1 — so each index
 * is its own slot, and a delta at one index never touches a block at another. A `model_output` step
 * usually opens BARE (`{"type":"model_output"}`, no content): whether it is text or an image is only
 * known from its first delta, so it waits in [OpenBlock.PendingModelOutput] and is promoted then, and a
 * text block stays open across an image delta at the same index because the two interleave.
 *
 * Function-call arguments stream as `arguments_delta` fragments that CONCATENATE; the full input is
 * emitted at `step.stop`. A built-in tool's call and result arrive whole on their deltas and are
 * emitted at their own `step.stop`. Sources are de-duplicated across the whole stream, because a
 * citation re-appears on every `text_annotation` delta as the text grows and a search result names the
 * same page the citation does.
 */
internal class GoogleInteractionsStreamState(
    private val ids: IdGenerator,
    private val url: String,
) {

    /** The `x-gemini-service-tier` header, a fallback the body's own `service_tier` wins over. */
    var headerServiceTier: String? = null

    private var interactionId: String? = null
    private var usage: JsonObject? = null
    private var serviceTier: String? = null
    private var finishStatus: String? = null
    private var hasFunctionCall = false
    private val open = mutableMapOf<Int, OpenBlock>()
    private val emittedSources = mutableSetOf<String>()

    /** A frame that did not parse: the stream is failed, whatever status the server reports later. */
    fun markFailed() {
        finishStatus = "failed"
    }

    suspend fun handle(collector: FlowCollector<StreamPart>, event: InteractionsEvent, raw: JsonElement) {
        when (event.eventType) {
            "interaction.created" -> collector.onCreated(event)
            "step.start" -> collector.onStepStart(event)
            "step.delta" -> collector.onStepDelta(event)
            "step.stop" -> collector.onStepStop(event)
            "interaction.status_update", "interaction.in_progress", "interaction.requires_action" ->
                finishStatus = event.status ?: if (event.eventType == "interaction.requires_action") {
                    "requires_action"
                } else {
                    "in_progress"
                }
            "interaction.completed" -> onCompleted(event)
            "error" -> collector.onError(event, raw)
            else -> Unit
        }
    }

    suspend fun finish(collector: FlowCollector<StreamPart>) {
        collector.emit(
            StreamPart.Finish(
                usage = usage.toUsage(),
                finishReason = interactionsFinishReason(finishStatus, hasFunctionCall),
                providerMetadata = interactionsResponseMetadata(
                    interactionId = interactionId,
                    serviceTier = serviceTier ?: headerServiceTier,
                    outputTokensByModality = usage.outputTokensByModality(),
                ),
            ),
        )
    }

    private suspend fun FlowCollector<StreamPart>.onCreated(event: InteractionsEvent) {
        val interaction = event.interaction
        // `id` is an EMPTY string on a `store: false` call; an empty id must not reach metadata.
        interactionId = interaction?.id?.takeIf { it.isNotEmpty() }
        emit(
            StreamPart.ResponseMetadataPart(
                ResponseMetadata(
                    id = interactionId,
                    timestamp = parseCreatedMillis(interaction?.created),
                    modelId = interaction?.model,
                ),
            ),
        )
    }

    private suspend fun FlowCollector<StreamPart>.onStepStart(event: InteractionsEvent) {
        val index = event.index ?: return
        val blockId = "${interactionId ?: "interaction"}:$index"
        val step = event.step
        val type = step?.type
        open[index] = when {
            type == MODEL_OUTPUT -> openModelOutput(blockId, step.content?.firstOrNull())

            type == THOUGHT -> OpenBlock.Reasoning(blockId).also { block ->
                block.signature = step.signature
                emit(StreamPart.ReasoningStart(blockId))
                // The initial summary may already hold text; the consumer's buffer must not miss it.
                step.summary.orEmpty().forEach { item ->
                    if (item.type == TEXT && item.text != null) emit(StreamPart.ReasoningDelta(blockId, item.text))
                }
            }

            type == FUNCTION_CALL -> {
                hasFunctionCall = true
                val toolCallId = step.id?.takeIf { it.isNotEmpty() } ?: blockId
                val toolName = step.name ?: "unknown"
                emit(StreamPart.ToolInputStart(toolCallId, toolName))
                OpenBlock.FunctionCall(blockId, toolCallId, toolName).also { it.signature = step.signature }
            }

            type in BUILTIN_TOOL_CALL_TYPES -> OpenBlock.BuiltinCall(
                id = blockId,
                stepType = type!!,
                toolCallId = step.id?.takeIf { it.isNotEmpty() } ?: blockId,
                toolName = builtinToolName(type, step.name),
                arguments = step.arguments,
            )

            type in BUILTIN_TOOL_RESULT_TYPES -> OpenBlock.BuiltinResult(
                id = blockId,
                stepType = type!!,
                callId = step.callId?.takeIf { it.isNotEmpty() } ?: blockId,
                toolName = builtinToolName(type, step.name),
                result = step.result,
                isError = step.isError,
            )

            // Agentic video's call and result: custom parts, with the ids and signature in the metadata.
            type == PROCESSING_CALL || type == PROCESSING_RESULT -> OpenBlock.Custom(blockId, type).also { block ->
                step.signature?.let { block.google[GOOGLE_INTERACTIONS_SIGNATURE_KEY] = it }
                interactionId?.let { block.google[GOOGLE_INTERACTIONS_ID_KEY] = it }
                if (type == PROCESSING_CALL) {
                    block.google[GOOGLE_INTERACTIONS_PROCESSING_ID_KEY] = step.id?.takeIf { it.isNotEmpty() } ?: blockId
                } else {
                    block.google[GOOGLE_INTERACTIONS_PROCESSING_CALL_ID_KEY] =
                        step.callId?.takeIf { it.isNotEmpty() } ?: blockId
                }
            }

            else -> OpenBlock.Unknown(blockId)
        }
    }

    /** A `model_output` opens as text or image when its first block says which, and waits otherwise. */
    private suspend fun FlowCollector<StreamPart>.openModelOutput(blockId: String, initial: InteractionsContent?): OpenBlock =
        when (initial?.type) {
            TEXT -> OpenBlock.Text(blockId).also {
                emit(StreamPart.TextStart(blockId))
                emitSources(initial.annotations.toSources(ids))
            }
            "image" -> OpenBlock.Image(blockId).also {
                it.data = initial.data
                it.mimeType = initial.mimeType
                it.uri = initial.uri
            }
            else -> OpenBlock.PendingModelOutput(blockId)
        }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun FlowCollector<StreamPart>.onStepDelta(event: InteractionsEvent) {
        val index = event.index ?: return
        val delta = event.delta ?: return
        var block = open[index] ?: return
        val type = delta.type

        if (block is OpenBlock.PendingModelOutput && (type == TEXT || type in TEXT_ANNOTATION_TYPES)) {
            block = OpenBlock.Text(block.id).also { open[index] = it }
            emit(StreamPart.TextStart(block.id))
        }

        // A generated file arrives whole on one delta. It is emitted where it lands rather than held for
        // `step.stop`, because a text block open at the same index would otherwise swallow it.
        if (type == "image" && (block is OpenBlock.PendingModelOutput || block is OpenBlock.Text || block is OpenBlock.Image)) {
            fileContent(delta.data, delta.uri, delta.mimeType, IMAGE_DEFAULT, stamp())?.let { emit(StreamPart.FilePart(it)) }
            // Emitted here; an eagerly-opened image block must not emit it again on stop.
            if (block is OpenBlock.Image) {
                block.data = null
                block.uri = null
            }
            return
        }
        if (type == "video" && (block is OpenBlock.PendingModelOutput || block is OpenBlock.Text)) {
            fileContent(delta.data, delta.uri, delta.mimeType, VIDEO_DEFAULT, stamp())?.let { emit(StreamPart.FilePart(it)) }
            return
        }

        when {
            block is OpenBlock.Text && type == TEXT ->
                delta.text?.takeIf { it.isNotEmpty() }?.let { emit(StreamPart.TextDelta(block.id, it)) }

            block is OpenBlock.Text && type in TEXT_ANNOTATION_TYPES -> emitSources(delta.annotations.toSources(ids))

            block is OpenBlock.Reasoning -> when (type) {
                "thought_summary" -> delta.content?.takeIf { it.type == TEXT }?.text
                    ?.let { emit(StreamPart.ReasoningDelta(block.id, it)) }
                "thought_signature" -> delta.signature?.let { block.signature = it }
                else -> Unit
            }

            block is OpenBlock.FunctionCall && type == "arguments_delta" -> {
                // The fragment is the STRING under `arguments`; the discriminator alone says `_delta`.
                val slice = (delta.arguments as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                if (slice.isNotEmpty()) {
                    block.arguments.append(slice)
                    emit(StreamPart.ToolInputDelta(block.toolCallId, slice))
                }
                delta.id?.takeIf { it.isNotEmpty() }?.let { block.toolCallId = it }
                delta.signature?.let { block.signature = it }
                hasFunctionCall = true
            }

            block is OpenBlock.BuiltinCall && type == block.stepType -> {
                delta.id?.takeIf { it.isNotEmpty() }?.let { block.toolCallId = it }
                (delta.arguments as? JsonObject)?.let { block.arguments = it }
                if (delta.name != null && block.stepType == "mcp_server_tool_call") block.toolName = delta.name
            }

            block is OpenBlock.BuiltinResult && type == block.stepType -> {
                delta.callId?.takeIf { it.isNotEmpty() }?.let { block.callId = it }
                delta.result?.let { block.result = it }
                delta.isError?.let { block.isError = it }
                if (delta.name != null && block.stepType == "mcp_server_tool_result") block.toolName = delta.name
            }

            block is OpenBlock.Custom && type == block.stepType -> {
                delta.signature?.let { block.google[GOOGLE_INTERACTIONS_SIGNATURE_KEY] = it }
                if (type == PROCESSING_CALL) {
                    delta.id?.takeIf { it.isNotEmpty() }?.let { block.google[GOOGLE_INTERACTIONS_PROCESSING_ID_KEY] = it }
                } else {
                    delta.callId?.takeIf { it.isNotEmpty() }
                        ?.let { block.google[GOOGLE_INTERACTIONS_PROCESSING_CALL_ID_KEY] = it }
                }
            }
        }
    }

    private suspend fun FlowCollector<StreamPart>.onStepStop(event: InteractionsEvent) {
        val index = event.index ?: return
        val block = open.remove(index) ?: return
        when (block) {
            is OpenBlock.Text -> emit(StreamPart.TextEnd(block.id, stamp()))

            is OpenBlock.Reasoning ->
                emit(StreamPart.ReasoningEnd(block.id, interactionsPartMetadata(block.signature, interactionId)))

            is OpenBlock.Image ->
                fileContent(block.data, block.uri, block.mimeType, IMAGE_DEFAULT, stamp())?.let { emit(StreamPart.FilePart(it)) }

            is OpenBlock.FunctionCall -> {
                emit(StreamPart.ToolInputEnd(block.toolCallId))
                emit(
                    StreamPart.ToolCallPart(
                        Content.ToolCall(
                            toolCallId = block.toolCallId,
                            toolName = block.toolName,
                            // An empty-argument call still needs valid JSON to replay.
                            input = block.arguments.toString().ifEmpty { "{}" },
                            providerMetadata = interactionsPartMetadata(block.signature, interactionId),
                        ),
                    ),
                )
            }

            is OpenBlock.BuiltinCall -> if (!block.emitted) {
                block.emitted = true
                emit(
                    StreamPart.ToolCallPart(
                        Content.ToolCall(
                            toolCallId = block.toolCallId,
                            toolName = block.toolName,
                            input = block.arguments?.toString() ?: "{}",
                            providerExecuted = true,
                        ),
                    ),
                )
            }

            is OpenBlock.BuiltinResult -> if (!block.emitted) {
                block.emitted = true
                emit(
                    StreamPart.ToolResultPart(
                        Content.ToolResult(
                            toolCallId = block.callId,
                            toolName = block.toolName,
                            output = ToolOutput.Json(block.result ?: JsonNull),
                            isError = block.isError ?: false,
                        ),
                    ),
                )
                emitSources(builtinToolResultToSources(block.stepType, block.result, ids))
            }

            is OpenBlock.Custom -> emit(
                StreamPart.CustomPart(
                    Content.Custom(
                        kind = "$GOOGLE_PROVIDER_ID.${block.stepType}",
                        providerMetadata = mapOf(
                            GOOGLE_PROVIDER_ID to buildJsonObject { block.google.forEach { (key, value) -> put(key, value) } },
                        ),
                    ),
                ),
            )

            is OpenBlock.PendingModelOutput, is OpenBlock.Unknown -> Unit
        }
    }

    private fun onCompleted(event: InteractionsEvent) {
        val interaction = event.interaction ?: return
        interaction.id?.takeIf { it.isNotEmpty() }?.let { interactionId = it }
        interaction.status?.let { finishStatus = it }
        interaction.usage?.let { usage = it }
        // The body's tier wins over the header fallback: this surface reports it here, not in a header.
        interaction.serviceTier?.let { serviceTier = it }
    }

    /**
     * An `error` event, as the error it is.
     *
     * The vendor's code is lifted onto the status so a retry policy keyed on 429 can see a rate limit
     * that arrived inside a 200, and the whole event rides `data` for the bug report.
     */
    private suspend fun FlowCollector<StreamPart>.onError(event: InteractionsEvent, raw: JsonElement) {
        finishStatus = "failed"
        val code = event.error?.code
        emit(
            StreamPart.Error(
                APICallError(
                    message = event.error?.message ?: "Unknown interaction error",
                    url = url,
                    statusCode = code?.intOrNull,
                    responseBody = raw.toString(),
                    data = raw,
                ),
            ),
        )
    }

    private suspend fun FlowCollector<StreamPart>.emitSources(sources: List<Content.Source>) {
        sources.forEach { source ->
            if (emittedSources.add(source.sourceKey())) emit(StreamPart.SourcePart(source))
        }
    }

    /** The interaction-id stamp every output part carries; null until the id is known. */
    private fun stamp() = interactionsPartMetadata(signature = null, interactionId = interactionId)

    private sealed class OpenBlock(val id: String) {
        class Text(id: String) : OpenBlock(id)

        class Reasoning(id: String) : OpenBlock(id) {
            var signature: String? = null
        }

        class Image(id: String) : OpenBlock(id) {
            var data: String? = null
            var mimeType: String? = null
            var uri: String? = null
        }

        class FunctionCall(id: String, var toolCallId: String, val toolName: String) : OpenBlock(id) {
            val arguments = StringBuilder()
            var signature: String? = null
        }

        class BuiltinCall(
            id: String,
            val stepType: String,
            var toolCallId: String,
            var toolName: String,
            var arguments: JsonObject?,
        ) : OpenBlock(id) {
            var emitted = false
        }

        class BuiltinResult(
            id: String,
            val stepType: String,
            var callId: String,
            var toolName: String,
            var result: JsonElement?,
            var isError: Boolean?,
        ) : OpenBlock(id) {
            var emitted = false
        }

        /** A `processing_call` / `processing_result` step: nothing but the metadata it will carry. */
        class Custom(id: String, val stepType: String) : OpenBlock(id) {
            val google = LinkedHashMap<String, String>()
        }

        class PendingModelOutput(id: String) : OpenBlock(id)

        class Unknown(id: String) : OpenBlock(id)
    }

    private companion object {
        val TEXT_ANNOTATION_TYPES = setOf("text_annotation", "text_annotation_delta")
    }
}

/**
 * A finished interaction replayed as a stream.
 *
 * An agent run that was already terminal when the POST returned has nothing left to stream from the
 * `GET`, so its steps are replayed in the same order and shape the live transform would have produced —
 * each text and reasoning block as one delta, since the server had produced the whole block before this
 * ran. [StreamPart.StreamStart] is the caller's, emitted before the POST like every other path.
 */
internal suspend fun FlowCollector<StreamPart>.emitSynthesizedInteraction(
    response: InteractionsResponse,
    raw: JsonElement,
    ids: IdGenerator,
    includeRawChunks: Boolean,
    headerServiceTier: String?,
) {
    val interactionId = response.id?.takeIf { it.isNotEmpty() }
    emit(
        StreamPart.ResponseMetadataPart(
            ResponseMetadata(
                id = interactionId,
                timestamp = parseCreatedMillis(response.created),
                modelId = response.model,
            ),
        ),
    )
    if (includeRawChunks) emit(StreamPart.Raw(raw))

    val parsed = parseGoogleInteractionsOutputs(response.steps, ids, interactionId)
    var counter = 0
    fun nextBlockId() = "${interactionId ?: "agent"}:${counter++}"

    parsed.content.forEach { part ->
        when (part) {
            is Content.Text -> {
                val id = nextBlockId()
                emit(StreamPart.TextStart(id))
                if (part.text.isNotEmpty()) emit(StreamPart.TextDelta(id, part.text))
                emit(StreamPart.TextEnd(id, part.providerMetadata))
            }
            is Content.Reasoning -> {
                val id = nextBlockId()
                emit(StreamPart.ReasoningStart(id))
                if (part.text.isNotEmpty()) emit(StreamPart.ReasoningDelta(id, part.text))
                emit(StreamPart.ReasoningEnd(id, part.providerMetadata))
            }
            is Content.ToolCall -> {
                emit(StreamPart.ToolInputStart(part.toolCallId, part.toolName, providerExecuted = part.providerExecuted))
                emit(StreamPart.ToolInputDelta(part.toolCallId, part.input))
                emit(StreamPart.ToolInputEnd(part.toolCallId))
                emit(StreamPart.ToolCallPart(part))
            }
            is Content.ToolResult -> emit(StreamPart.ToolResultPart(part))
            is Content.Source -> emit(StreamPart.SourcePart(part))
            is Content.File -> emit(StreamPart.FilePart(part))
            is Content.Custom -> emit(StreamPart.CustomPart(part))
            else -> Unit
        }
    }

    emit(
        StreamPart.Finish(
            usage = response.usage.toUsage(),
            finishReason = interactionsFinishReason(response.status, parsed.hasFunctionCall),
            providerMetadata = interactionsResponseMetadata(
                interactionId = interactionId,
                serviceTier = response.serviceTier ?: headerServiceTier,
                outputTokensByModality = response.usage.outputTokensByModality(),
            ),
        ),
    )
}

/** Opens the stream with the warnings the request built up; the first part of every path. */
internal fun streamStart(warnings: List<Warning>): StreamPart.StreamStart = StreamPart.StreamStart(warnings)
