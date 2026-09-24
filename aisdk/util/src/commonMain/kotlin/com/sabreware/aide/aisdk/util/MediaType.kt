package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.BinaryData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Identifies a file's type from its leading bytes.
 *
 * Providers need a media type on every attachment, and callers frequently do not have one: a file picked
 * off disk, a clipboard paste, a byte array from a socket. The alternatives are worse than sniffing — an
 * extension can lie or be absent, and defaulting to `image/jpeg` means a PNG reaches a vendor mislabelled
 * and comes back as "unsupported image format" for an image it supports perfectly well.
 *
 * Returns null rather than guessing when nothing matches. A wrong label is worse than no label, because a
 * caller can still fall back on what it knows.
 */
public object MediaType {

    /** Signatures long enough to be unambiguous, checked longest-first. */
    private val SIGNATURES: List<Pair<ByteArray, String>> = listOf(
        // Images
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) to "image/png",
        bytes(0xFF, 0xD8, 0xFF) to "image/jpeg",
        // GIF is the full six-byte `GIF87a` / `GIF89a`, never the bare `GIF`: the reference matched
        // three bytes until a text file beginning "GIF support notes" was labelled an image.
        "GIF87a".encodeToByteArray() to "image/gif",
        "GIF89a".encodeToByteArray() to "image/gif",
        bytes(0x49, 0x49, 0x2A, 0x00) to "image/tiff",
        bytes(0x4D, 0x4D, 0x00, 0x2A) to "image/tiff",
        // Documents
        "%PDF-".encodeToByteArray() to "application/pdf",
        // Audio
        "OggS".encodeToByteArray() to "audio/ogg",
        "fLaC".encodeToByteArray() to "audio/flac",
        bytes(0x40, 0x15, 0x00, 0x00) to "audio/aac",
        // Bare MPEG audio frames. All six markers matter: which one a file starts with is decided by the
        // encoder's layer and CRC settings, so recognising only two labels the other four as unknown and
        // the vendor rejects the upload for having no format at all.
        bytes(0xFF, 0xFB) to "audio/mpeg",
        bytes(0xFF, 0xFA) to "audio/mpeg",
        bytes(0xFF, 0xF3) to "audio/mpeg",
        bytes(0xFF, 0xF2) to "audio/mpeg",
        bytes(0xFF, 0xE3) to "audio/mpeg",
        bytes(0xFF, 0xE2) to "audio/mpeg",
    )

    /**
     * The media type of [bytes], or null if nothing matches.
     *
     * [topLevelType] resolves the containers that carry either kind. Matroska is one byte-for-byte
     * format for both `audio/webm` and `video/webm`, and a browser's `MediaRecorder` produces exactly
     * that for a voice note — labelled `video/webm`, a transcription endpoint rejects it. A caller that
     * knows which modality it is asking about says so; one that does not gets the video spelling, which
     * is what the reference returns.
     *
     * RIFF and ISO-BMFF containers are handled separately for the same reason: both share a prefix
     * across several formats, so the bytes that actually distinguish them sit further in.
     */
    public fun detect(bytes: ByteArray, topLevelType: String? = null): String? {
        val id3 = hasId3(bytes)
        val head = if (id3) bytes.stripId3() else bytes

        SIGNATURES.firstOrNull { (signature, _) -> head.startsWith(signature) }
            ?.let { return it.second }

        // BMP: `BM`, four size bytes, then four RESERVED bytes a real header zeroes. The two-byte magic
        // alone matched any text beginning "BM" — "BM25 ranking notes" was an image.
        if (head.startsWith(bytes(0x42, 0x4D)) && head.size >= BMP_HEADER &&
            (BMP_RESERVED_AT..<BMP_HEADER).all { head[it] == 0.toByte() }
        ) {
            return "image/bmp"
        }

        // Matroska/WebM. The EBML header is identical for both, so only the caller's intent separates
        // them.
        if (head.startsWith(bytes(0x1A, 0x45, 0xDF, 0xA3))) {
            return if (topLevelType == "audio") "audio/webm" else "video/webm"
        }

        // RIFF containers: "RIFF" then four size bytes then the real format. WAV and WEBP are both RIFF,
        // so matching on "RIFF" alone labels a WebP image as audio.
        if (head.startsWith("RIFF".encodeToByteArray()) && head.size >= RIFF_HEADER) {
            return when (head.decodeToString(RIFF_FORMAT_AT, RIFF_HEADER)) {
                "WAVE" -> "audio/wav"
                "WEBP" -> "image/webp"
                "AVI " -> "video/x-msvideo"
                else -> null
            }
        }

        // ISO base media containers: a size prefix, then "ftyp", then the brand. mp4, m4a, mov, avif and
        // heic all share the box; the brand is the only thing that separates a photo from a film.
        if (head.size >= FTYP_BRAND_END && head.decodeToString(FTYP_AT, FTYP_AT + 4) == "ftyp") {
            return when (head.decodeToString(FTYP_BRAND_AT, FTYP_BRAND_END).trimEnd()) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "hevc", "heim", "heis", "mif1" -> "image/heic"
                "M4A", "M4B" -> "audio/mp4"
                "qt" -> "video/quicktime"
                else -> if (topLevelType == "audio") "audio/mp4" else "video/mp4"
            }
        }

        // An ID3 tag with nothing recognisable behind it is an MP3 whose first frame sits past the
        // bounded prefix we decode. The tag itself is only ever attached to MPEG audio in practice.
        return if (id3) "audio/mpeg" else null
    }

    /**
     * [detect] over base64, decoding only the prefix it needs.
     *
     * Vendors hand back base64 constantly and a caller holding a data URI should not have to materialise
     * a whole 20 MB video to learn its first four bytes.
     */
    @OptIn(ExperimentalEncodingApi::class)
    public fun detect(base64: String, topLevelType: String? = null): String? {
        val bytes = runCatching {
            // Whole 4-character groups only: a partial group is not decodable, and the extra bytes are
            // trimmed so this path and the raw one see an identical prefix.
            val chars = minOf(base64.length, ((SNIFF_BYTES + 2) / 3) * 4)
            Base64.decode(base64, 0, chars - chars % 4)
        }.getOrNull() ?: return null
        val detected = detect(bytes, topLevelType)
        // An ID3 tag's own length lives past the sniffing prefix, so the tagged case decodes further —
        // still bounded, and only for the input that actually claims to be tagged.
        if (detected == null && hasId3(bytes)) {
            val tagged = runCatching {
                val chars = minOf(base64.length, ((ID3_SCAN_BYTES + 2) / 3) * 4)
                Base64.decode(base64, 0, chars - chars % 4)
            }.getOrNull() ?: return null
            return detect(tagged, topLevelType)
        }
        return detected
    }

    /** [detect] over whichever representation the caller happens to hold. */
    public fun detect(data: BinaryData, topLevelType: String? = null): String? = when (data) {
        is BinaryData.Bytes -> detect(data.value, topLevelType)
        is BinaryData.Base64 -> detect(data.value, topLevelType)
    }

    /**
     * [detect], or [fallback] when nothing matches.
     *
     * For the call sites that must send something. Keeping the fallback explicit at each one means a
     * wrong guess is visible in the code rather than buried in a default.
     */
    public fun detectOr(bytes: ByteArray, fallback: String): String = detect(bytes) ?: fallback

    /** Whether this looks like an image, for a capability gate that has only the bytes. */
    public fun isImage(bytes: ByteArray): Boolean = detect(bytes)?.startsWith("image/") == true

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    private fun hasId3(bytes: ByteArray): Boolean =
        bytes.size > ID3_HEADER && bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()

    /**
     * The bytes after an ID3v2 tag, where the audio frame the signatures match actually starts.
     *
     * The tag's length is stored syncsafe — seven bits per byte, so no byte of the length can be
     * mistaken for a frame marker — and can run to megabytes when it carries cover art.
     */
    private fun ByteArray.stripId3(): ByteArray {
        val size = (this[6].toInt() and SYNCSAFE) shl 21 or
            ((this[7].toInt() and SYNCSAFE) shl 14) or
            ((this[8].toInt() and SYNCSAFE) shl 7) or
            (this[9].toInt() and SYNCSAFE)
        val start = size + ID3_HEADER
        return if (start in 0..<this.size) copyOfRange(start, this.size) else this
    }

    private const val RIFF_FORMAT_AT = 8
    private const val RIFF_HEADER = 12
    private const val FTYP_AT = 4
    private const val FTYP_BRAND_AT = 8
    private const val FTYP_BRAND_END = 12
    private const val ID3_HEADER = 10
    private const val SYNCSAFE = 0x7F
    private const val BMP_RESERVED_AT = 6
    private const val BMP_HEADER = 10

    /** Enough for the longest signature plus the ISO-BMFF brand, and no more. */
    private const val SNIFF_BYTES = 18

    /** A tag past this is a file we decline to scan rather than decode whole to find a frame. */
    private const val ID3_SCAN_BYTES = 128 * 1024 + SNIFF_BYTES
}

/**
 * The file extension a media type is spelled with.
 *
 * Not the subtype: `audio/mpeg` is an `.mp3`, and several audio endpoints infer the format from the
 * filename's extension and reject an upload named `audio.mpeg` as an unsupported format — for a format
 * they support. The mappings are the reference's, and an unknown subtype falls through to itself, which
 * is right far more often than any default would be.
 */
public fun mediaTypeToExtension(mediaType: String): String {
    val subtype = mediaType.lowercase().substringAfter('/', missingDelimiterValue = "")
    return when (subtype) {
        "mpeg" -> "mp3"
        "x-wav" -> "wav"
        "opus" -> "ogg"
        "mp4", "x-m4a" -> "m4a"
        else -> subtype
    }
}
