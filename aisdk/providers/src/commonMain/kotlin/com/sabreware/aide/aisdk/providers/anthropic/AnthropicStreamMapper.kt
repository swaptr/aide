package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ToolNameMapping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One decoded SSE frame, alongside the payload it was decoded from. */
internal data class AnthropicFrame(
    val event: AnthropicStreamEvent,
    /** Only kept when the caller asked for raw chunks; parsing twice to serve a debug flag is waste. */
    val raw: JsonElement? = null,
)

/**
 * Anthropic's streaming events, mapped to the spec's stream parts.
 *
 * Separate from the model because Anthropic's event vocabulary reaches AIDE by two different transports:
 * SSE from the Anthropic API, and AWS's binary event stream from Bedrock, which wraps these very same
 * events as base64 inside its own frames. The signature handling is delicate enough that having it in two
 * places would guarantee they eventually disagree — and the one that drifted would fail as an opaque 400.
 *
 * The critical behaviour: `signature_delta` is buffered per block and emitted on
 * [StreamPart.ReasoningEnd], because the signed payload arrives when the block CLOSES rather than with
 * its text.
 */
internal class AnthropicStreamMapper(
    private val sourceUrl: String,
    /** Translates the vendor's fixed tool names back to whatever the caller called them. */
    private val toolNames: ToolNameMapping = ToolNameMapping.Identity,
    /** A structured-output turn served through the JSON tool: its input is the answer, not a call. */
    private val usesJsonResponseTool: Boolean = false,
    /**
     * Whether `code_execution` calls are marked `dynamic`: a dynamic-filtering web tool was offered with
     * no code-execution tool beside it, so the API provisioned one the caller never declared — see
     * [hasDynamicFilteringWebToolWithoutCodeExecution].
     */
    private val markCodeExecutionDynamic: Boolean = false,
) {

    private val sourceIds = IdGenerator("src_")

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun map(events: Flow<AnthropicFrame>, warnings: List<Warning>): Flow<StreamPart> = flow {
        emit(StreamPart.StreamStart(warnings))
        val state = BlockState()
        val finishMetadata = AnthropicFinishMetadata()
        var cumulativeOutput = 0
        var thinkingTokens: Int? = null
        var inputTokens: Int? = null
        var cacheRead: Int? = null
        var cacheWrite: Int? = null
        var finish: FinishReason? = null

        events.collect { frame ->
            frame.raw?.let { emit(StreamPart.Raw(it)) }
            val event = frame.event
            when (event.type) {
                // Anthropic answers HTTP 200 and then says `overloaded_error` in the body. Throwing here
                // produced an exception with no status code, so `isRetryable` defaulted to false and no
                // retry policy anywhere could fire on the single most retryable failure Anthropic has.
                "error" -> emit(StreamPart.Error(streamError(event.error)))

                "message_start" -> {
                    event.message?.let { message ->
                        emit(
                            StreamPart.ResponseMetadataPart(
                                ResponseMetadata(id = message.id, modelId = message.model),
                            ),
                        )
                        finishMetadata.onMessageStart(message)
                        inputTokens = message.usage?.inputTokens
                        cacheRead = message.usage?.cacheReadInputTokens
                        cacheWrite = message.usage?.cacheCreationInputTokens
                    }
                }

                "content_block_start" -> {
                    val block = event.contentBlock ?: return@collect
                    val id = event.index?.toString() ?: return@collect
                    when {
                        block.type == "text" -> {
                            state.open(id, BlockKind.Text)
                            emit(StreamPart.TextStart(id))
                            block.text?.takeIf { it.isNotEmpty() }?.let { emit(StreamPart.TextDelta(id, it)) }
                        }
                        block.type == "thinking" -> {
                            state.open(id, BlockKind.Reasoning)
                            emit(StreamPart.ReasoningStart(id))
                            block.thinking?.takeIf { it.isNotEmpty() }
                                ?.let { emit(StreamPart.ReasoningDelta(id, it)) }
                        }
                        // Complete on arrival: no deltas ever follow.
                        block.type == "redacted_thinking" -> {
                            state.open(id, BlockKind.Reasoning)
                            state.redacted[id] = block.data.orEmpty()
                            emit(StreamPart.ReasoningStart(id))
                        }
                        // The JSON response tool's input IS the answer, so it opens a TEXT block. A
                        // caller that asked for structured output never learns which mechanism served it.
                        usesJsonResponseTool && block.type == "tool_use" && block.name == JSON_RESPONSE_TOOL -> {
                            state.open(id, BlockKind.JsonOutput)
                            emit(StreamPart.TextStart(id))
                        }
                        block.type in TOOL_CALL_BLOCKS -> {
                            val providerExecuted = block.type != "tool_use"
                            state.open(id, BlockKind.Tool)
                            state.toolIds[id] = block.id.orEmpty()
                            state.toolNames[id] = toolNames.toCustomToolName(block.name.orEmpty())
                            state.providerExecuted[id] = providerExecuted
                            // The implicitly provisioned code-execution tool: its calls must pass a
                            // validation against tools the caller never listed.
                            state.dynamic[id] = markCodeExecutionDynamic && block.name == CODE_EXECUTION
                            // A client toolset member reports which toolset it came from, and both the
                            // caller's dispatch and the answering result need it — see AnthropicApi.
                            block.toolsetName?.let { state.toolsetNames[id] = it }
                            // Some server tools carry their whole input here and stream no deltas at all.
                            (block.input as? JsonObject)?.takeIf { it.isNotEmpty() }
                                ?.let { state.toolInput.getOrPut(id) { StringBuilder() }.append(it.toString()) }
                            emit(
                                StreamPart.ToolInputStart(
                                    id = block.id.orEmpty(),
                                    toolName = state.toolNames.getValue(id),
                                    providerExecuted = providerExecuted,
                                    dynamic = state.dynamic[id] == true,
                                    providerMetadata = state.toolsetMetadata(id),
                                ),
                            )
                        }
                        block.type.endsWith(TOOL_RESULT_SUFFIX) -> {
                            state.open(id, BlockKind.Ignored)
                            emitToolResult(block)
                        }
                        else -> Unit
                    }
                }

                "content_block_delta" -> {
                    val delta = event.delta ?: return@collect
                    val id = event.index?.toString() ?: return@collect
                    when (delta.type) {
                        "text_delta" -> delta.text?.takeIf { it.isNotEmpty() }
                            ?.let { emit(StreamPart.TextDelta(id, it)) }
                        "thinking_delta" -> delta.thinking?.takeIf { it.isNotEmpty() }
                            ?.let { emit(StreamPart.ReasoningDelta(id, it)) }
                        // Buffered, not emitted: it belongs on the block's End part.
                        "signature_delta" -> delta.signature?.takeIf { it.isNotEmpty() }
                            ?.let { state.signatures[id] = it }
                        "input_json_delta" -> delta.partialJson?.let { fragment ->
                            if (state.kinds[id] == BlockKind.JsonOutput) {
                                emit(StreamPart.TextDelta(id, fragment))
                            } else {
                                state.toolInput.getOrPut(id) { StringBuilder() }.append(fragment)
                                emit(StreamPart.ToolInputDelta(state.toolIds[id].orEmpty(), fragment))
                            }
                        }
                        else -> Unit
                    }
                }

                "content_block_stop" -> {
                    val id = event.index?.toString() ?: return@collect
                    when (state.kinds[id]) {
                        BlockKind.Text, BlockKind.JsonOutput -> emit(StreamPart.TextEnd(id))
                        BlockKind.Reasoning -> emit(
                            StreamPart.ReasoningEnd(id, providerMetadata = state.reasoningMetadata(id)),
                        )
                        BlockKind.Tool -> {
                            val toolCallId = state.toolIds[id].orEmpty()
                            emit(StreamPart.ToolInputEnd(toolCallId))
                            emit(
                                StreamPart.ToolCallPart(
                                    Content.ToolCall(
                                        toolCallId = toolCallId,
                                        toolName = state.toolNames[id].orEmpty(),
                                        // An empty-argument tool call still needs valid JSON on replay.
                                        input = state.toolInput[id]?.toString()?.takeIf { it.isNotBlank() } ?: "{}",
                                        providerExecuted = state.providerExecuted[id] == true,
                                        dynamic = state.dynamic[id] == true,
                                        providerMetadata = state.toolsetMetadata(id),
                                    ),
                                ),
                            )
                        }
                        BlockKind.Ignored, null -> Unit
                    }
                    state.close(id)
                }

                "message_delta" -> {
                    finishMetadata.onMessageDelta(event)
                    // Spec: message_delta.usage is CUMULATIVE, and a stream may carry more than one.
                    event.usage?.outputTokens?.let { cumulativeOutput = it }
                    // Only present on the final message_delta, and only when thinking ran.
                    event.usage?.outputTokensDetails?.thinkingTokens?.let { thinkingTokens = it }
                    event.delta?.stopReason?.let { finish = it.toFinishReason(usesJsonResponseTool) }
                }

                else -> Unit // message_stop, ping: nothing to surface
            }
        }

        emit(
            StreamPart.Finish(
                usage = Usage(
                    inputTokens = Usage.InputTokens(
                        total = inputTokens,
                        cacheRead = cacheRead,
                        cacheWrite = cacheWrite,
                    ),
                    outputTokens = Usage.OutputTokens(
                        total = cumulativeOutput.takeIf { it > 0 },
                        reasoning = thinkingTokens,
                        // Anthropic reports the total and the thinking share; the text share is the rest.
                        text = cumulativeOutput.takeIf { it > 0 }?.let { it - (thinkingTokens ?: 0) },
                    ),
                ),
                finishReason = finish ?: FinishReason(FinishReason.Unified.Other, raw = null),
                // Container id, stop details, iterations and applied context edits — the payload that
                // lets a next step reuse this turn's code-execution container instead of losing it.
                providerMetadata = finishMetadata.toProviderMetadata(),
            ),
        )
    }

    /**
     * A result from a tool Anthropic ran on its own servers.
     *
     * The block type is kept in [ANTHROPIC_BLOCK_TYPE_KEY] because the replay half has to reproduce it —
     * a web search result and a code execution result are different wire types, and sending back the
     * wrong one is a 400 on the next turn.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamPart>.emitToolResult(
        block: AnthropicContentBlock,
    ) {
        val providerToolName = block.type.removeSuffix(TOOL_RESULT_SUFFIX).let { CODE_EXECUTION_ALIASES[it] ?: it }
        val content = block.content
        val isError = (content as? JsonObject)?.get("type")?.stringOrNull()?.endsWith("_error") == true

        emit(
            StreamPart.ToolResultPart(
                Content.ToolResult(
                    toolCallId = block.toolUseId.orEmpty(),
                    toolName = toolNames.toCustomToolName(providerToolName),
                    output = ToolOutput.Json(content ?: JsonObject(emptyMap())),
                    isError = isError,
                    dynamic = markCodeExecutionDynamic && providerToolName == CODE_EXECUTION,
                    providerMetadata = mapOf(
                        ANTHROPIC_PROVIDER_ID to buildJsonObject { put(ANTHROPIC_BLOCK_TYPE_KEY, block.type) },
                    ),
                ),
            ),
        )

        // A search result is also a citation. Surfacing it as a source is what lets a UI show where an
        // answer came from without teaching it Anthropic's block shapes.
        if (providerToolName == "web_search") {
            (content as? JsonArray)?.forEach { result ->
                val entry = result as? JsonObject ?: return@forEach
                val url = entry["url"]?.stringOrNull() ?: return@forEach
                emit(
                    StreamPart.SourcePart(
                        Content.Source.Url(
                            id = sourceIds.next(),
                            url = url,
                            title = entry["title"]?.stringOrNull(),
                        ),
                    ),
                )
            }
        }
    }

    private fun streamError(error: AnthropicError?): APICallError = APICallError(
        message = error?.message ?: "Anthropic stream error",
        url = sourceUrl,
        // Anthropic's own documented meaning for the code, and the reason to bother: a 529 is retryable
        // and every other mid-stream error is not, which is the whole decision a retry policy makes.
        statusCode = if (error?.type == OVERLOADED) OVERLOADED_STATUS else INTERNAL_STATUS,
        responseBody = error?.let { "${it.type}: ${it.message}" },
        isRetryable = error?.type == OVERLOADED,
    )

    /** Which kind of block an index refers to; Anthropic gives the type once, at the start event. */
    private enum class BlockKind { Text, Reasoning, Tool, JsonOutput, Ignored }

    private class BlockState {
        val kinds = mutableMapOf<String, BlockKind>()
        val signatures = mutableMapOf<String, String>()
        val redacted = mutableMapOf<String, String>()
        val toolIds = mutableMapOf<String, String>()
        val toolNames = mutableMapOf<String, String>()
        val toolInput = mutableMapOf<String, StringBuilder>()
        val providerExecuted = mutableMapOf<String, Boolean>()
        val dynamic = mutableMapOf<String, Boolean>()
        val toolsetNames = mutableMapOf<String, String>()

        fun open(id: String, kind: BlockKind) {
            kinds[id] = kind
        }

        fun close(id: String) {
            kinds.remove(id)
        }

        /**
         * Which client toolset a member call belongs to, for the caller's dispatch and its own replay.
         *
         * Carried rather than folded into the tool name: the name is what the vendor called it, and the
         * answering `tool_result` has to echo this value beside it or the API rejects the turn.
         */
        fun toolsetMetadata(id: String): ProviderMetadata? = toolsetNames[id]?.let { toolset ->
            mapOf(ANTHROPIC_PROVIDER_ID to buildJsonObject { put(ANTHROPIC_TOOLSET_KEY, toolset) })
        }

        /** The signed or encrypted payload for a reasoning block, namespaced under the provider. */
        fun reasoningMetadata(id: String): ProviderMetadata? {
            val signature = signatures[id]
            val redactedData = redacted[id]
            if (signature == null && redactedData == null) return null
            return mapOf(
                ANTHROPIC_PROVIDER_ID to buildJsonObject {
                    signature?.let { put(ANTHROPIC_SIGNATURE_KEY, it) }
                    redactedData?.let { put(ANTHROPIC_REDACTED_KEY, it) }
                },
            )
        }
    }

    private companion object {
        val TOOL_CALL_BLOCKS = setOf("tool_use", "server_tool_use", "mcp_tool_use")
        const val TOOL_RESULT_SUFFIX = "_tool_result"
        const val CODE_EXECUTION = "code_execution"

        // Bash and the text editor run INSIDE the code execution tool, and their results come back under
        // their own block types while the tool the caller declared is `code_execution`.
        val CODE_EXECUTION_ALIASES = mapOf(
            "bash_code_execution" to "code_execution",
            "text_editor_code_execution" to "code_execution",
        )

        const val OVERLOADED = "overloaded_error"
        const val OVERLOADED_STATUS = 529
        const val INTERNAL_STATUS = 500
    }
}

/**
 * Anthropic's stop reasons, keeping the vendor string alongside the normalized one.
 *
 * `tool_use` is the interesting one: when the turn was a structured-output request served through the
 * JSON tool, the tool call IS the answer, so the run has finished rather than paused for a tool.
 */
internal fun String.toFinishReason(isJsonResponseFromTool: Boolean = false): FinishReason = FinishReason(
    unified = when (this) {
        "end_turn", "stop_sequence", "pause_turn" -> FinishReason.Unified.Stop
        "tool_use" -> if (isJsonResponseFromTool) FinishReason.Unified.Stop else FinishReason.Unified.ToolCalls
        "max_tokens", "model_context_window_exceeded" -> FinishReason.Unified.Length
        "refusal" -> FinishReason.Unified.ContentFilter
        else -> FinishReason.Unified.Other
    },
    raw = this,
)
