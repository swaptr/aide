package com.sabreware.aide.data.tools.fs

import java.io.File
import java.nio.file.Files

/**
 * The one thing the two JVMs genuinely disagree about when listing a directory. Android has a curated
 * extension→type table (`android.webkit.MimeTypeMap`); a desktop JVM has NIO's probe. Injecting it is what
 * lets [JvmFileSystemBackend] be a single class.
 *
 * Returning null is normal and expected — callers fall back to the text-probe heuristic.
 */
fun interface MimeTypeResolver {

    fun mimeTypeFor(file: File): String?

    companion object {
        /** NIO content-type probe: extension table plus limited content sniffing. */
        val Probe = MimeTypeResolver { file ->
            runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
        }
    }
}
