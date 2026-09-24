package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.StreamPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The reference's own `smooth-stream.test.ts` cases, translated to our [RunEvent] stream.
 *
 * What these pin is invisible in a demo and immediately visible in an IME: that fragments recombine on
 * word boundaries rather than on the server's packet boundaries, that a buffered word is flushed BEFORE
 * the tool call that interrupted it (ordering), and that a vendor payload riding a delta survives the
 * re-chunking. The delay assertions run on `runTest`'s virtual clock, so they assert the schedule
 * exactly rather than sleeping through it.
 */
class SmoothStreamTest {

    private fun text(vararg deltas: String, id: String = "t", step: Int = 0): List<RunEvent> =
        deltas.map { RunEvent.Part(StreamPart.TextDelta(id, it), step) }

    private fun reasoning(vararg deltas: String, id: String = "r", step: Int = 0): List<RunEvent> =
        deltas.map { RunEvent.Part(StreamPart.ReasoningDelta(id, it), step) }

    private fun finish(): RunEvent =
        RunEvent.StepFinish(
            Step(
                content = emptyList(),
                finishReason = com.sabreware.aide.aisdk.FinishReason(
                    com.sabreware.aide.aisdk.FinishReason.Unified.Stop,
                ),
                usage = com.sabreware.aide.aisdk.Usage(),
            ),
            0,
        )

    private suspend fun kotlinx.coroutines.flow.Flow<RunEvent>.textChunks(): List<String> =
        toList().mapNotNull { ((it as? RunEvent.Part)?.part as? StreamPart.TextDelta)?.delta }

    // --- word chunking -------------------------------------------------------------------------------

    @Test
    fun `partial words are combined before they are emitted`() = runTest {
        val chunks = (text("Hello", ", ", "world!") + finish()).asFlow()
            .smoothStream(delayMs = null)
            .textChunks()

        // "Hello" alone is not a word boundary; the boundary arrives with ", " and the tail flushes
        // when the finish forces it.
        assertEquals(listOf("Hello, ", "world!"), chunks)
    }

    @Test
    fun `a large fragment is split into words`() = runTest {
        val chunks = (text("Hello, World! This is an example text.") + finish()).asFlow()
            .smoothStream(delayMs = null)
            .textChunks()

        assertEquals(
            listOf("Hello, ", "World! ", "This ", "is ", "an ", "example ", "text."),
            chunks,
        )
    }

    @Test
    fun `longer whitespace runs stay attached to their word`() = runTest {
        // The reference's own case, expectation for expectation: a whitespace run rides out with the
        // word BEFORE it, and whitespace opening a fragment attaches to the word AFTER it.
        val chunks = (text("First line", " \n\n", "  ", "  Multiple spaces", "\n    Indented") + finish())
            .asFlow()
            .smoothStream(delayMs = null)
            .textChunks()

        assertEquals(
            listOf("First ", "line \n\n", "    Multiple ", "spaces\n    ", "Indented"),
            chunks,
        )
    }

    // --- flushing on non-smoothable parts ------------------------------------------------------------

    @Test
    fun `buffered text is flushed before a tool call passes through`() = runTest {
        val call = RunEvent.Part(
            StreamPart.ToolCallPart(Content.ToolCall("call-1", "search", "{}")),
            0,
        )
        val events = (text("I will check", " that now") + call + finish()).asFlow()
            .smoothStream(delayMs = null)
            .toList()

        val kinds = events.map { ((it as? RunEvent.Part)?.part)?.let { p -> p::class.simpleName } ?: "step" }
        // The buffered tail must precede the tool call, or the transcript reads as the model calling a
        // tool before saying why.
        assertEquals(
            listOf("TextDelta", "TextDelta", "TextDelta", "TextDelta", "TextDelta", "ToolCallPart", "step"),
            kinds,
        )
        assertEquals(listOf("I ", "will ", "check ", "that ", "now"), events.textChunksOf())
    }

    private fun List<RunEvent>.textChunksOf(): List<String> =
        mapNotNull { ((it as? RunEvent.Part)?.part as? StreamPart.TextDelta)?.delta }

    @Test
    fun `a stream that ends mid-word still delivers the tail`() = runTest {
        // No finish part at all — a cut stream. The reference drops this tail; we flush on completion.
        val chunks = text("Hello, wor", "ld").asFlow()
            .smoothStream(delayMs = null)
            .textChunks()

        assertEquals(listOf("Hello, ", "world"), chunks)
    }

