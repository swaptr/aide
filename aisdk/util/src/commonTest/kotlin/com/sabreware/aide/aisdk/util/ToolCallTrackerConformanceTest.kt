package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.StreamPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The reference's `provider-utils/src/streaming-tool-call-tracker` cases, translated.
 *
 * Our own `ToolCallTrackerTest` pins the concatenation rule; these are the cases around it that the
 * reference spends most of its file on, and every one of them is a way a stream of fragments can be
 * mis-assembled into JSON that still parses. A tracker that closed a call as soon as its arguments
 * looked complete would truncate every call whose first fragment happens to be a whole object; one that
 * keyed on the index alone would merge two calls a server numbered the same and split one a server
 * stopped numbering; one that accepted a late fragment would mutate input the caller was already handed
 * as final. None of those fail loudly — the model is simply called with the wrong arguments.
 */
class ToolCallTrackerConformanceTest {

    @Test
    fun `an argument prefix that already parses does not close the call early`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        // The whole first fragment is valid JSON on its own, and more arrives after it. A tracker that
        // finalized on "the arguments parse" would emit a call missing everything that followed.
        tracker.accept(recorder, index = 0, id = "call_1", name = "search", argumentsFragment = """{"query": "test"}""")

        assertEquals(
            listOf("tool-input-start", "tool-input-delta"),
            recorder.kinds(),
        )

        tracker.accept(recorder, index = 0, id = null, name = null, argumentsFragment = """, "limit": 10}""")
        tracker.finish(recorder)

        val calls = recorder.calls()
        assertEquals(1, calls.size)
        assertEquals("""{"query": "test"}, "limit": 10}""", calls.single().input)
    }

    @Test
    fun `indexes that are neither zero-based nor contiguous keep their own calls`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.accept(recorder, index = 1, id = "call_1", name = "fn1", argumentsFragment = """{"value":1}""")
        tracker.accept(recorder, index = 3, id = "call_2", name = "fn2", argumentsFragment = """{"value":2}""")
        tracker.finish(recorder)

        assertEquals(listOf("call_1", "call_2"), recorder.calls().map { it.toolCallId })
        assertEquals(listOf("""{"value":1}""", """{"value":2}"""), recorder.calls().map { it.input })
    }

    @Test
    fun `two calls a server numbered the same stay distinct because their ids differ`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.accept(recorder, index = 0, id = "call_1", name = "fn", argumentsFragment = """{"value":1}""")
        tracker.accept(recorder, index = 0, id = "call_2", name = "fn", argumentsFragment = """{"value":2}""")
        tracker.finish(recorder)

        // Keying on the index alone merges these into one unparseable blob.
        assertEquals(listOf("call_1", "call_2"), recorder.calls().map { it.toolCallId })
        assertEquals(listOf("""{"value":1}""", """{"value":2}"""), recorder.calls().map { it.input })
    }

    @Test
    fun `a continuation whose id is the empty string falls back to the index`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.accept(recorder, index = 0, id = "call_1", name = "fn", argumentsFragment = """{"val""")
        // An empty id is not a new call: several wires send "" rather than omitting the field.
        tracker.accept(recorder, index = 0, id = "", name = null, argumentsFragment = """ue":1}""")
        tracker.finish(recorder)

        assertEquals(1, recorder.calls().size)
        assertEquals("""{"value":1}""", recorder.calls().single().input)
    }

    @Test
    fun `a continuation with neither id nor index continues the most recent call`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.accept(recorder, index = 7, id = "call_1", name = "fn", argumentsFragment = """{"val""")
        tracker.accept(recorder, index = null, id = null, name = null, argumentsFragment = """ue":1}""")
        tracker.finish(recorder)

        assertEquals(1, recorder.calls().size)
        assertEquals("""{"value":1}""", recorder.calls().single().input)
    }

    @Test
    fun `a fragment arriving after the call was closed is ignored, not appended`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.accept(recorder, index = 0, id = "call_1", name = "fn", argumentsFragment = "{}")
        tracker.finish(recorder)
        recorder.clear()

        tracker.accept(recorder, index = 0, id = null, name = null, argumentsFragment = "extra")

        // Appending would mutate input the caller has already been handed as complete.
        assertEquals(emptyList(), recorder.kinds())
    }

    @Test
    fun `a fragment with no arguments opens the call but emits no delta`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.accept(recorder, index = 0, id = "call_1", name = "fn", argumentsFragment = null)

        assertEquals(listOf("tool-input-start"), recorder.kinds())

        tracker.accept(recorder, index = 0, id = null, name = null, argumentsFragment = null)

        assertEquals(listOf("tool-input-start"), recorder.kinds())
    }

    @Test
    fun `finishing twice does not emit the same call again`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.accept(recorder, index = 0, id = "call_1", name = "fn", argumentsFragment = "{}")
        tracker.finish(recorder)
        tracker.finish(recorder)

        // A caller that flushes defensively must not double-dispatch a tool.
        assertEquals(1, recorder.calls().size)
    }

    @Test
    fun `a call left open when the stream ends is still completed by finish`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.accept(recorder, index = 0, id = "call_1", name = "fn", argumentsFragment = """{"a":""")
        tracker.accept(recorder, index = 0, id = null, name = null, argumentsFragment = """1}""")
        tracker.finish(recorder)

        assertEquals(
            listOf("tool-input-start", "tool-input-delta", "tool-input-delta", "tool-input-end", "tool-call"),
            recorder.kinds(),
        )
        assertEquals("""{"a":1}""", recorder.calls().single().input)
    }

    @Test
    fun `provider metadata attached to any fragment reaches the finished call`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.accept(
            collector = recorder,
            index = 0,
            id = "call_1",
            name = "fn",
            argumentsFragment = """{"a":""",
            providerMetadata = mapOf("openai" to buildJsonObject { put("itemId", "item-1") }),
        )
        // A vendor is as likely to attach its data to the LAST fragment as to the first, so a tracker
        // that only reads the opening one loses exactly what this port exists to preserve.
        tracker.accept(
            collector = recorder,
            index = 0,
            id = null,
            name = null,
            argumentsFragment = """1}""",
            providerMetadata = mapOf("extra" to buildJsonObject { put("seen", true) }),
        )
        tracker.finish(recorder)

        val metadata = recorder.calls().single().providerMetadata
        assertEquals(setOf("openai", "extra"), metadata?.keys)
    }

    @Test
    fun `a stream with no tool calls reports nothing to dispatch`() = runTest {
        val recorder = Recorder()
        val tracker = ToolCallTracker()

        tracker.finish(recorder)

        assertEquals(emptyList(), recorder.kinds())
    }
}

/** Collects the stream parts a tracker emits, so a case can assert on their order and their kinds. */
private class Recorder : FlowCollector<StreamPart> {

    private val parts = mutableListOf<StreamPart>()

    override suspend fun emit(value: StreamPart) {
        parts += value
    }

    fun clear() {
        parts.clear()
    }

    fun kinds(): List<String> = parts.map {
        when (it) {
            is StreamPart.ToolInputStart -> "tool-input-start"
            is StreamPart.ToolInputDelta -> "tool-input-delta"
            is StreamPart.ToolInputEnd -> "tool-input-end"
            is StreamPart.ToolCallPart -> "tool-call"
            else -> "other"
        }
    }

    fun calls(): List<Content.ToolCall> =
        parts.filterIsInstance<StreamPart.ToolCallPart>().map { it.toolCall }
}
