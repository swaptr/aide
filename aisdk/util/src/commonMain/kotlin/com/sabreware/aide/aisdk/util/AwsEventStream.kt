package com.sabreware.aide.aisdk.util

/**
 * One `application/vnd.amazon.eventstream` message.
 *
 * [headers] carry the routing — `:event-type`, `:message-type`, `:exception-type` — and [payload] is the
 * body, which for Bedrock is JSON.
 */
public data class AwsEventStreamMessage(
    val headers: Map<String, String>,
    val payload: ByteArray,
) {

    /** What kind of event this frame carries — Bedrock's routing key. */
    public val eventType: String? get() = headers[":event-type"]
    /** `event` or `exception`; a frame's own claim about which it is. */
    public val messageType: String? get() = headers[":message-type"]
    /** The error's type name, on an exception frame. */
    public val exceptionType: String? get() = headers[":exception-type"]

    override fun equals(other: Any?): Boolean = this === other ||
        (other is AwsEventStreamMessage && headers == other.headers && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * headers.hashCode() + payload.contentHashCode()
}

/**
 * Incremental decoder for AWS's binary event-stream framing.
 *
 * Bedrock does not speak SSE. Its streaming responses arrive as length-prefixed binary frames:
 *
 * ```
 * [total_length: 4] [headers_length: 4] [prelude_crc: 4]
 * [headers: headers_length]
 * [payload: total_length - headers_length - 16]
 * [message_crc: 4]
 * ```
 *
 * Stateful and incremental because a frame does not arrive in one read — bytes are fed as they come and
 * complete messages fall out. A decoder that assumed frame-aligned reads would work locally and corrupt
 * under real network chunking, which is the kind of bug that only appears in production.
 *
 * Both CRCs are verified. They cost almost nothing and are the only way to tell a truncated stream from a
 * malformed one, which otherwise present identically as garbage headers.
 */
public class AwsEventStreamDecoder {

    private var buffer = ByteArray(0)

    /** Feeds bytes; returns whatever complete messages they completed. */
    public fun feed(bytes: ByteArray): List<AwsEventStreamMessage> {
        buffer += bytes
        val out = mutableListOf<AwsEventStreamMessage>()
        while (true) {
            val message = takeMessage() ?: break
            out += message
        }
        return out
    }

    /** True when bytes remain that did not form a complete message — a truncated stream. */
    public fun hasPartialMessage(): Boolean = buffer.isNotEmpty()

    private fun takeMessage(): AwsEventStreamMessage? {
        if (buffer.size < PRELUDE_SIZE) return null
        val totalLength = buffer.readInt(0)
        if (totalLength < MIN_MESSAGE_SIZE || totalLength > MAX_MESSAGE_SIZE) {
            throw AwsEventStreamException("Implausible frame length $totalLength; the stream is not aligned")
        }
        if (buffer.size < totalLength) return null

        val headersLength = buffer.readInt(4)
        val preludeCrc = buffer.readInt(8)
        val actualPreludeCrc = crc32(buffer, 0, 8)
        if (preludeCrc != actualPreludeCrc) {
            throw AwsEventStreamException("Prelude CRC mismatch; the stream is corrupt or misaligned")
        }

        val messageCrc = buffer.readInt(totalLength - 4)
        if (messageCrc != crc32(buffer, 0, totalLength - 4)) {
            throw AwsEventStreamException("Message CRC mismatch; the frame was truncated or altered")
        }

        val headers = parseHeaders(buffer, PRELUDE_SIZE, PRELUDE_SIZE + headersLength)
        val payloadStart = PRELUDE_SIZE + headersLength
        val payload = buffer.copyOfRange(payloadStart, totalLength - 4)
        buffer = buffer.copyOfRange(totalLength, buffer.size)
        return AwsEventStreamMessage(headers, payload)
    }

    /**
     * Header values, rendered as strings.
     *
     * Only the string type is kept verbatim; the numeric and binary types are rendered readably rather
     * than modelled, because Bedrock's routing headers are all strings and a typed value nothing reads is
     * a type to maintain for nothing.
     */
    private fun parseHeaders(bytes: ByteArray, from: Int, to: Int): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var i = from
        while (i < to) {
            val nameLength = bytes[i].toInt() and 0xFF
            i += 1
            val name = bytes.decodeToString(i, i + nameLength)
            i += nameLength
            val type = bytes[i].toInt() and 0xFF
            i += 1
            when (type) {
                TYPE_BOOL_TRUE -> headers[name] = "true"
                TYPE_BOOL_FALSE -> headers[name] = "false"
                TYPE_BYTE -> { headers[name] = bytes[i].toString(); i += 1 }
                TYPE_SHORT -> { headers[name] = bytes.readShort(i).toString(); i += 2 }
                TYPE_INT -> { headers[name] = bytes.readInt(i).toString(); i += 4 }
                TYPE_LONG, TYPE_TIMESTAMP -> { headers[name] = bytes.readLong(i).toString(); i += 8 }
                TYPE_BYTES, TYPE_STRING -> {
                    val length = bytes.readShort(i)
                    i += 2
                    headers[name] = bytes.decodeToString(i, i + length)
                    i += length
                }
                TYPE_UUID -> { headers[name] = bytes.copyOfRange(i, i + 16).toHex(); i += 16 }
                else -> throw AwsEventStreamException("Unknown header value type $type for '$name'")
            }
        }
        return headers
    }

    private companion object {
        const val PRELUDE_SIZE = 12
        const val MIN_MESSAGE_SIZE = 16
        // 16 MiB, AWS's documented ceiling. A larger value means the stream is misaligned, and catching
        // that here beats allocating from a corrupt length.
        const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024
        const val TYPE_BOOL_TRUE = 0
        const val TYPE_BOOL_FALSE = 1
        const val TYPE_BYTE = 2
        const val TYPE_SHORT = 3
        const val TYPE_INT = 4
        const val TYPE_LONG = 5
        const val TYPE_BYTES = 6
        const val TYPE_STRING = 7
        const val TYPE_TIMESTAMP = 8
        const val TYPE_UUID = 9
    }
}

/** The stream was corrupt, truncated, or not an event stream at all. */
public class AwsEventStreamException(message: String) : Exception(message)

private fun ByteArray.readShort(at: Int): Int =
    ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

private fun ByteArray.readInt(at: Int): Int =
    ((this[at].toInt() and 0xFF) shl 24) or
        ((this[at + 1].toInt() and 0xFF) shl 16) or
        ((this[at + 2].toInt() and 0xFF) shl 8) or
        (this[at + 3].toInt() and 0xFF)

private fun ByteArray.readLong(at: Int): Long =
    (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (this[at + i].toLong() and 0xFF) }

private fun ByteArray.toHex(): String = joinToString("") {
    val v = it.toInt() and 0xFF
    "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0xF]
}

/**
 * CRC-32 (IEEE), table-driven.
 *
 * Hand-rolled because `java.util.zip.CRC32` is not reachable from commonMain — the same constraint that
 * pushed SigV4 onto okio. The table is built once on first use.
 */
private val CRC_TABLE: IntArray by lazy {
    IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }
}

private fun crc32(bytes: ByteArray, from: Int, to: Int): Int {
    var crc = -1
    for (i in from until to) {
        crc = CRC_TABLE[(crc xor bytes[i].toInt()) and 0xFF] xor (crc ushr 8)
    }
    return crc.inv()
}
