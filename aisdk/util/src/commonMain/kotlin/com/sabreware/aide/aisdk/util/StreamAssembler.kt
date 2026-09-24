package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.Warning
import kotlinx.coroutines.flow.Flow

/**
 * Folds a [StreamPart] stream into a [GenerateResult].
 *
 * Two jobs, and the second is the one that is easy to get wrong.
 *
 * **Order is preserved.** Blocks are emitted in the order they OPENED, not the order they closed.
 * Vendors interleave — a model may open a reasoning block, open a tool call, then close the reasoning —
 * and Anthropic requires a replayed assistant turn to begin with its thinking block. Sorting by close
 * order silently reorders exactly the case that matters.
 *
 * **Metadata is carried.** A signature arrives on the block's End part, because that is when the vendor
 * emits it; it is merged onto the assembled [Content.Reasoning]. This is the join that makes signed
 * reasoning replayable, and dropping it here would waste every precaution taken upstream.
 *
 * Lets a provider implement streaming only and get non-streaming for free — one wire path that is right
 * beats two that drift.
 *
 * @param stream the parts, in the order the provider emitted them.
 * @param onError what to do with a [StreamPart.Error]. Null throws it, which is what a provider folding
 *   its own stream into `doGenerate` wants: a failed generation must fail. A multi-round runtime passes a
 *   handler instead, because an error on round four should cost that round and not the three before it.
 */
public suspend fun assembleGenerateResult(
    stream: Flow<StreamPart>,
    onError: (suspend (Throwable) -> Unit)? = null,
): GenerateResult {
    val blocks = LinkedHashMap<String, OpenBlock>()
    val standalone = mutableListOf<IndexedContent>()
    val warnings = mutableListOf<Warning>()
    var usage = Usage()
    var finishReason = FinishReason(FinishReason.Unified.Other)
    var providerMetadata: ProviderMetadata? = null
    var response: ResponseInfo? = null
    var ordinal = 0

    stream.collect { part ->
        when (part) {
            is StreamPart.StreamStart -> warnings += part.warnings
            is StreamPart.ResponseMetadataPart -> response = ResponseInfo(metadata = part.metadata)

            is StreamPart.TextStart ->
                blocks.getOrPut(key(Kind.Text, part.id)) { OpenBlock(Kind.Text, ordinal++) }
            is StreamPart.TextDelta ->
                blocks.getOrPut(key(Kind.Text, part.id)) { OpenBlock(Kind.Text, ordinal++) }
                    .text.append(part.delta)
            is StreamPart.TextEnd ->
                blocks[key(Kind.Text, part.id)]?.mergeMetadata(part.providerMetadata)

            is StreamPart.ReasoningStart ->
                blocks.getOrPut(key(Kind.Reasoning, part.id)) { OpenBlock(Kind.Reasoning, ordinal++) }
            is StreamPart.ReasoningDelta ->
                blocks.getOrPut(key(Kind.Reasoning, part.id)) { OpenBlock(Kind.Reasoning, ordinal++) }
                    .text.append(part.delta)
            // The signature lands here, on close, and must reach the assembled block.
            is StreamPart.ReasoningEnd ->
                blocks.getOrPut(key(Kind.Reasoning, part.id)) { OpenBlock(Kind.Reasoning, ordinal++) }
                    .mergeMetadata(part.providerMetadata)

            // Tool input deltas are ignored: the provider emits a complete ToolCallPart on close, and
            // accumulating the fragments here as well would double the arguments.
            is StreamPart.ToolInputStart, is StreamPart.ToolInputDelta, is StreamPart.ToolInputEnd -> Unit

            is StreamPart.ToolCallPart -> standalone += IndexedContent(ordinal++, part.toolCall)
            is StreamPart.ToolResultPart -> standalone += IndexedContent(ordinal++, part.toolResult)
            is StreamPart.ToolApprovalRequestPart -> standalone += IndexedContent(ordinal++, part.request)
            is StreamPart.FilePart -> standalone += IndexedContent(ordinal++, part.file)
            is StreamPart.ReasoningFilePart -> standalone += IndexedContent(ordinal++, part.file)
            is StreamPart.SourcePart -> standalone += IndexedContent(ordinal++, part.source)
            is StreamPart.CustomPart -> standalone += IndexedContent(ordinal++, part.custom)

            is StreamPart.Finish -> {
                usage = part.usage
                finishReason = part.finishReason
                providerMetadata = part.providerMetadata
            }

            is StreamPart.Error -> if (onError != null) onError(part.error) else throw part.error
            is StreamPart.Raw -> Unit
        }
    }

    val assembled = blocks.values.map { block ->
        IndexedContent(
            block.ordinal,
            when (block.kind) {
                Kind.Text -> Content.Text(block.text.toString(), block.metadata)
                Kind.Reasoning -> Content.Reasoning(block.text.toString(), block.metadata)
            },
        )
    }

    return GenerateResult(
        content = (assembled + standalone).sortedBy { it.ordinal }.map { it.content },
        finishReason = finishReason,
        usage = usage,
        warnings = warnings,
        providerMetadata = providerMetadata,
        response = response,
    )
}

private enum class Kind { Text, Reasoning }

/**
 * Text and reasoning ids are namespaced separately.
 *
 * Anthropic keys both by the same content-block index, so a shared map would have a reasoning block at
 * index 0 collide with a text block at index 0 and one would overwrite the other.
 */
private fun key(kind: Kind, id: String): String = "${kind.name}:$id"

private class OpenBlock(val kind: Kind, val ordinal: Int) {
    val text = StringBuilder()
    var metadata: ProviderMetadata? = null

    /** Merges per provider, so two vendors' payloads on one block do not clobber each other. */
    fun mergeMetadata(incoming: ProviderMetadata?) {
        if (incoming == null) return
        val current = metadata
        metadata = if (current == null) incoming else current + incoming
    }
}

private class IndexedContent(val ordinal: Int, val content: Content)
