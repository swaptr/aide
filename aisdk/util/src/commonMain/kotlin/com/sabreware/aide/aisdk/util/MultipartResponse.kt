package com.sabreware.aide.aisdk.util

/**
 * One part of a multipart RESPONSE body: its headers, keys lower-cased, and its raw bytes.
 *
 * The response-side twin of [MultipartPart], which exists only for requests. It exists because at least
 * one vendor — Prodia — answers a job submit with job metadata and image bytes in ONE multipart body,
 * and Ktor has no response-side multipart decoding: without a parser the only options are to skip the
 * vendor or to regex bytes apart at every call site.
 */
public data class MultipartResponsePart(
    /** The part's own headers — `content-disposition`, `content-type` — keys lower-cased. */
    val headers: Map<String, String>,
    /** The part's body, exactly as it appeared between the delimiters. */
    val body: ByteArray,
) {

    /** The `name="…"` of the part's content-disposition, or null when the part carries none. */
    public val name: String?
        get() = headers["content-disposition"]
            ?.substringAfter("name=\"", "")
            ?.substringBefore('"')
            ?.takeIf { it.isNotEmpty() }

    /** The part's own content type, or null. */
    public val contentType: String? get() = headers["content-type"]

    override fun equals(other: Any?): Boolean = this === other ||
        (other is MultipartResponsePart && headers == other.headers && body.contentEquals(other.body))

    override fun hashCode(): Int = 31 * headers.hashCode() + body.contentHashCode()
}

/**
 * Decodes a multipart response body. A pure function over bytes: no transport, no streaming — a
 * response small enough to be one [ByteArray] is the only shape a job endpoint answers with.
 */
public object MultipartResponse {

    /**
     * The boundary named by a `Content-Type` header, or null when the header names none.
     *
     * Surrounding quotes are stripped — RFC 2046 allows `boundary="…"`, and the actual delimiter on
     * the wire is always the UNQUOTED token. (The reference's regex keeps the quotes and would fail to
     * match a quoting server; this is the one deliberate divergence in this file.)
     */
    public fun boundaryOf(contentType: String?): String? = contentType
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeIf { it.isNotEmpty() }

    /**
     * Splits [data] on `--boundary` delimiters into parts.
     *
     * Ported from the reference's `parseMultipart` semantics, byte-for-byte tolerant in the same
     * places: delimiters may be followed by CRLF or bare LF, a part's header block ends at the first
     * blank line in either convention, the closing `--boundary--` is skipped, and a part with no
     * header/body separator at all is dropped rather than misread as headers.
     */
    public fun parse(data: ByteArray, boundary: String): List<MultipartResponsePart> {
        val delimiter = "--$boundary".encodeToByteArray()
        val closing = "--$boundary--".encodeToByteArray()

        val positions = data.indexesOf(delimiter)
        val parts = mutableListOf<MultipartResponsePart>()

        for (i in 0 until positions.size - 1) {
            // The closing delimiter also matches the plain one by prefix; a "part" that starts at the
            // closing delimiter is the epilogue, not content.
            if (data.startsWith(closing, at = positions[i])) continue

            var start = positions[i] + delimiter.size
            if (data.getOrNull(start) == CR && data.getOrNull(start + 1) == LF) {
                start += 2
            } else if (data.getOrNull(start) == LF) {
                start += 1
            }
            var end = positions[i + 1]
            if (end >= 2 && data[end - 2] == CR && data[end - 1] == LF) {
                end -= 2
            } else if (end >= 1 && data[end - 1] == LF) {
                end -= 1
            }
            if (end <= start) continue

            val part = data.copyOfRange(start, end)
            val separator = part.headerBodySplit() ?: continue
            val headerText = part.copyOfRange(0, separator.first).decodeToString()
            val headers = buildMap {
                headerText.split("\r\n", "\n").forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) put(line.take(colon).trim().lowercase(), line.drop(colon + 1).trim())
                }
            }
            parts += MultipartResponsePart(headers, part.copyOfRange(separator.second, part.size))
        }
        return parts
    }

    /** Where the header block ends and where the body begins, in either newline convention. */
    private fun ByteArray.headerBodySplit(): Pair<Int, Int>? {
        for (i in 0 until size - 1) {
            if (i < size - 3 && this[i] == CR && this[i + 1] == LF && this[i + 2] == CR && this[i + 3] == LF) {
                return i to i + 4
            }
            if (this[i] == LF && this[i + 1] == LF) return i to i + 2
        }
        return null
    }

    private fun ByteArray.indexesOf(needle: ByteArray): List<Int> {
        val found = mutableListOf<Int>()
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            found += i
        }
        return found
    }

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int): Boolean {
        if (at + prefix.size > size) return false
        for (j in prefix.indices) if (this[at + j] != prefix[j]) return false
        return true
    }

    private const val CR: Byte = 0x0d
    private const val LF: Byte = 0x0a
}

private fun ByteArray.getOrNull(index: Int): Byte? = if (index in indices) this[index] else null