    // --- line chunking -------------------------------------------------------------------------------

    @Test
    fun `line chunking emits whole lines`() = runTest {
        val chunks = (text("First line\nSecond ", "line\nThird") + finish()).asFlow()
            .smoothStream(delayMs = null, chunking = SmoothChunking.Line)
            .textChunks()

        assertEquals(listOf("First line\n", "Second line\n", "Third"), chunks)
    }

    @Test
    fun `text without line endings arrives once, at the flush`() = runTest {
        val chunks = (text("No newlines here at all") + finish()).asFlow()
            .smoothStream(delayMs = null, chunking = SmoothChunking.Line)
            .textChunks()

        assertEquals(listOf("No newlines here at all"), chunks)
    }

    // --- custom chunking -----------------------------------------------------------------------------

    @Test
    fun `a regex that matches mid-buffer takes the text before it too`() = runTest {
        // The chunk is prefix + match — slicing at the match alone would silently drop "ab".
        val chunks = (text("abXcdX") + finish()).asFlow()
            .smoothStream(delayMs = null, chunking = SmoothChunking.ByRegex(Regex("X")))
            .textChunks()

        assertEquals(listOf("abX", "cdX"), chunks)
    }

    @Test
    fun `character-level regex chunking works`() = runTest {
        val chunks = (text("Hello") + finish()).asFlow()
            .smoothStream(delayMs = null, chunking = SmoothChunking.ByRegex(Regex(".")))
            .textChunks()

        assertEquals(listOf("H", "e", "l", "l", "o"), chunks)
    }

    @Test
    fun `a regex that can match the empty string is rejected`() = runTest {
        assertFailsWith<InvalidArgumentError> {
            (text("hi") + finish()).asFlow()
                .smoothStream(delayMs = null, chunking = SmoothChunking.ByRegex(Regex("x?")))
                .toList()
        }
    }

    @Test
    fun `a custom detector drives the boundaries`() = runTest {
        // Fixed-size chunks of 3 — a boundary no regex on content can express.
        val detector = ChunkDetector { buffer -> buffer.takeIf { it.length >= 3 }?.take(3) }
        val chunks = (text("abcdefgh") + finish()).asFlow()
            .smoothStream(delayMs = null, chunking = SmoothChunking.Custom(detector))
            .textChunks()

        assertEquals(listOf("abc", "def", "gh"), chunks)
    }

    @Test
    fun `a detector returning an empty match is rejected`() = runTest {
        assertFailsWith<InvalidArgumentError> {
            (text("hi") + finish()).asFlow()
                .smoothStream(delayMs = null, chunking = SmoothChunking.Custom { "" })
                .toList()
        }
    }

    @Test
    fun `a detector returning a non-prefix is rejected`() = runTest {
        assertFailsWith<InvalidArgumentError> {
            (text("hello") + finish()).asFlow()
                .smoothStream(delayMs = null, chunking = SmoothChunking.Custom { "ello" })
                .toList()
        }
    }

    // --- pacing --------------------------------------------------------------------------------------

    @Test
    fun `the default delay is ten milliseconds per chunk`() = runTest {
        val times = mutableListOf<Long>()
        (text("one two three ") + finish()).asFlow()
            .smoothStream()
            .collect { event ->
                if ((event as? RunEvent.Part)?.part is StreamPart.TextDelta) {
                    times += testScheduler.currentTime
                }
            }

        // Three word chunks; each emission is followed by the delay, so they land 10ms apart starting
        // at zero.
        assertEquals(listOf(0L, 10L, 20L), times)
    }

    @Test
    fun `a custom delay is honoured`() = runTest {
        val times = mutableListOf<Long>()
        (text("one two ") + finish()).asFlow()
            .smoothStream(delayMs = 25)
            .collect { event ->
                if ((event as? RunEvent.Part)?.part is StreamPart.TextDelta) {
                    times += testScheduler.currentTime
                }
            }

        assertEquals(listOf(0L, 25L), times)
    }

    @Test
    fun `a null delay emits without pausing`() = runTest {
        (text("one two three four ") + finish()).asFlow()
            .smoothStream(delayMs = null)
            .toList()

        assertEquals(0L, testScheduler.currentTime)
    }

    // --- block identity ------------------------------------------------------------------------------

