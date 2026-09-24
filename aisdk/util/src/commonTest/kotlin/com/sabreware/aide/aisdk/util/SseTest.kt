package com.sabreware.aide.aisdk.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * SSE framing, per the WHATWG rules rather than per what one vendor happens to emit.
 *
 * The multi-line case is the one that matters: every vendor currently sends a single `data:` line per
 * event, so a parser that treats each line as a complete JSON document appears to work — right up until
 * a legal multi-line frame arrives and the decode error kills the whole generation.
 */
class SseTest {

    private fun parse(vararg lines: String): List<SseEvent> {
        val parser = SseParser()
        val out = lines.mapNotNull { parser.feed(it) }.toMutableList()
        parser.flush()?.let { out += it }
        return out
    }

    @Test
    fun `data lines since the last dispatch join with a newline`() {
        val events = parse("data: {\"a\":1,", "data: \"b\":2}", "")

        assertEquals(1, events.size)
        assertEquals("{\"a\":1,\n\"b\":2}", events.single().data)
    }

    @Test
    fun `exactly one leading space after the colon is stripped`() {
        // "data:  x" keeps one space; "data:x" has none to strip.
        assertEquals(" x", parse("data:  x", "").single().data)
        assertEquals("x", parse("data:x", "").single().data)
    }

    @Test
    fun `the event field is captured and resets per event`() {
        val events = parse(
            "event: content_block_delta",
            "data: {}",
            "",
            "data: {}",
            "",
        )

        assertEquals(2, events.size)
        assertEquals("content_block_delta", events[0].event)
        // The name does not leak into the next event — vendors interleave named and unnamed frames.
        assertNull(events[1].event)
    }

    @Test
    fun `comments and unknown fields are ignored`() {
        // A bare colon is the conventional keep-alive; proxies inject them to hold the connection open.
        val events = parse(": keep-alive", "id: 42", "retry: 1000", "data: payload", "")

        assertEquals(1, events.size)
        assertEquals("payload", events.single().data)
    }

    @Test
    fun `a blank line with no data dispatches nothing`() {
        assertEquals(emptyList(), parse("", "", ""))
    }

    @Test
    fun `a whitespace-only line does not dispatch`() {
        // A line of spaces is a field named with spaces, not the empty line that terminates an event.
        // Dispatching on it cuts the event short and drops every data line after it.
        val events = parse("data: a", " ", "data: b", "")

        assertEquals(1, events.size)
        assertEquals("a\nb", events.single().data)
    }

    @Test
    fun `a field with no colon is that field with an empty value`() {
        // How a vendor sends an empty delta. Falling through instead loses the line and, with it, the
        // fact that an event was dispatched at all.
        val events = parse("data", "")

        assertEquals(1, events.size)
        assertEquals("", events.single().data)
    }

    @Test
    fun `a bare event field clears the name rather than naming it empty`() {
        assertNull(parse("event", "data: x", "").single().event)
    }

    @Test
    fun `flush dispatches a body the server closed without a blank line`() {
        // Anthropic ends the stream by closing after message_stop, with no terminating blank line.
        val events = parse("data: last")

        assertEquals(1, events.size)
        assertEquals("last", events.single().data)
    }

    @Test
    fun `asSseEvents frames a line flow`() = runTest {
        val lines = listOf("data: one", "", "data: two", "")

        assertEquals(
            listOf("one", "two"),
            lines.asFlow().asSseEvents().toList().map { it.data },
        )
    }

    @Test
    fun `stopAtDone ends the stream and drops the sentinel`() = runTest {
        val lines = listOf("data: one", "", "data: $SSE_DONE", "", "data: never", "")

        val events = lines.asFlow().asSseEvents().stopAtDone().toList()

        assertEquals(listOf("one"), events.map { it.data })
    }

    @Test
    fun `without stopAtDone the sentinel is an ordinary event`() {
        // Anthropic never sends it, so the parser must not treat it as special on its own.
        assertEquals(2, parse("data: a", "", "data: $SSE_DONE", "").size)
    }
}
