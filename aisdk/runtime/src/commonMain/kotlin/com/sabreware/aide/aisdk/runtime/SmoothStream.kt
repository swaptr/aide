package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.StreamPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Finds the first complete chunk at the START of the buffer, or null if none has formed yet.
 *
 * The prefix rule is a contract, not a convention: the operator removes exactly what was returned from
 * the front of its buffer, so a detector that returns text from the middle would silently drop the
 * characters before it. [smoothStream] therefore rejects a non-prefix match loudly instead.
 */
public fun interface ChunkDetector {

    /** @return the chunk to emit — a non-empty PREFIX of [buffer] — or null to wait for more text. */
    public fun firstChunk(buffer: String): String?
}

/**
 * How [smoothStream] decides where one displayed chunk ends and the next begins.
 *
 * A model streams text in whatever fragments its server happened to batch — half a word, three
 * sentences — and rendering those fragments as they arrive makes the text stutter. Chunking rebuilds the
 * boundaries a reader actually perceives.
 */
public sealed interface SmoothChunking {

    /** Whole words, whitespace attached. The default, and what a chat surface almost always wants. */
    public data object Word : SmoothChunking

    /** Whole lines. For content whose unit is the line — code, lyrics, a list. */
    public data object Line : SmoothChunking

    /**
     * Chunks end where [regex] first matches: the emitted chunk is everything up to AND including the
     * match. The pattern must never match an empty string — an empty match would emit nothing and ask
     * again against the same buffer, forever.
     */
    public data class ByRegex(val regex: Regex) : SmoothChunking

    /** A caller-supplied [ChunkDetector], for boundaries no regex expresses — locale-aware wordbreaks. */
    public data class Custom(val detector: ChunkDetector) : SmoothChunking
}

