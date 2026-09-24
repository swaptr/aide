package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.StreamPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Partial tool-call assembly, shared by every wire that streams arguments in fragments.
 *
 * The failure this guards is the quiet one: treating fragments as cumulative doubles every argument
 * string, producing JSON that still parses and means something else entirely.
 */
class ToolCallTrackerTest {

    private fun track(block: suspend ToolCallTracker.(collector: kotlinx.coroutines.flow.FlowCollector<StreamPart>) -> Unit) =
        flow {
            val tracker = ToolCallTracker()
            tracker.block(this)
            tracker.finish(this)
        }

    @Test
    fun `fragments concatenate rather than replace`() = runTest {
        val parts = track { collector ->
            accept(collector, index = 0, id = "c1", name = "lookup", argumentsFragment = """{"q":""")
            accept(collector, index = 0, id = null, name = null, argumentsFragment = """"cats"}""")
        }.toList()

        val call = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        // Cumulative handling would give {"q":{"q":"cats"} — valid JSON meaning the wrong thing.
        assertEquals("""{"q":"cats"}""", call.input)
        assertEquals("lookup", call.toolName)
    }

    @Test
    fun `calls at different indices stay separate`() = runTest {
        val calls = track { collector ->
            accept(collector, 0, "a", "one", "{}")
            accept(collector, 1, "b", "two", "{}")
        }.toList().filterIsInstance<StreamPart.ToolCallPart>().map { it.toolCall }

        assertEquals(listOf("one", "two"), calls.map { it.toolName })
        assertEquals(listOf("a", "b"), calls.map { it.toolCallId })
    }

    @Test
    fun `a server that omits the index does not merge unrelated calls`() = runTest {
        // Some compatible servers send one complete call with no index at all; keying everything under a
        // missing index would collapse them into one.
        val calls = track { collector ->
            accept(collector, null, "a", "one", "{}")
            accept(collector, null, "b", "two", "{}")
        }.toList().filterIsInstance<StreamPart.ToolCallPart>().map { it.toolCall }

        assertEquals(2, calls.size)
    }

    @Test
    fun `a call opened with no name is a bad response`() = runTest {
        // Every wire this tracker serves sends the name on the fragment that OPENS the call; a later one
        // carries argument deltas only. So a nameless opening fragment is a malformed response rather
        // than a name still to come, and waiting for one that never arrives yields a ToolCall with an
        // empty name that no tool matches.
        assertFailsWith<InvalidResponseDataError> {
            track { collector ->
                accept(collector, 0, "c1", null, null)
            }.toList()
        }
    }

    @Test
    fun `a call with no arguments still replays as valid JSON`() = runTest {
        val call = track { collector ->
            accept(collector, 0, "c1", "now", null)
        }.toList().filterIsInstance<StreamPart.ToolCallPart>().single().toolCall

        assertEquals("{}", call.input)
    }

    @Test
    fun `blocks are opened and closed around the deltas`() = runTest {
        val parts = track { collector ->
            accept(collector, 0, "c1", "lookup", "{}")
        }.toList()

        assertTrue(parts[0] is StreamPart.ToolInputStart)
        assertTrue(parts[1] is StreamPart.ToolInputDelta)
        assertTrue(parts[2] is StreamPart.ToolInputEnd)
        assertTrue(parts[3] is StreamPart.ToolCallPart)
    }

    @Test
    fun `a call opened with no id is a bad response, not a call with an invented one`() = runTest {
        // Minting one produces a `ToolCall` the runtime dispatches and nothing can answer: a tool result
        // has to reference the id the PROVIDER issued, so an invented one is unusable the moment the
        // result goes back. Reporting it as a provider fault is the only honest outcome — the previous
        // behaviour surfaced it to the user as "tool not found" for a tool they have.
        assertFailsWith<InvalidResponseDataError> {
            track { collector ->
                accept(collector, 0, null, "anon", "{}")
            }.toList()
        }
    }

    @Test
    fun `a turn with no tool calls reports empty`() {
        assertTrue(ToolCallTracker().isEmpty())
    }
}
