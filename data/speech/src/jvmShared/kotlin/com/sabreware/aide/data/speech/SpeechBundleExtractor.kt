package com.sabreware.aide.data.speech

import com.sabreware.aide.core.domain.util.AideLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Unpacks a Sherpa `.tar.bz2` model bundle into its extracted dir: strips the single top-level directory
 * prefix so files land directly in `targetDir`, writes [SpeechAssetStorageImpl.INSTALL_MARKER_NAME], and
 * only then deletes the archive. If the marker is absent (crash mid-extract, upgrade) the leftovers are
 * wiped and the archive re-extracted.
 *
 * **Marker before delete, not after.** The other order left a window in which the archive was already gone
 * and the marker not yet written: process death there meant `hasExtracted` said false forever while
 * `require(archive.isFile)` failed forever — a fully-extracted 500 MB model, permanently unusable, with
 * re-downloading as the only way out. The reverse order can only leave a redundant archive on disk, which
 * the next call deletes.
 *
 * It also validates what it unpacks. Signature verification runs earlier in the worker, but an archive is
 * an untrusted container regardless of who signed it: entry paths are checked against the target root so
 * `../` cannot escape it, non-regular entries (symlinks, devices) are skipped, and the expanded size is
 * capped relative to the compressed size so a decompression bomb fills a bounded amount of disk.
 *
 * Lives in `src/jvmShared` rather than commonMain because commons-compress and the streaming copy are
 * `java.io`; it is the same class on both JVMs, which previously ran near-identical copies.
 */
class SpeechBundleExtractor(
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun extract(archive: File, targetDir: File): Result<File> = withContext(ioDispatcher) {
        runCatching {
            val marker = File(targetDir, SpeechAssetStorageImpl.INSTALL_MARKER_NAME)
            if (marker.isFile && targetDir.isDirectory) {
                AideLog.i(TAG, "skip extract: ${targetDir.name} already installed (marker present)")
                // Cleans up after a crash in the (now tiny) window between writing the marker and deleting
                // the archive.
                if (archive.isFile) archive.delete()
                return@runCatching targetDir
            }
            require(archive.isFile) { "Archive missing: ${archive.absolutePath}" }
            if (targetDir.isDirectory && (targetDir.list()?.isNotEmpty() == true)) {
                AideLog.i(TAG, "wiping incomplete dir: ${targetDir.name}")
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()
            val rootPrefix = targetDir.name + "/"
            val root = targetDir.canonicalFile
            // A bomb is an expansion RATIO, not an absolute size — these bundles are legitimately hundreds
            // of megabytes, so a fixed cap would either be useless or reject real models.
            val maxTotalBytes = maxOf(MIN_TOTAL_CAP_BYTES, archive.length() * MAX_EXPANSION_RATIO)
            var totalBytes = 0L
            BufferedInputStream(FileInputStream(archive)).use { raw ->
                BZip2CompressorInputStream(raw).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        var entry = tar.nextEntry
                        val buf = ByteArray(BUF)
                        while (entry != null) {
                            currentCoroutineContext().ensureActive()
                            val rawName = entry.name
                            val rel = if (rawName.startsWith(rootPrefix))
                                rawName.removePrefix(rootPrefix) else rawName
                            if (rel.isEmpty()) { entry = tar.nextEntry; continue }
                            // Only regular files and directories. A symlink entry would otherwise be
                            // materialised as a file whose CONTENT is a path, or — worse, on a future
                            // extractor — as a link pointing outside the bundle.
                            if (!entry.isDirectory && !entry.isFile) {
                                AideLog.w(TAG, "skipping non-regular tar entry: $rawName")
                                entry = tar.nextEntry
                                continue
                            }
                            val outFile = File(targetDir, rel)
                            require(outFile.isInside(root)) {
                                "Refusing tar entry that escapes the target directory: $rawName"
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val n = tar.read(buf)
                                        if (n <= 0) break
                                        totalBytes += n
                                        require(totalBytes <= maxTotalBytes) {
                                            "Archive expands past $maxTotalBytes bytes — refusing to continue"
                                        }
                                        fos.write(buf, 0, n)
                                    }
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            // Marker first: from here on the install is complete and re-extraction is unnecessary.
            marker.outputStream().close()
            // Then the archive, which is now redundant. A crash between the two lines costs disk, not the
            // model.
            archive.delete()
            AideLog.i(TAG, "extracted ${archive.name} → ${targetDir.absolutePath}")
            targetDir
        }
    }

    /** True when this file resolves inside [root] — the check that makes a `../` entry name inert. */
    private fun File.isInside(root: File): Boolean {
        val canonical = canonicalFile
        return canonical == root || canonical.path.startsWith(root.path + File.separator)
    }

    private companion object {
        const val TAG = "SpeechExtractor"
        const val BUF = 64 * 1024

        // bzip2 over ONNX weights compresses by well under 3x in practice; 20x is comfortably above any
        // real bundle and far below what a bomb needs to be useful. The floor keeps a tiny archive of
        // mostly-empty files from tripping the ratio.
        const val MAX_EXPANSION_RATIO = 20L
        const val MIN_TOTAL_CAP_BYTES = 256L * 1024 * 1024
    }
}
