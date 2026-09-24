package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.StreamPart
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Metadata key holding Gemini's `thought_signature` for a tool call that arrived on this wire. */
public const val THOUGHT_SIGNATURE_KEY: String = "thoughtSignature"

/**
 * Assembles index-correlated tool-call fragments, carrying each call's own signature with it.
 *
 * A near-twin of `util`'s `ToolCallTracker`, which this wire cannot use for two reasons:
 *
 * - **`Content.ToolCall.providerMetadata` has to be populated per call.** Gemini issues a
 *   `thought_signature` for each `functionCall` and rejects the following turn if the call is replayed
 *   without it; the shared tracker has no channel for a per-call payload, so the signature would be
 *   dropped between arriving and being assembled — the defect `aisdk/DESIGN.md` names as the reason
 *   this library exists, reproduced on the compatible wire.
 * - **Several compatible servers send the first fragment with no `function.name`.** Opening the block
 *   there means emitting a `ToolInputStart` whose `toolName` is the empty string, which a consumer can
 *   neither display nor route. Fragments are buffered until the name is known, exactly as the reference
 *   does (`openai-compatible-chat-language-model.ts:465-518`).
 */
internal class OpenAIToolCallAccumulator(
    /** The namespace a signature is filed under: the provider's own id. */
    private val providerKey: String,
) {

    private class Pending(var id: String?) {
        val arguments: StringBuilder = StringBuilder()
        var signature: String? = null
        /** Non-null once the name has arrived and the block has been opened. */
        var name: String? = null
    }

    private val byIndex = linkedMapOf<Int, Pending>()

    /**
     * Accepts one fragment.
     *
     * [index] falls back to arrival position: Google's compatible endpoint sends no index at all, and
     * keying every call under a missing index would merge unrelated calls into one.
     */
    suspend fun accept(collector: FlowCollector<StreamPart>, call: OpenAIToolCall) {
        val key = call.index ?: byIndex.size
        val pending = byIndex.getOrPut(key) { Pending(call.id) }
        if (pending.id == null) pending.id = call.id
        call.extraContent?.google?.thoughtSignature?.let { pending.signature = it }

        val name = call.function?.name?.takeIf { it.isNotEmpty() }
        val fragment = call.function?.arguments

        if (pending.name == null && name != null) {
            pending.name = name
            collector.emit(StreamPart.ToolInputStart(pending.identifier(key), name))
            // The buffered fragments belong to this block and are emitted now that it has a name.
            pending.arguments.takeIf { it.isNotEmpty() }?.let {
                collector.emit(StreamPart.ToolInputDelta(pending.identifier(key), it.toString()))
            }
            fragment?.let {
                pending.arguments.append(it)
                collector.emit(StreamPart.ToolInputDelta(pending.identifier(key), it))
            }
            return
        }

        fragment?.let {
            pending.arguments.append(it)
            if (pending.name != null) {
                collector.emit(StreamPart.ToolInputDelta(pending.identifier(key), it))
            }
        }
    }

    /** Closes every call, emitting the complete [Content.ToolCall] for each. */
    suspend fun finish(collector: FlowCollector<StreamPart>) {
        byIndex.forEach { (key, pending) ->
            val id = pending.identifier(key)
            if (pending.name == null) {
                // A call whose name never arrived cannot be executed, but must still be replayable —
                // hence `invalid`, which is the spec's word for "reproduce this, never run it".
                collector.emit(
                    StreamPart.ToolCallPart(
                        Content.ToolCall(
                            toolCallId = id,
                            toolName = "",
                            input = pending.input(),
                            invalid = true,
                            providerMetadata = pending.metadata(),
                        ),
                    ),
                )
                return@forEach
            }
            collector.emit(StreamPart.ToolInputEnd(id))
            collector.emit(
                StreamPart.ToolCallPart(
                    Content.ToolCall(
                        toolCallId = id,
                        toolName = pending.name.orEmpty(),
                        input = pending.input(),
                        providerMetadata = pending.metadata(),
                    ),
                ),
            )
        }
        byIndex.clear()
    }

    fun isEmpty(): Boolean = byIndex.isEmpty()

    private fun Pending.identifier(key: Int): String = id ?: "call_$key"

    // An empty-argument call still needs valid JSON to replay.
    private fun Pending.input(): String = arguments.toString().takeIf { it.isNotBlank() } ?: "{}"

    private fun Pending.metadata(): ProviderMetadata? = signature?.let {
        mapOf(providerKey to buildJsonObject { put(THOUGHT_SIGNATURE_KEY, it) })
    }
}