    @Test
    fun `an id change flushes the old block and starts a new one`() = runTest {
        val events = (text("first block", id = "a") + text("second block", id = "b") + finish()).asFlow()
            .smoothStream(delayMs = null)
            .toList()

        val byId = events.mapNotNull { (it as? RunEvent.Part)?.part as? StreamPart.TextDelta }
            .map { it.id to it.delta }
        assertEquals(
            listOf("a" to "first ", "a" to "block", "b" to "second ", "b" to "block"),
            byId,
        )
    }

    @Test
    fun `reasoning deltas are smoothed too, separately from text`() = runTest {
        val events = (reasoning("thinking about", " it") + text("the answer") + finish()).asFlow()
            .smoothStream(delayMs = null)
            .toList()

        val reasoningChunks = events
            .mapNotNull { (it as? RunEvent.Part)?.part as? StreamPart.ReasoningDelta }
            .map { it.delta }
        // The reasoning buffer flushes when the stream switches to text — the two must not blend.
        assertEquals(listOf("thinking ", "about ", "it"), reasoningChunks)
        assertEquals(listOf("the ", "answer"), events.textChunksOf())
    }

    // --- provider metadata ---------------------------------------------------------------------------

    @Test
    fun `metadata riding a delta survives onto the flushed chunk`() = runTest {
        val meta = mapOf("anthropic" to buildJsonObject { put("signature", "sig-1") })
        val events = (
            listOf<RunEvent>(
                RunEvent.Part(StreamPart.ReasoningDelta("r", "signed thought", meta), 0),
            ) + finish()
            ).asFlow()
            .smoothStream(delayMs = null)
            .toList()

        val flushed = events.mapNotNull { (it as? RunEvent.Part)?.part as? StreamPart.ReasoningDelta }
        // "signed " is a chunk; "thought" is the flush, and the flush is what carries the payload.
        assertEquals(listOf(null, meta), flushed.map { it.providerMetadata })
    }

    @Test
    fun `metadata is not lost even when the buffer drained exactly at a boundary`() = runTest {
        val meta = mapOf("anthropic" to buildJsonObject { put("signature", "sig-2") })
        val events = (
            listOf<RunEvent>(
                // Trailing newline: line chunking consumes the WHOLE buffer, leaving nothing to flush.
                RunEvent.Part(StreamPart.TextDelta("t", "whole line\n", meta), 0),
            ) + finish()
            ).asFlow()
            .smoothStream(delayMs = null, chunking = SmoothChunking.Line)
            .toList()

        val deltas = events.mapNotNull { (it as? RunEvent.Part)?.part as? StreamPart.TextDelta }
        // An empty delta carries the payload — legal, merged by the assembler, stripped from replay. The
        // reference dropped it here until a51cc94; the next test is its own case for the fix.
        assertTrue(deltas.any { it.providerMetadata == meta })
    }

    @Test
    fun `metadata from an empty delta is preserved when the buffer is empty`() = runTest {
        // The reference's case for its fix — the divergence noted above was exactly this, and upstream
        // has since closed it the same way: the empty delta rides out with its payload, nothing else.
        val providerMetadata = mapOf("anthropic" to buildJsonObject { put("signature", "sig_abc123") })
        val events = listOf<RunEvent>(
            RunEvent.Part(StreamPart.ReasoningStart("r1"), 0),
            RunEvent.Part(StreamPart.ReasoningDelta("r1", "Let me think. "), 0),
            RunEvent.Part(StreamPart.ReasoningDelta("r1", "", providerMetadata), 0),
            RunEvent.Part(StreamPart.ReasoningEnd("r1"), 0),
            RunEvent.Part(StreamPart.TextStart("t1"), 0),
            RunEvent.Part(StreamPart.TextDelta("t1", "Hello world"), 0),
            RunEvent.Part(StreamPart.TextEnd("t1"), 0),
        ).asFlow().smoothStream(delayMs = null).toList().map { (it as RunEvent.Part).part }

        assertEquals(
            listOf(
                StreamPart.ReasoningStart("r1"),
                StreamPart.ReasoningDelta("r1", "Let "),
                StreamPart.ReasoningDelta("r1", "me "),
                StreamPart.ReasoningDelta("r1", "think. "),
                StreamPart.ReasoningDelta("r1", "", providerMetadata),
                StreamPart.ReasoningEnd("r1"),
                StreamPart.TextStart("t1"),
                StreamPart.TextDelta("t1", "Hello "),
                StreamPart.TextDelta("t1", "world"),
                StreamPart.TextEnd("t1"),
            ),
            events,
        )
    }
}
