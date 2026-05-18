package com.swaptr.aide.data.speech

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

// Marker written last; if absent (crash/upgrade), leftovers wiped and archive re-extracted.
// Strips single top-level dir prefix so files land directly in targetDir.
object SpeechBundleExtractor {
    private const val TAG = "SpeechExtractor"
    private const val BUF = 64 * 1024

    const val INSTALL_MARKER_NAME = ".install_complete"

    suspend fun extract(archive: File, targetDir: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val marker = File(targetDir, INSTALL_MARKER_NAME)
            if (marker.isFile && targetDir.isDirectory) {
                Log.i(TAG, "skip extract: ${targetDir.name} already installed (marker present)")
                return@runCatching targetDir
            }
            require(archive.isFile) { "Archive missing: ${archive.absolutePath}" }
            if (targetDir.isDirectory && (targetDir.list()?.isNotEmpty() == true)) {
                Log.i(TAG, "wiping incomplete dir: ${targetDir.name}")
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()
            val rootPrefix = targetDir.name + "/"
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
                            val outFile = File(targetDir, rel)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val n = tar.read(buf)
                                        if (n <= 0) break
                                        fos.write(buf, 0, n)
                                    }
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            archive.delete()
            // Write the marker as the very last step so any partial-extract is detectable.
            marker.outputStream().close()
            Log.i(TAG, "extracted ${archive.name} → ${targetDir.absolutePath}")
            targetDir
        }
    }
}