/**
 * Smooths text and reasoning deltas into evenly-paced, boundary-aligned chunks.
 *
 * Everything that is not a text or reasoning delta passes through untouched, and any buffered text is
 * flushed FIRST — so ordering is preserved: a tool call never overtakes the words that preceded it.
 * Buffered text is also flushed when the open block changes (a different id, the other delta type, a new
 * step), so two adjacent blocks never blend into one chunk.
 *
 * A delta's `providerMetadata` is captured and re-attached to the next flushed chunk of its block — and
 * if the block closes with nothing left to flush, to an empty delta emitted just before whatever forced
 * the flush. The reference dropped the payload in that last case until `ai@7.0.9x` (a51cc94), and this
 * port never did, because losing a vendor payload the provider chose to attach is its founding bug; the
 * two now agree, and the reference's own case is pinned in `SmoothStreamTest`.
 *
 * ```kotlin
 * streamText(model, prompt)
 *     .smoothStream()
 *     .collect { event -> /* text now arrives word by word, 10ms apart */ }
 * ```
 *
 * @param delayMs pause after each emitted chunk; null emits as fast as the collector consumes. The
 *   default 10ms matches the reference and reads as typing rather than as teleporting.
 * @param chunking where chunk boundaries fall — see [SmoothChunking].
 */
public fun Flow<RunEvent>.smoothStream(
    delayMs: Long? = SMOOTH_DELAY_MS,
    chunking: SmoothChunking = SmoothChunking.Word,
): Flow<RunEvent> = flow {
    val smoother = Smoother(chunking.asDetector(), delayMs)
    collect { event ->
        val delta = (event as? RunEvent.Part)?.smoothableDelta()
        if (delta == null) {
            smoother.flushInto(this)
            emit(event)
        } else {
            smoother.append(delta, this)
        }
    }
    // A flow that ends mid-block still owes the collector its tail. The reference has no flush at all
    // and relies on a finish part always arriving; here the tail survives even a stream that was cut.
    smoother.flushInto(this)
}

/** The word and line boundaries the reference hard-codes, spelled the same way. */
private val WORD_BOUNDARY = Regex("""\S+\s+""")
private val LINE_BOUNDARY = Regex("\n+")

/** The reference's default pacing: fast enough to feel live, slow enough to read as typing. */
private const val SMOOTH_DELAY_MS: Long = 10

private fun SmoothChunking.asDetector(): ChunkDetector = when (this) {
    SmoothChunking.Word -> WORD_BOUNDARY.asDetector()
    SmoothChunking.Line -> LINE_BOUNDARY.asDetector()
    is SmoothChunking.ByRegex -> regex.asDetector()
    is SmoothChunking.Custom -> ChunkDetector { buffer ->
        detector.firstChunk(buffer)?.also { match ->
            if (match.isEmpty()) {
                throw InvalidArgumentError("Chunking function must return a non-empty string.", "chunking")
            }
            if (!buffer.startsWith(match)) {
                throw InvalidArgumentError(
                    "Chunking function must return a prefix of the buffer; got \"$match\".",
                    "chunking",
                )
            }
        }
    }
}

/**
 * A regex boundary: the chunk is everything up to and including the first match, so a pattern that
 * matches mid-buffer still flushes the text before it rather than skipping it.
 */
private fun Regex.asDetector(): ChunkDetector = ChunkDetector { buffer ->
    find(buffer)?.let { match ->
        if (match.value.isEmpty()) {
            throw InvalidArgumentError("Chunking regex must not match an empty string.", "chunking")
        }
        buffer.substring(0, match.range.first) + match.value
    }
}

/** One smoothable delta, flattened so text and reasoning share the buffering logic. */
private class SmoothableDelta(
    val reasoning: Boolean,
    val id: String,
    val text: String,
    val providerMetadata: ProviderMetadata?,
    val stepIndex: Int,
)

private fun RunEvent.Part.smoothableDelta(): SmoothableDelta? = when (val streamed = part) {
    is StreamPart.TextDelta ->
        SmoothableDelta(reasoning = false, streamed.id, streamed.delta, streamed.providerMetadata, stepIndex)
    is StreamPart.ReasoningDelta ->
        SmoothableDelta(reasoning = true, streamed.id, streamed.delta, streamed.providerMetadata, stepIndex)
    else -> null
}

/**
 * The buffer and the identity of the block it belongs to.
 *
 * One buffer, not one per block: blocks do not interleave WITHIN a delta stream — a new id means the
 * previous block is done being appended to — so a change of identity is a flush point, not a second
 * buffer.
 */
private class Smoother(private val detector: ChunkDetector, private val delayMs: Long?) {

    private var buffer = ""
    private var reasoning = false
    private var id = ""
    private var stepIndex = 0
    private var pendingMetadata: ProviderMetadata? = null

    suspend fun append(delta: SmoothableDelta, collector: FlowCollector<RunEvent>) {
        val sameBlock = delta.reasoning == reasoning && delta.id == id && delta.stepIndex == stepIndex
        if (!sameBlock) flushInto(collector)

        buffer += delta.text
        reasoning = delta.reasoning
        id = delta.id
        stepIndex = delta.stepIndex
        // Latest wins, as in the reference: a block that attaches metadata twice meant the second one.
        delta.providerMetadata?.let { pendingMetadata = it }

        while (true) {
            val chunk = detector.firstChunk(buffer) ?: break
            buffer = buffer.substring(chunk.length)
            collector.emit(chunkEvent(chunk, metadata = null))
            delayMs?.let { delay(it) }
        }
    }

    /** Emits whatever is buffered — and a pending vendor payload even when no text is. */
    suspend fun flushInto(collector: FlowCollector<RunEvent>) {
        if (buffer.isEmpty() && pendingMetadata == null) return
        collector.emit(chunkEvent(buffer, pendingMetadata))
        buffer = ""
        pendingMetadata = null
    }

    private fun chunkEvent(text: String, metadata: ProviderMetadata?): RunEvent.Part = RunEvent.Part(
        part = if (reasoning) {
            StreamPart.ReasoningDelta(id, text, metadata)
        } else {
            StreamPart.TextDelta(id, text, metadata)
        },
        stepIndex = stepIndex,
    )
}
