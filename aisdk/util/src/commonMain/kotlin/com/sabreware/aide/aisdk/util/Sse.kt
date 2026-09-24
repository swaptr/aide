package com.sabreware.aide.aisdk.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

/** One dispatched Server-Sent Event: its `event:` name, if any, and its joined `data:` payload. */
public data class SseEvent(
    /** The `event:` field, or null when the server routes on the payload alone. */
    val event: String? = null,
    /** Every `data:` line since the last dispatch, joined with newlines. */
    val data: String,
)

/**
 * Splits a byte stream into lines the way the SSE specification defines them.
 *
 * The transport's own `readLine` is not usable here. Ktor's splits on `LineEnding.Default`, which does
 * not include a bare CR — a legal SSE terminator that the specification lists first — so a server using
 * it produces one enormous line that never dispatches an event and a stream that appears to hang.
 *
 * Splitting on bytes rather than on a decoded string is what makes it safe: UTF-8 never encodes a
 * continuation byte in the ASCII range, so 0x0A and 0x0D cannot appear inside a multi-byte character,
 * and a chunk boundary landing mid-character is held in [pending] until the line completes.
 */
public class LineSplitter {

    private var pending = EMPTY
    /** A chunk that ended on CR: a LF opening the next chunk belongs to that same terminator. */
    private var pendingCr = false
    private var atStreamStart = true

    /** The complete lines in [chunk], with any terminators removed. */
    public fun feed(chunk: ByteArray): List<String> {
        if (chunk.isEmpty()) return emptyList()
        var input = chunk
        if (pendingCr) {
            pendingCr = false
            if (input[0] == LF) input = input.copyOfRange(1, input.size)
        }
        val lines = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < input.size) {
            val byte = input[i]
            if (byte != LF && byte != CR) {
                i++
                continue
            }
            lines += decode(input, start, i)
            if (byte == CR) {
                if (i + 1 < input.size) {
                    if (input[i + 1] == LF) i++
                } else {
                    pendingCr = true
                }
            }
            i++
            start = i
        }
        pending = if (start == 0) pending + input else input.copyOfRange(start, input.size)
        return lines
    }

    /** EOF: the bytes after the last terminator, which a server may close without terminating. */
    public fun flush(): String? {
        if (pending.isEmpty()) return null
        return decode(EMPTY, 0, 0)
    }

    private fun decode(input: ByteArray, from: Int, to: Int): String {
        val line = (pending + input.copyOfRange(from, to)).decodeToString()
        pending = EMPTY
        if (!atStreamStart) return line
        atStreamStart = false
        // A byte-order mark is legal at the head of a UTF-8 stream and several gateways prepend one.
        // Left in place it becomes part of the first line's field name, so `﻿data` matches nothing
        // and the first event of the stream is dropped in silence.
        return line.removePrefix("﻿")
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val LF: Byte = 0x0A
        const val CR: Byte = 0x0D
    }
}

/** Frames a byte-chunk flow into lines. See [LineSplitter]. */
public fun Flow<ByteArray>.asLines(): Flow<String> = flow {
    val splitter = LineSplitter()
    collect { chunk -> splitter.feed(chunk).forEach { emit(it) } }
    splitter.flush()?.let { emit(it) }
}

/**
 * Server-Sent-Events framing, per the WHATWG rules.
 *
 * An event's payload is EVERY `data:` line since the last dispatch, joined with `\n`, dispatched at the
 * blank line. Vendors currently emit one `data:` line per event, but the specification permits several,
 * and treating each line as a complete JSON document turns a legal multi-line frame into a decode error
 * that aborts the whole generation. That is not hypothetical — it is why AIDE's Anthropic codec grew
 * this parser in the first place.
 *
 * `event:` is captured because vendors differ on whether routing lives there or in the JSON's own `type`
 * field: Anthropic sets both, OpenAI's Responses API routes on `event:` alone. `id:`, `retry:` and `:`
 * comment lines are skipped.
 *
 * Stateful per stream, and pure line-in/event-out, so the rules stay testable without a socket. Line
 * terminators are [LineSplitter]'s business: a line arriving here is already stripped of them, so
 * "empty" here means the dispatch line and nothing else.
 */
public class SseParser {

    private val data = StringBuilder()
    private var hasData = false
    private var eventName: String? = null

    /** Feed one line; returns a complete event when it terminates (blank line), else null. */
    public fun feed(line: String): SseEvent? {
        // Only a line of length zero dispatches. A line of spaces is a field whose name is spaces —
        // dispatching on it truncates an event whose payload happens to continue after one.
        if (line.isEmpty()) return takeEvent()
        if (line.startsWith(":")) return null // comment; also the usual keep-alive
        val colon = line.indexOf(':')
        // Spec: a line with no colon is a field name with an empty value. Falling through instead loses
        // a bare `data` line, which is how a vendor sends an empty delta.
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> eventName = value.takeIf { it.isNotEmpty() }
            else -> Unit // id:, retry:, and anything a vendor invents
        }
        return null
    }

    /** EOF: dispatch a payload the server closed without a terminating blank line. */
    public fun flush(): SseEvent? = takeEvent()

    private fun takeEvent(): SseEvent? {
        if (!hasData) {
            eventName = null
            return null
        }
        val event = SseEvent(event = eventName, data = data.toString())
        data.clear()
        hasData = false
        eventName = null
        return event
    }
}

/**
 * The conventional end-of-stream sentinel. OpenAI-compatible servers send it; Anthropic does not, and
 * ends the stream by closing the body instead.
 */
public const val SSE_DONE: String = "[DONE]"

/**
 * Frames a line flow into events.
 *
 * Takes lines rather than bytes so the transport stays the caller's concern and the rules stay testable
 * against a plain list. Termination is a separate operator — see [stopAtDone] — because whether the
 * sentinel exists is a per-vendor fact, not a property of SSE.
 */
public fun Flow<String>.asSseEvents(): Flow<SseEvent> = flow {
    val parser = SseParser()
    collect { line -> parser.feed(line)?.let { emit(it) } }
    // A server that closes without a terminating blank line still has one event's worth of data buffered.
    parser.flush()?.let { emit(it) }
}

/**
 * Ends the stream at the [SSE_DONE] sentinel.
 *
 * Composed rather than folded into [asSseEvents] so each provider states its own truth: OpenAI-compatible
 * servers send the sentinel, Anthropic ends by closing the body, and a parser that assumed either one
 * would be wrong for the other half of the ecosystem.
 */
public fun Flow<SseEvent>.stopAtDone(): Flow<SseEvent> = takeWhile { it.data.trim() != SSE_DONE }
