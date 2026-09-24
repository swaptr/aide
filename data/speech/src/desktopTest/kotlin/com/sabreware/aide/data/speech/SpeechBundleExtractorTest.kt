package com.sabreware.aide.data.speech

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

/**
 * Unpacking a model bundle: the layout it must produce, and the three ways it must refuse.
 *
 * The extractor is the only code in the app that writes an archive's contents to disk, and it is handed
 * ~500 MB of third-party bytes. Signature verification runs earlier in the worker, but a signed archive is
 * still an untrusted container — and the failure modes here are not "an exception a user sees", they are
 * "a model that can never be used again" and "a file written outside its directory".
 */
class SpeechBundleExtractorTest {

    private val temp: File = Files.createTempDirectory("speech-extract-test").toFile()
    private val extractor = SpeechBundleExtractor(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        temp.deleteRecursively()
    }

    /** A `.tar.bz2` whose entries are `<rootPrefix>/<name>`, the shape Sherpa publishes. */
    private fun archive(name: String, rootPrefix: String, entries: Map<String, ByteArray>): File {
        val file = File(temp, name)
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream().buffered())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { (entryName, bytes) ->
                val entry = TarArchiveEntry("$rootPrefix/$entryName")
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    private fun bundle(vararg extra: Pair<String, ByteArray>) = mapOf(
        "tokens.txt" to "a b c".toByteArray(),
        "model.onnx" to ByteArray(2048) { 7 },
        *extra,
    )

    @Test
    fun `strips the top-level directory so files land directly in the target`() = runTest {
        val target = File(temp, "zipformer-en")
        val src = archive("bundle.tar.bz2", rootPrefix = target.name, entries = bundle())

        val result = extractor.extract(src, target)

        assertTrue(result.isSuccess, "extraction should succeed: ${result.exceptionOrNull()}")
        assertTrue(File(target, "tokens.txt").isFile)
        assertTrue(File(target, "model.onnx").isFile)
        assertEquals(2048L, File(target, "model.onnx").length())
        // No nested `zipformer-en/zipformer-en/` — the prefix is stripped, not preserved.
        assertFalse(File(target, target.name).exists())
    }

    /**
     * The P7 regression, and the reason the order changed.
     *
     * The archive used to be deleted BEFORE the install marker was written. Process death in that window
     * left `hasExtracted` false forever while `require(archive.isFile)` failed forever: a fully-extracted
     * 500 MB model, permanently unusable, with re-downloading as the only way out. Marker first means the
     * worst case is a redundant archive on disk.
     */
    @Test
    fun `writes the install marker before deleting the archive`() = runTest {
        val target = File(temp, "zipformer-en")
        val src = archive("bundle.tar.bz2", rootPrefix = target.name, entries = bundle())

        extractor.extract(src, target)

        assertTrue(
            File(target, SpeechAssetStorageImpl.INSTALL_MARKER_NAME).isFile,
            "the marker is what makes the install detectable at all",
        )
        assertFalse(src.exists(), "the archive is redundant once the marker is written")
    }

    /** And if the crash lands in the (now tiny) remaining window, the next call cleans up rather than redoing. */
    @Test
    fun `an already-installed bundle is left alone and its leftover archive removed`() = runTest {
        val target = File(temp, "zipformer-en")
        target.mkdirs()
        File(target, SpeechAssetStorageImpl.INSTALL_MARKER_NAME).createNewFile()
        File(target, "tokens.txt").writeText("already here")
        val src = archive("bundle.tar.bz2", rootPrefix = target.name, entries = bundle())

        val result = extractor.extract(src, target)

        assertTrue(result.isSuccess)
        assertEquals("already here", File(target, "tokens.txt").readText(), "must not re-extract over an install")
        assertFalse(src.exists(), "a leftover archive beside a complete install is cleaned up")
    }

    /** A half-extracted directory (no marker) is wiped and redone, rather than merged into. */
    @Test
    fun `an incomplete directory is wiped before extracting`() = runTest {
        val target = File(temp, "zipformer-en")
        target.mkdirs()
        File(target, "stale.txt").writeText("from a crashed extract")
        val src = archive("bundle.tar.bz2", rootPrefix = target.name, entries = bundle())

        extractor.extract(src, target)

        assertFalse(File(target, "stale.txt").exists(), "leftovers from a partial extract must not survive")
        assertTrue(File(target, "model.onnx").isFile)
    }

    /**
     * Path traversal. A `../` entry name resolved against the target directory used to write wherever it
     * pointed — the archive decided, not us.
     */
    @Test
    fun `refuses an entry whose path escapes the target directory`() = runTest {
        val target = File(temp, "zipformer-en")
        val outside = File(temp, "escaped.txt")
        val src = archive(
            "evil.tar.bz2",
            rootPrefix = target.name,
            entries = bundle("../escaped.txt" to "pwned".toByteArray()),
        )

        val result = extractor.extract(src, target)

        assertTrue(result.isFailure, "an escaping entry must fail the extraction, not be written")
        assertContains(result.exceptionOrNull()?.message.orEmpty(), "escapes the target directory")
        assertFalse(outside.exists(), "nothing may be written outside the target directory")
        assertFalse(
            File(target, SpeechAssetStorageImpl.INSTALL_MARKER_NAME).exists(),
            "a failed extraction must not look installed",
        )
    }

    /** Decompression bomb: bounded by an expansion RATIO, because the legitimate bundles are huge. */
    @Test
    fun `refuses an archive that expands far beyond its compressed size`() = runTest {
        val target = File(temp, "bomb")
        // Highly compressible: ~400 MB of zeroes in a tiny archive, past the 256 MB floor and the 20x ratio.
        val src = archive(
            "bomb.tar.bz2",
            rootPrefix = target.name,
            entries = mapOf(
                "tokens.txt" to "a".toByteArray(),
                "model.onnx" to ByteArray(400 * 1024 * 1024),
            ),
        )

        val result = extractor.extract(src, target)

        assertTrue(result.isFailure, "an archive that expands past the cap must be refused mid-stream")
        assertContains(result.exceptionOrNull()?.message.orEmpty(), "expands past")
        assertFalse(
            File(target, SpeechAssetStorageImpl.INSTALL_MARKER_NAME).exists(),
            "a refused extraction must not look installed",
        )
    }

    @Test
    fun `a missing archive fails rather than producing an empty install`() = runTest {
        val target = File(temp, "zipformer-en")

        val result = extractor.extract(File(temp, "not-there.tar.bz2"), target)

        assertTrue(result.isFailure)
        assertFalse(File(target, SpeechAssetStorageImpl.INSTALL_MARKER_NAME).exists())
    }
}
