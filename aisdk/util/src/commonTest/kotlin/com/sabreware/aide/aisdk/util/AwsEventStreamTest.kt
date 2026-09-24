package com.sabreware.aide.aisdk.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AWS's binary event-stream framing, against a frame produced by an INDEPENDENT implementation
 * (Python's `struct` + `zlib.crc32`), not one this decoder wrote itself.
 *
 * Bedrock does not speak SSE, and this framing is unforgiving: an off-by-one in the header walk or a
 * wrong CRC polynomial yields plausible-looking garbage rather than an error.
 */
@OptIn(ExperimentalEncodingApi::class)
class AwsEventStreamTest {

    /** A real `chunk` event carrying a base64 Anthropic text delta, exactly as Bedrock sends one. */
    private val frame = Base64.decode(
        "AAAA1wAAAEu/+eQ4CzpldmVudC10eXBlBwAFY2h1bmsNOm1lc3NhZ2UtdHlwZQcABWV2ZW50DTpjb250ZW50LXR5cGUH" +
            "ABBhcHBsaWNhdGlvbi9qc29ueyJieXRlcyI6ImV5SjBlWEJsSWpvaVkyOXVkR1Z1ZEY5aWJHOWphMTlrWld4MFlTSXNJ" +
            "bWx1WkdWNElqb3dMQ0prWld4MFlTSTZleUowZVhCbElqb2lkR1Y0ZEY5a1pXeDBZU0lzSW5SbGVIUWlPaUpvYVNKOWZR" +
            "PT0ife0c9Po=",
    )

    @Test
    fun `a real frame decodes to its headers and payload`() {
        val messages = AwsEventStreamDecoder().feed(frame)

        val message = messages.single()
        assertEquals("chunk", message.eventType)
        assertEquals("event", message.messageType)
        assertEquals("application/json", message.headers[":content-type"])
        assertTrue(message.payload.decodeToString().startsWith("""{"bytes":"""))
    }

    @Test
    fun `the payload unwraps to the vendor event Bedrock is carrying`() {
        val message = AwsEventStreamDecoder().feed(frame).single()

        // Bedrock wraps the vendor's own event as base64 inside a JSON envelope.
        val envelope = parseJsonObject(message.payload.decodeToString())
        val inner = Base64.decode(envelope["bytes"].toString().trim('"')).decodeToString()

        assertEquals(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""",
            inner,
        )
    }

    @Test
    fun `bytes arriving in arbitrary chunks still produce one message`() {
        // The bug this guards: a decoder assuming frame-aligned reads works locally and corrupts under
        // real network chunking.
        val decoder = AwsEventStreamDecoder()
        val collected = frame.toList().chunked(7).flatMap { chunk ->
            decoder.feed(chunk.toByteArray())
        }

        assertEquals(1, collected.size)
        assertEquals("chunk", collected.single().eventType)
        assertTrue(!decoder.hasPartialMessage())
    }

    @Test
    fun `one byte at a time works too`() {
        val decoder = AwsEventStreamDecoder()
        val collected = frame.flatMap { decoder.feed(byteArrayOf(it)) }

        assertEquals(1, collected.size)
    }

    @Test
    fun `two frames back to back both decode`() {
        val messages = AwsEventStreamDecoder().feed(frame + frame)

        assertEquals(2, messages.size)
    }

    @Test
    fun `an incomplete frame yields nothing and is reported as partial`() {
        val decoder = AwsEventStreamDecoder()

        assertEquals(emptyList(), decoder.feed(frame.copyOfRange(0, frame.size - 10)))
        // The difference between "still streaming" and "the server hung up mid-frame".
        assertTrue(decoder.hasPartialMessage())
    }

    @Test
    fun `a corrupted payload is caught by the message CRC`() {
        val corrupted = frame.copyOf()
        corrupted[corrupted.size - 20] = (corrupted[corrupted.size - 20] + 1).toByte()

        val error = assertFailsWith<AwsEventStreamException> { AwsEventStreamDecoder().feed(corrupted) }
        assertTrue(error.message!!.contains("CRC"), error.message!!)
    }

    @Test
    fun `a corrupted prelude is caught before anything is allocated from it`() {
        val corrupted = frame.copyOf()
        corrupted[5] = (corrupted[5] + 1).toByte()

        assertFailsWith<AwsEventStreamException> { AwsEventStreamDecoder().feed(corrupted) }
    }

    @Test
    fun `an implausible length is rejected rather than allocated`() {
        // A misaligned stream reads garbage as a length; allocating from it is how a decoder turns
        // corruption into an OOM.
        val bogus = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F) + ByteArray(20)

        val error = assertFailsWith<AwsEventStreamException> { AwsEventStreamDecoder().feed(bogus) }
        assertTrue(error.message!!.contains("Implausible"), error.message!!)
    }
}
