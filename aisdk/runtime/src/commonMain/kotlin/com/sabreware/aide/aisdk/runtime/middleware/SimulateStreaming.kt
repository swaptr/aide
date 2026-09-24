package com.sabreware.aide.aisdk.runtime.middleware

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.LanguageModelMiddleware
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Answers a streaming call by generating, then replaying the result as a stream.
 *
 * For the model that has no streaming endpoint at all — several image-to-text and self-hosted
 * inference servers among them. Without this, a UI built on `streamText` cannot show that model's
 * answer, and the alternative is a per-model branch at the seam that renders text.
 *
 * It is a simulation and does not pretend otherwise: nothing arrives until everything has, so the
 * time-to-first-token is the whole generation. That is the honest trade, and it is why this is opt-in
 * per model rather than a fallback the runtime applies whenever a stream looks slow.
 *
 * Reasoning parts keep their `providerMetadata` onto the [StreamPart.ReasoningEnd], because that is
 * where a signature lives and the assembler reads it from there. Replaying a signed thinking block
 * without its signature produces a turn Anthropic rejects on the next round.
 */
public fun simulateStreaming(): LanguageModelMiddleware = object : LanguageModelMiddleware {

    override suspend fun wrapStream(
        params: CallOptions,
        model: LanguageModel,
        doStream: suspend () -> StreamResult,
    ): StreamResult {
        val result = model.doGenerate(params)
        return StreamResult(
            stream = flow {
                emit(StreamPart.StreamStart(result.warnings))
                result.response?.metadata?.let { emit(StreamPart.ResponseMetadataPart(it)) }
                result.content.forEachIndexed { index, part -> replay(index.toString(), part) }
                emit(
                    StreamPart.Finish(
                        usage = result.usage,
                        finishReason = result.finishReason,
                        providerMetadata = result.providerMetadata,
                    ),
                )
            },
            request = result.request,
            response = result.response,
        )
    }
}

private suspend fun FlowCollector<StreamPart>.replay(id: String, part: Content) {
    when (part) {
        is Content.Text -> {
            // An empty text part is dropped rather than replayed as a start/end pair with nothing
            // between: a consumer counting text blocks would see one that never existed.
            if (part.text.isNotEmpty()) {
                emit(StreamPart.TextStart(id, part.providerMetadata))
                emit(StreamPart.TextDelta(id, part.text))
                emit(StreamPart.TextEnd(id))
            }
        }
        is Content.Reasoning -> {
            emit(StreamPart.ReasoningStart(id))
            emit(StreamPart.ReasoningDelta(id, part.text))
            emit(StreamPart.ReasoningEnd(id, part.providerMetadata))
        }
        is Content.ToolCall -> emit(StreamPart.ToolCallPart(part))
        is Content.ToolResult -> emit(StreamPart.ToolResultPart(part))
        is Content.ToolApprovalRequest -> emit(StreamPart.ToolApprovalRequestPart(part))
        is Content.File -> emit(StreamPart.FilePart(part))
        is Content.ReasoningFile -> emit(StreamPart.ReasoningFilePart(part))
        is Content.Source -> emit(StreamPart.SourcePart(part))
        is Content.Custom -> emit(StreamPart.CustomPart(part))
    }
}
