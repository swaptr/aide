package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.StreamPart
import kotlinx.coroutines.flow.FlowCollector

/**
 * Assembles partial tool calls into complete ones.
 *
 * OpenAI-shaped servers set `id` and `name` only on the FIRST fragment for a call, then stream
 * `arguments` after it; the fragments CONCATENATE and are never cumulative. A client that treats them as
 * cumulative doubles every argument string, which produces JSON that parses and means the wrong thing —
 * the worst kind of wrong, because nothing errors.
 *
 * **Which call a fragment belongs to is resolved id → index → most recent**, in that order, because the
 * wires disagree about which of the three they send. Keying on the index alone, as this did, is wrong in
 * both directions: a server that reuses an index across two calls merges their arguments into one
 * unparseable blob, and a server that omits the index on a continuation splits one call into several
 * calls with truncated inputs.
 *
 * Lives here rather than inside one provider because several wires share the shape: OpenAI-compatible
 * and Cohere today, and any vendor that streams partial arguments tomorrow.
 */
public class ToolCallTracker {

    private class Pending(val id: String, val name: String) {
        val args: StringBuilder = StringBuilder()
        var providerMetadata: ProviderMetadata? = null
        var finished: Boolean = false
    }

    private val order = mutableListOf<Pending>()
    private val byId = mutableMapOf<String, Pending>()
    private val byIndex = mutableMapOf<Int, Pending>()
    private var latest: Pending? = null

    /**
     * Accepts one fragment, emitting the block-delimiting parts as it goes.
     *
     * A fragment that opens a NEW call must carry both [id] and [name]. Minting a placeholder for either
     * — which is what this used to do — turns a malformed provider response into a `ToolCall` with an
     * empty tool name that the runtime happily dispatches and no tool matches, so the user is told "tool
     * not found" for a tool they have. [InvalidResponseDataError] names the actual fault.
     *
     * [providerMetadata] rides through to the finished [Content.ToolCall]. It is the vendor's own data
     * about the call — OpenAI's `item_id`, Anthropic's cache counters — and dropping it here is the
     * exact loss this port exists to prevent. Later fragments merge into it, since a vendor is as likely
     * to attach it to the last fragment as to the first.
     */
    @Suppress("LongParameterList")
    public suspend fun accept(
        collector: FlowCollector<StreamPart>,
        index: Int?,
        id: String?,
        name: String?,
        argumentsFragment: String?,
        providerMetadata: ProviderMetadata? = null,
    ) {
        val existing = when {
            !id.isNullOrEmpty() -> byId[id]
            index != null -> byIndex[index]
            else -> latest
        }
        val pending = existing?.also { append(collector, it, argumentsFragment) }
            ?: open(collector, id, name, argumentsFragment)

        providerMetadata?.let { pending.providerMetadata = mergeMetadata(pending.providerMetadata, it) }
        if (index != null) byIndex[index] = pending
        latest = pending
    }

    /** Closes every open call, emitting the complete [Content.ToolCall] for each. */
    public suspend fun finish(collector: FlowCollector<StreamPart>) {
        order.forEach { pending ->
            if (pending.finished) return@forEach
            pending.finished = true
            collector.emit(StreamPart.ToolInputEnd(pending.id))
            collector.emit(
                StreamPart.ToolCallPart(
                    Content.ToolCall(
                        toolCallId = pending.id,
                        toolName = pending.name,
                        // An empty-argument call still needs valid JSON to replay.
                        input = pending.args.toString().takeIf { it.isNotBlank() } ?: "{}",
                        providerMetadata = pending.providerMetadata,
                    ),
                ),
            )
        }
    }

    /** Whether anything was tracked, for a caller deciding whether the turn called tools at all. */
    public fun isEmpty(): Boolean = order.isEmpty()

    private suspend fun open(
        collector: FlowCollector<StreamPart>,
        id: String?,
        name: String?,
        argumentsFragment: String?,
    ): Pending {
        if (id.isNullOrEmpty()) {
            throw InvalidResponseDataError("Tool call fragment has no id; a tool result cannot reference it.")
        }
        if (name.isNullOrEmpty()) {
            throw InvalidResponseDataError("Tool call $id has no tool name; nothing can be dispatched for it.")
        }
        val pending = Pending(id = id, name = name)
        order += pending
        byId[id] = pending
        collector.emit(StreamPart.ToolInputStart(pending.id, pending.name))
        append(collector, pending, argumentsFragment)
        return pending
    }

    private suspend fun append(
        collector: FlowCollector<StreamPart>,
        pending: Pending,
        fragment: String?,
    ) {
        // A fragment arriving after the call was closed is a server contradicting itself; appending it
        // would mutate input the caller has already been handed as complete.
        if (pending.finished || fragment.isNullOrEmpty()) return
        pending.args.append(fragment)
        collector.emit(StreamPart.ToolInputDelta(pending.id, fragment))
    }

    private fun mergeMetadata(current: ProviderMetadata?, added: ProviderMetadata): ProviderMetadata =
        if (current == null) added else current + added
}
