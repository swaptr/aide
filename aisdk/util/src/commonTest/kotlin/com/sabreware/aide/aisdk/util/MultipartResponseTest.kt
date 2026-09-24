package com.sabreware.aide.aisdk.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The multipart RESPONSE parser, against the same shapes the reference's `parseMultipart` tolerates:
 * CRLF and bare-LF delimiters, a closing boundary that must not become a part, and binary bodies that
 * must come back byte-identical — an image with a flipped byte decodes to garbage with no error.
 */
class MultipartResponseTest {

    private val boundary = "abc123"

    private fun crlfBody(): ByteArray = buildString {
        append("--abc123\r\n")
        append("Content-Disposition: form-data; name=\"job\"\r\n")
        append("Content-Type: application/json\r\n")
        append("\r\n")
        append("""{"id":"job-1"}""")
        append("\r\n--abc123\r\n")
        append("Content-Disposition: form-data; name=\"output\"\r\n")
        append("Content-Type: image/png\r\n")
        append("\r\n")
        append("PNGBYTES")
        append("\r\n--abc123--\r\n")
    }.encodeToByteArray()

    @Test
    fun `boundary comes out of the content type, quoted or bare`() {
        assertEquals("abc123", MultipartResponse.boundaryOf("multipart/form-data; boundary=abc123"))
        assertEquals("abc123", MultipartResponse.boundaryOf("multipart/form-data; boundary=\"abc123\""))
        assertEquals(
            "abc123",
            MultipartResponse.boundaryOf("multipart/form-data; charset=utf-8; boundary=abc123"),
        )
        assertNull(MultipartResponse.boundaryOf("application/json"))
        assertNull(MultipartResponse.boundaryOf(null))
    }

    @Test
    fun `two parts decode with lower-cased headers and exact bodies`() {
        val parts = MultipartResponse.parse(crlfBody(), boundary)

        assertEquals(2, parts.size)
        assertEquals("job", parts[0].name)
        assertEquals("application/json", parts[0].contentType)
        assertEquals("""{"id":"job-1"}""", parts[0].body.decodeToString())
        assertEquals("output", parts[1].name)
        assertContentEquals("PNGBYTES".encodeToByteArray(), parts[1].body)
    }

    @Test
    fun `bare-LF newlines decode the same way`() {
        val body = crlfBody().decodeToString().replace("\r\n", "\n").encodeToByteArray()

        val parts = MultipartResponse.parse(body, boundary)

        assertEquals(2, parts.size)
        assertEquals("""{"id":"job-1"}""", parts[0].body.decodeToString())
        assertContentEquals("PNGBYTES".encodeToByteArray(), parts[1].body)
    }

    @Test
    fun `the closing boundary never becomes a part`() {
        val parts = MultipartResponse.parse(crlfBody(), boundary)

        assertTrue(parts.none { it.body.decodeToString().contains("--") })
        assertEquals(2, parts.size)
    }

    @Test
    fun `binary bytes survive untouched, including CRLF sequences inside the body`() {
        // A body that CONTAINS the bytes 0x0d 0x0a mid-payload: trimming or splitting on newlines
        // inside a part corrupts exactly this shape.
        val payload = byteArrayOf(0x00, 0x0d, 0x0a, 0x7f, -1, 0x0d, 0x0a, 0x42)
        val head = "--$boundary\r\ncontent-disposition: form-data; name=\"output\"\r\n\r\n"
            .encodeToByteArray()
        val tail = "\r\n--$boundary--\r\n".encodeToByteArray()

        val parts = MultipartResponse.parse(head + payload + tail, boundary)

        assertEquals(1, parts.size)
        assertContentEquals(payload, parts[0].body)
    }

    @Test
    fun `a part with no header separator is dropped, not misread`() {
        val body = "--$boundary\r\nno separator here--$boundary--\r\n".encodeToByteArray()

        assertEquals(emptyList(), MultipartResponse.parse(body, boundary))
    }
}
