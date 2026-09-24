package com.sabreware.aide.aisdk.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Line framing for a byte stream, which is where two silent data losses lived.
 *
 * A bare CR is the first terminator the SSE specification lists and the one Ktor's own `readLine` does
 * not split on, so a server that uses it produced a single unterminated line and a stream that looked
 * like a model refusing to answer. A leading byte-order mark rode into the first field name and cost the
 * first event of every stream behind a gateway that emits one.
 */
class LineSplitterTest {

    private fun split(vararg chunks: String): List<String> {
        val splitter = LineSplitter()
        val out = chunks.flatMap { splitter.feed(it.encodeToByteArray()) }.toMutableList()
        splitter.flush()?.let { out += it }
        return out
    }

    @Test
    fun `each of the three terminators ends a line`() {
        assertEquals(listOf("a", "b"), split("a\nb\n"))
        assertEquals(listOf("a", "b"), split("a\rb\r"))
        assertEquals(listOf("a", "b"), split("a\r\nb\r\n"))
    }

    @Test
    fun `a CRLF split across chunks is one terminator, not two lines`() {
        // The blank line this would invent dispatches an SSE event early, cutting a multi-line frame in
        // half — and the halves are two invalid JSON documents rather than one valid one.
        assertEquals(listOf("a", "b"), split("a\r", "\nb\n"))
    }

    @Test
    fun `a line split across chunks is rejoined`() {
        assertEquals(listOf("data: hello"), split("data: he", "llo\n"))
    }

    @Test
    fun `a multi-byte character split across chunks survives`() {
        // Decoding per chunk rather than per line turns the halves into two replacement characters.
        val bytes = "é".encodeToByteArray()
        val splitter = LineSplitter()

        assertEquals(emptyList(), splitter.feed(byteArrayOf(bytes[0])))
        assertEquals(listOf("é"), splitter.feed(byteArrayOf(bytes[1], '\n'.code.toByte())))
    }

    @Test
    fun `a leading byte-order mark is stripped from the first line only`() {
        assertEquals(listOf("data: a", "data: ﻿b"), split("﻿data: a\ndata: ﻿b\n"))
    }

    @Test
    fun `consecutive terminators produce the empty lines that dispatch events`() {
        assertEquals(listOf("data: a", "", "data: b"), split("data: a\n\ndata: b\n"))
    }

    @Test
    fun `flush yields a final line the server closed without terminating`() {
        assertEquals(listOf("a", "b"), split("a\nb"))
    }

    @Test
    fun `asLines frames a chunk flow`() = runTest {
        val chunks = listOf("data: on", "e\n\ndata: two\n\n").map { it.encodeToByteArray() }

        assertEquals(
            listOf("data: one", "", "data: two", ""),
            chunks.asFlow().asLines().toList(),
        )
    }

    @Test
    fun `a bare-CR stream frames into events end to end`() = runTest {
        val chunks = listOf("data: one\r\rdata: two\r\r".encodeToByteArray())

        assertEquals(
            listOf("one", "two"),
            chunks.asFlow().asLines().asSseEvents().toList().map { it.data },
        )
    }
}
