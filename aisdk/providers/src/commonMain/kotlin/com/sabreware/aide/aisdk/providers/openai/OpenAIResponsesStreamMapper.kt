package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.IdGenerator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A decoded stream chunk beside the raw frame, so `includeRawChunks` costs the mapper nothing. */
internal class OpenAIResponsesFrame(val chunk: OpenAIResponsesChunk, val raw: JsonElement?)

/**
 * The `/v1/responses` SSE stream, mapped to the spec's delimited parts.
 *
 * Two things here are not obvious and both are load-bearing.
 *
 * **A reasoning block's id is `itemId:summaryIndex`, not the item id.** One reasoning item carries
 * several summary parts, and OpenAI interleaves their deltas with nothing but `summary_index` to tell
 * them apart. Keying blocks by the item id alone merges every summary of a turn into one block, which
 * loses the model's own account of where one line of thought ended and the next began.
 *
 * **`encrypted_content` arrives on `response.output_item.done`, after every delta.** It is OpenAI's
 * counterpart to Anthropic's `signature`: without it a `store: false` reasoning turn has nothing to
 * replay, so the next round re-derives the entire chain of thought and is billed for it again. It is
 * emitted on EVERY reasoning end for its item rather than only the last, because a consumer that keeps
 * some summaries and not others would otherwise keep the parts without the payload.
 *
 * Item types that do not stream — a web search, a code interpreter run, an MCP call — are routed through
 * the same [toContent] the non-streaming path uses when their item completes. One mapping to keep
 * correct, rather than a streaming copy that drifts from it.
 */
