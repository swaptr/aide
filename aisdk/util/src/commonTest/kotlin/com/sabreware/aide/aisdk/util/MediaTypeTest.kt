package com.sabreware.aide.aisdk.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Media-type sniffing from leading bytes.
 *
 * Providers need a type on every attachment and callers often do not have one. An extension can lie or
 * be absent, and a default of `image/jpeg` sends a PNG mislabelled — which comes back as "unsupported
 * image format" for an image the vendor supports perfectly well.
 */
class MediaTypeTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** An ID3v2 header whose body is [size] bytes, length encoded seven bits per byte. */
    private fun id3Tag(size: Int) = "ID3".encodeToByteArray() + bytes(0x04, 0x00, 0x00) +
        bytes((size shr 21) and 0x7F, (size shr 14) and 0x7F, (size shr 7) and 0x7F, size and 0x7F) +
        ByteArray(size)

    @Test
    fun `common image formats are identified by signature`() {
        assertEquals("image/png", MediaType.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("image/jpeg", MediaType.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/gif", MediaType.detect("GIF89a...".encodeToByteArray()))
        assertEquals("image/bmp", MediaType.detect(bmpHeader))
    }

    /** `BM`, a file size, then the four reserved bytes a real header zeroes — the reference's fixture. */
    private val bmpHeader = bytes(0x42, 0x4D, 0x36, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun `GIF and BMP are recognised by their full headers, in bytes and in base64`() {
        assertEquals("image/gif", MediaType.detect(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61), "image"))
        assertEquals(
            "image/gif",
            MediaType.detect(kotlin.io.encoding.Base64.encode(bytes(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)), "image"),
        )
        assertEquals("image/bmp", MediaType.detect(bmpHeader, "image"))
        assertEquals("image/bmp", MediaType.detect(kotlin.io.encoding.Base64.encode(bmpHeader), "image"))
    }

    @Test
    fun `plain text that merely starts like an image magic is not an image`() {
        // Three bytes of GIF and two of BMP matched any prose beginning with them — the reference's fix,
        // with its two sentences: a file labelled an image reaches a vendor as one and is refused there.
        assertNull(MediaType.detect("GIF support notes".encodeToByteArray(), "image"))
        assertNull(MediaType.detect("BM25 ranking notes".encodeToByteArray(), "image"))
        // A real header whose reserved bytes are not zero is not a BMP either.
        assertNull(MediaType.detect(bytes(0x42, 0x4D, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x00, 0x00, 0x00)))
    }

    @Test
    fun `RIFF containers are separated by their inner format`() {
        // Both WAV and WebP are RIFF; matching "RIFF" alone labels a WebP image as audio.
        val wav = "RIFF".encodeToByteArray() + ByteArray(4) + "WAVE".encodeToByteArray()
        val webp = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray()

        assertEquals("audio/wav", MediaType.detect(wav))
        assertEquals("image/webp", MediaType.detect(webp))
    }

    @Test
    fun `ISO container brands separate audio from video`() {
        // mp4, m4a and mov share the ftyp box; the brand is what distinguishes them.
        val m4a = ByteArray(4) + "ftyp".encodeToByteArray() + "M4A ".encodeToByteArray()
        val mp4 = ByteArray(4) + "ftyp".encodeToByteArray() + "isom".encodeToByteArray()

        assertEquals("audio/mp4", MediaType.detect(m4a))
        assertEquals("video/mp4", MediaType.detect(mp4))
    }

    @Test
    fun `audio formats are identified`() {
        assertEquals("audio/ogg", MediaType.detect("OggS".encodeToByteArray()))
        assertEquals("audio/flac", MediaType.detect("fLaC".encodeToByteArray()))
        assertEquals("audio/aac", MediaType.detect(bytes(0x40, 0x15, 0x00, 0x00)))
        assertEquals("audio/mpeg", MediaType.detect(bytes(0xFF, 0xFB, 0x90)))
    }

    @Test
    fun `every MPEG frame marker is recognised`() {
        // Which marker a file opens with is the encoder's choice of layer and CRC. Knowing two of the
        // six labels the rest as unknown, and the vendor rejects an upload with no format.
        listOf(0xFB, 0xFA, 0xF3, 0xF2, 0xE3, 0xE2).forEach { marker ->
            assertEquals("audio/mpeg", MediaType.detect(bytes(0xFF, marker, 0x90)), marker.toString())
        }
    }

    @Test
    fun `an ID3 tag is skipped to reach the audio frame behind it`() {
        // The tag length is syncsafe — seven bits per byte — and a tagged MP3 is what nearly every
        // library and recorder produces. Matching only the frame marker misses all of them.
        val tag = id3Tag(size = 20)
        val mp3 = tag + bytes(0xFF, 0xFB, 0x90) + ByteArray(4)

        assertEquals("audio/mpeg", MediaType.detect(mp3))
        // A tag whose body runs past what we decoded is still an MP3, not an unknown file.
        assertEquals("audio/mpeg", MediaType.detect(id3Tag(size = 500_000)))
    }

    @Test
    fun `WebM is audio or video depending on what the caller asked about`() {
        // A browser's MediaRecorder produces exactly this for a voice note. Sniffing it to null is how
        // three providers ended up naming an upload the vendor then rejected.
        val ebml = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01, 0x00, 0x00, 0x00)

        assertEquals("audio/webm", MediaType.detect(ebml, topLevelType = "audio"))
        assertEquals("video/webm", MediaType.detect(ebml))
    }

    @Test
    fun `the modern image container brands are identified`() {
        val avif = ByteArray(4) + "ftyp".encodeToByteArray() + "avif".encodeToByteArray()
        val heic = ByteArray(4) + "ftyp".encodeToByteArray() + "heic".encodeToByteArray()

        assertEquals("image/avif", MediaType.detect(avif))
        assertEquals("image/heic", MediaType.detect(heic))
        // A phone photo reaching a vendor as video/mp4 is rejected as an unsupported image.
        assertTrue(MediaType.isImage(heic))
    }

    @Test
    fun `base64 is sniffed without decoding the whole payload`() {
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val png = kotlin.io.encoding.Base64.encode(
            bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(1024 * 1024),
        )

        assertEquals("image/png", MediaType.detect(png))
        assertNull(MediaType.detect("not base64 at all!!"))
    }

    @Test
    fun `a media type names the extension a vendor expects, not its subtype`() {
        // `audio.mpeg` is rejected by endpoints that infer the format from the filename — for a format
        // they support perfectly well.
        assertEquals("mp3", mediaTypeToExtension("audio/mpeg"))
        assertEquals("wav", mediaTypeToExtension("audio/x-wav"))
        assertEquals("ogg", mediaTypeToExtension("audio/opus"))
        assertEquals("m4a", mediaTypeToExtension("audio/mp4"))
        assertEquals("m4a", mediaTypeToExtension("audio/x-m4a"))
        // An unknown subtype is its own extension, which is right more often than any default.
        assertEquals("flac", mediaTypeToExtension("audio/flac"))
        assertEquals("png", mediaTypeToExtension("IMAGE/PNG"))
    }

    @Test
    fun `PDFs are identified, which is what gates the document capability`() {
        assertEquals("application/pdf", MediaType.detect("%PDF-1.7".encodeToByteArray()))
    }

    @Test
    fun `nothing recognisable returns null rather than a guess`() {
        // A wrong label is worse than no label: a caller can still fall back on what it knows.
        assertNull(MediaType.detect("just some text".encodeToByteArray()))
        assertNull(MediaType.detect(ByteArray(0)))
        // Too short to match anything, and reading past the end would crash instead.
        assertNull(MediaType.detect(bytes(0x89)))
    }

    @Test
    fun `the fallback is explicit at the call site`() {
        assertEquals("image/jpeg", MediaType.detectOr("???".encodeToByteArray(), "image/jpeg"))
        // A real signature still wins over the fallback.
        assertEquals("image/png", MediaType.detectOr(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), "image/jpeg"))
    }

    @Test
    fun `isImage answers a capability gate that has only bytes`() {
        assertTrue(MediaType.isImage(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertTrue(!MediaType.isImage("OggS".encodeToByteArray()))
        assertTrue(!MediaType.isImage("nonsense".encodeToByteArray()))
    }
}