internal class OpenAIResponsesStreamMapper(
    private val tools: PreparedTools,
    private val url: String,
    private val generateId: () -> String = IdGenerator("src_")::next,
    /** See `OpenAIResponsesLanguageModel.namespace` — the key every emission here is filed under. */
    private val namespace: String = OPENAI_PROVIDER_ID,
    private val quirks: ResponsesQuirks = ResponsesQuirks(),
    /** The endpoint's Open Responses extensions, which own every namespaced event and item. */
    private val extensions: OpenResponsesExtensionRegistry = OpenResponsesExtensionRegistry.Empty,
) {

    /** An extension's scratch space, alive for exactly this stream — see [OpenResponsesExtension.decodeEvent]. */
    private val extensionState = mutableMapOf<String, Any?>()

    /** Which summary indices of a reasoning item have been opened, and the payload that closes them. */
    private class ReasoningState {
        val openSummaries = mutableListOf<Int>()
        var encryptedContent: String? = null
    }

    private val reasoning = mutableMapOf<String, ReasoningState>()

    /**
     * A call that has opened and not yet closed. `async` is read off the item that OPENED it, because
     * the item that closes a call may omit the flag the opening one carried.
     */
    private class OngoingToolCall(val call: Content.ToolCall, val async: Boolean?)

    /** Keyed by `output_index`, because argument deltas name the index and never the call id. */
    private val toolCalls = mutableMapOf<Int, OngoingToolCall>()

    private val openText = mutableSetOf<String>()
    private var hasFunctionCall = false
    private var responseId: String? = null
    private var serviceTier: String? = null

    fun map(frames: Flow<OpenAIResponsesFrame>, warnings: List<Warning>): Flow<StreamPart> = flow {
        emit(StreamPart.StreamStart(warnings))
        var finished = false
        frames.collect { frame ->
            frame.raw?.let { emit(StreamPart.Raw(it)) }
            if (handle(frame.chunk)) finished = true
        }
        // A stream that ends without a terminal chunk still owes the caller a Finish: the assembler
        // reads usage and the finish reason from it, and its absence reports a truncated connection as a
        // successful turn with no tokens.
        if (!finished) emit(finishPart(null, "incomplete"))
    }

    /** @return true once a terminal chunk has been emitted. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun FlowCollector<StreamPart>.handle(chunk: OpenAIResponsesChunk): Boolean {
        when (chunk.type) {
            "response.created", "response.in_progress" -> {
                val response = chunk.response ?: return false
                responseId = response.id
                serviceTier = response.serviceTier ?: serviceTier
                emit(
                    StreamPart.ResponseMetadataPart(
                        ResponseMetadata(
                            id = response.id,
                            // OpenAI reports `created_at` in epoch SECONDS; ResponseMetadata is millis.
                            timestamp = response.createdAt?.let { it * MILLIS_PER_SECOND },
                            modelId = response.model,
                        ),
                    ),
                )
            }

            "response.output_item.added" -> openItem(chunk)

            "response.reasoning_summary_part.added" -> {
                val itemId = chunk.itemId ?: return false
                val index = chunk.summaryIndex ?: return false
                // Index 0 was opened with the item itself; only a SUBSEQUENT part starts a new block.
                if (index == 0) return false
                val state = reasoning.getOrPut(itemId) { ReasoningState() }
                state.openSummaries += index
                emit(StreamPart.ReasoningStart("$itemId:$index", reasoningMetadata(itemId, null, namespace)))
            }

            "response.reasoning_summary_text.delta" -> {
                val itemId = chunk.itemId ?: return false
                emit(
                    StreamPart.ReasoningDelta(
                        id = "$itemId:${chunk.summaryIndex ?: 0}",
                        delta = chunk.delta.orEmpty(),
                        providerMetadata = reasoningMetadata(itemId, null, namespace),
                    ),
                )
            }

            "response.output_text.delta" -> {
                val itemId = chunk.itemId ?: return false
                if (openText.add(itemId)) emit(StreamPart.TextStart(itemId))
                emit(StreamPart.TextDelta(itemId, chunk.delta.orEmpty()))
            }

            "response.function_call_arguments.delta", "response.custom_tool_call_input.delta" -> {
                val call = toolCalls[chunk.outputIndex ?: return false]?.call ?: return false
                emit(StreamPart.ToolInputDelta(call.toolCallId, chunk.delta.orEmpty()))
            }

            "response.output_text.annotation.added" ->
                chunk.annotation?.toSource(generateId, namespace)?.let { emit(StreamPart.SourcePart(it)) }

            "response.output_item.done" -> closeItem(chunk.item ?: return false, chunk.outputIndex)

            "response.completed", "response.incomplete" -> {
                val response = chunk.response
                serviceTier = response?.serviceTier ?: serviceTier
                responseId = response?.id ?: responseId
                emit(finishPart(response?.usage, response?.incompleteDetails?.reason))
                return true
            }

            "response.failed" -> {
                emit(StreamPart.Error(apiError(chunk.response?.error?.message ?: chunk.message)))
                emit(finishPart(chunk.response?.usage, "error"))
                return true
            }

            // The error frame OpenAI raises after the 200 — a quota exhausted mid-generation. It arrives
            // in two shapes depending on the failure, and modelling only one reports the other as an
            // unparseable frame, which reads as a stream that simply stopped.
            "error" -> emit(StreamPart.Error(apiError(chunk.error?.message ?: chunk.message)))

            // A namespaced type is an extension's event; anything else unknown is a chunk this port
            // has not seen and costs nothing.
            else -> chunk.extension?.let { extensionEvent(it) }
        }
        return false
    }

    /**
     * An extension's own event, decoded by the extension that registered its type — or ignored where
     * none did, since a frame nothing understands is exactly what the skip rule above is for.
     *
     * A codec that throws becomes an error PART rather than the end of the stream, for the same
     * reason a bad frame does: one extension's bug should cost its parts, not the turn.
     */
    private suspend fun FlowCollector<StreamPart>.extensionEvent(raw: JsonObject) {
        val event = OpenResponsesExtensionEvent.from(raw) ?: return
        val decode = extensions.byEventType[event.type]?.decodeEvent ?: return
        val parts = try {
            decode(event, extensionState)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            emit(StreamPart.Error(e))
            return
        }
        parts.orEmpty().forEach { part ->
            if (part is StreamPart.ToolCallPart || part is StreamPart.ToolInputStart) hasFunctionCall = true
            emit(part)
        }
    }

    /** A completed extension item, through the same decode the JSON path uses, in stream mode. */
    private suspend fun FlowCollector<StreamPart>.extensionItem(raw: JsonObject) {
        val item = OpenResponsesExtensionItem.from(raw) ?: return
        val decoded = try {
            extensions.decodeExtensionItem(item, OpenResponsesDecodeMode.Stream, namespace)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            emit(StreamPart.Error(e))
            return
        }
        decoded.orEmpty().forEach { content ->
            if (content is Content.ToolCall) hasFunctionCall = true
            content.asStreamParts(generateId).forEach { emit(it) }
        }
    }

    private suspend fun FlowCollector<StreamPart>.openItem(chunk: OpenAIResponsesChunk) {
        val item = chunk.item ?: return
        when (item.type) {
            "reasoning" -> {
                val itemId = item.id ?: return
                val state = reasoning.getOrPut(itemId) { ReasoningState() }
                state.openSummaries += 0
                state.encryptedContent = item.encryptedContent ?: state.encryptedContent
                emit(StreamPart.ReasoningStart("$itemId:0", reasoningMetadata(itemId, null, namespace)))
            }

            "function_call", "custom_tool_call" -> {
                val call = Content.ToolCall(
                    toolCallId = item.callId.orEmpty(),
                    toolName = if (item.type == "custom_tool_call") {
                        tools.mapping.toCustomToolName(item.name.orEmpty())
                    } else {
                        item.name.orEmpty()
                    },
                    input = "",
                    providerMetadata = if (item.type == "custom_tool_call") {
                        toolCallMetadata(item.id, item.async, namespace = namespace)
                    } else {
                        toolCallMetadata(item.id, item.async, item.namespace, item.caller, namespace)
                    },
                )
                chunk.outputIndex?.let { toolCalls[it] = OngoingToolCall(call, item.async) }
                emit(StreamPart.ToolInputStart(call.toolCallId, call.toolName, providerMetadata = call.providerMetadata))
            }

            else -> Unit
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun FlowCollector<StreamPart>.closeItem(item: OpenAIOutputItem, outputIndex: Int?) {
        when (item.type) {
            "reasoning" -> {
                val itemId = item.id ?: return
                val state = reasoning[itemId] ?: ReasoningState().also { it.openSummaries += 0 }
                val encrypted = item.encryptedContent ?: state.encryptedContent
                state.openSummaries.distinct().sorted().forEach { index ->
                    emit(
                        StreamPart.ReasoningEnd(
                            id = "$itemId:$index",
                            providerMetadata = reasoningMetadata(itemId, encrypted, namespace),
                        ),
                    )
                }
                reasoning.remove(itemId)
            }

            "message" -> {
                val itemId = item.id ?: return
                // A message whose text never streamed — a non-streaming upstream, or a message that was
                // complete on arrival — still owes the assembler its text.
                if (openText.remove(itemId)) {
                    emit(StreamPart.TextEnd(itemId, textMetadata(item.id, item.phase, namespace)))
                } else {
                    val text = item.content.orEmpty().joinToString("") { it.text.orEmpty() }
                    emit(StreamPart.TextStart(itemId, textMetadata(item.id, item.phase, namespace)))
                    emit(StreamPart.TextDelta(itemId, text))
                    emit(StreamPart.TextEnd(itemId))
                }
                item.content.orEmpty().flatMap { it.annotations }.forEach { annotation ->
                    annotation.toSource(generateId, namespace)?.let { emit(StreamPart.SourcePart(it)) }
                }
            }

            "function_call", "custom_tool_call" -> {
                hasFunctionCall = true
                val callId = item.callId.orEmpty()
                val ongoing = outputIndex?.let { toolCalls.remove(it) }
                val async = item.async ?: ongoing?.async
                emit(StreamPart.ToolInputEnd(callId))
                emit(
                    StreamPart.ToolCallPart(
                        Content.ToolCall(
                            toolCallId = callId,
                            toolName = if (item.type == "custom_tool_call") {
                                tools.mapping.toCustomToolName(item.name.orEmpty())
                            } else {
                                item.name.orEmpty()
                            },
                            input = (if (item.type == "custom_tool_call") item.input else item.arguments).orEmpty(),
                            providerMetadata = if (item.type == "custom_tool_call") {
                                toolCallMetadata(item.id, async, namespace = namespace)
                            } else {
                                toolCallMetadata(item.id, async, item.namespace, item.caller, namespace)
                            },
                        ),
                    ),
                )
            }

            // Everything the vendor ran itself, through the mapper the non-streaming path uses — or,
            // for a namespaced item, through the extension that owns it.
            else -> {
                val raw = item.extension
                if (raw != null) {
                    extensionItem(raw)
                } else {
                    val mapped = listOf(item).toContent(tools, generateId, namespace)
                    if (mapped.hasFunctionCall) hasFunctionCall = true
                    mapped.content.forEach { content ->
                        content.asStreamParts(generateId).forEach { emit(it) }
                    }
                }
            }
        }
    }

    private fun finishPart(usage: JsonObject?, incompleteReason: String?): StreamPart.Finish =
        StreamPart.Finish(
            usage = usage.decodeResponsesUsage()
                .toUsage(raw = usage, mayExcludeCached = quirks.usageMayExcludeCachedTokens),
            finishReason = openAIFinishReason(incompleteReason, hasFunctionCall, quirks),
            providerMetadata = responseMetadata(),
        )

    /**
     * The response id, which a caller needs to send `previous_response_id` on the next turn — the whole
     * mechanism by which a stateful OpenAI conversation avoids resending its history.
     */
    private fun responseMetadata(): ProviderMetadata? {
        if (responseId == null && serviceTier == null) return null
        return mapOf(
            namespace to buildJsonObject {
                responseId?.let { put(OPENAI_RESPONSE_ID_KEY, it) }
                serviceTier?.let { put("serviceTier", it) }
            },
        )
    }

    private fun apiError(message: String?): Throwable = APICallError(
        message = message ?: "The OpenAI Responses stream reported an error with no message.",
        url = url,
        isRetryable = false,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/**
 * The stream parts that carry a completed [Content].
 *
 * Text and reasoning arrive whole here, so they are emitted as a one-delta block rather than left for a
 * consumer to special-case: the assembler's rule is that content exists only inside a delimited block,
 * and a bare delta with no start would be filed under an id nothing opened.
 */
private fun Content.asStreamParts(nextId: () -> String): List<StreamPart> = when (this) {
    is Content.ToolCall -> listOf(StreamPart.ToolCallPart(this))
    is Content.ToolResult -> listOf(StreamPart.ToolResultPart(this))
    is Content.ToolApprovalRequest -> listOf(StreamPart.ToolApprovalRequestPart(this))
    is Content.Source -> listOf(StreamPart.SourcePart(this))
    is Content.File -> listOf(StreamPart.FilePart(this))
    is Content.ReasoningFile -> listOf(StreamPart.ReasoningFilePart(this))
    is Content.Custom -> listOf(StreamPart.CustomPart(this))
    is Content.Text -> nextId().let { id ->
        listOf(
            StreamPart.TextStart(id),
            StreamPart.TextDelta(id, text),
            StreamPart.TextEnd(id, providerMetadata),
        )
    }
    is Content.Reasoning -> nextId().let { id ->
        listOf(
            StreamPart.ReasoningStart(id),
            StreamPart.ReasoningDelta(id, text),
            StreamPart.ReasoningEnd(id, providerMetadata),
        )
    }
}
