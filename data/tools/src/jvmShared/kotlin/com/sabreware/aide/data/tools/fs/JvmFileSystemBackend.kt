package com.sabreware.aide.data.tools.fs

import com.sabreware.aide.core.domain.tools.fs.FileSystemBackend
import com.sabreware.aide.core.domain.tools.fs.FsEntry
import com.sabreware.aide.core.domain.tools.fs.FsErrorCode
import com.sabreware.aide.core.domain.tools.fs.FsException
import com.sabreware.aide.core.domain.tools.fs.FsTextDetect
import com.sabreware.aide.core.domain.tools.fs.PathSandbox
import com.sabreware.aide.core.domain.tools.fs.ReadTextResult
import com.sabreware.aide.core.domain.tools.fs.RegisteredRoot
import com.sabreware.aide.core.domain.tools.fs.RootBackendKind
import java.io.File
import java.io.IOException

/**
 * The direct `java.io.File` filesystem backend — sandboxed navigation, listing, globbing, text detection.
 *
 * Written once for both JVMs. Android and desktop ran near-identical 240-line copies of this whose real
 * difference was four lines of MIME lookup, so that one difference is injected ([MimeTypeResolver]) and the
 * rest lives in `src/jvmShared` — one directory compiled by both targets. It is not commonMain because the
 * sandbox walk is `java.io`, which a non-JVM target does not have.
 */
class JvmFileSystemBackend(
    private val mime: MimeTypeResolver = MimeTypeResolver.Probe,
) : FileSystemBackend {

    override val kind: RootBackendKind = RootBackendKind.DIRECT

    override fun list(
        root: RegisteredRoot,
        relPath: String,
        recursive: Boolean,
        maxEntries: Int,
    ): List<FsEntry> {
        val dir = navigateDir(root, relPath)
        val out = ArrayList<FsEntry>()
        if (recursive) {
            walk(dir, relPath, maxDepth = 6) { entry ->
                out += entry
                out.size < maxEntries
            }
        } else {
            val children = dir.listFiles().orEmpty()
            for (child in children) {
                if (out.size >= maxEntries) break
                out += child.toFsEntry(joinRel(relPath, child.name))
            }
        }
        return out
    }

    override fun find(
        root: RegisteredRoot,
        relPath: String,
        pattern: String,
        mimePrefix: String?,
        maxDepth: Int,
        maxHits: Int,
    ): List<FsEntry> {
        val start = navigateDir(root, relPath)
        val regex = PathSandbox.globToRegex(pattern)
        val out = ArrayList<FsEntry>()
        walk(start, relPath, maxDepth) { entry ->
            if (!entry.isDir && regex.matches(entry.name)) {
                val mimeOk = mimePrefix == null ||
                    (entry.mimeType?.startsWith(mimePrefix, ignoreCase = true) == true)
                if (mimeOk) out += entry
            }
            out.size < maxHits
        }
        return out
    }

    override fun info(root: RegisteredRoot, relPath: String): FsEntry? {
        val file = resolve(root, relPath)
        if (!file.exists()) return null
        return file.toFsEntry(PathSandbox.normalize(relPath))
    }

    override fun readText(root: RegisteredRoot, relPath: String, maxBytes: Int): ReadTextResult {
        val file = resolve(root, relPath)
        if (!file.exists()) return ReadTextResult.NotFound
        if (file.isDirectory) throw FsException(FsErrorCode.IS_A_DIR, "path is a directory")
        val entry = file.toFsEntry(PathSandbox.normalize(relPath))

        val mimeText = FsTextDetect.isLikelyTextFromMime(entry.mimeType)
        if (mimeText == false) return ReadTextResult.Binary(entry, "mime=${entry.mimeType}")

        file.inputStream().use { input ->
            val probe = ByteArray(1024)
            val probeLen = input.read(probe).coerceAtLeast(0)
            if (mimeText == null && !FsTextDetect.isProbablyText(probe.copyOf(probeLen))) {
                return ReadTextResult.Binary(entry, "binary bytes in probe")
            }
            val buffer = ByteArray(maxBytes + 1)
            System.arraycopy(probe, 0, buffer, 0, probeLen)
            var read = probeLen
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            val truncated = read > maxBytes
            val text = String(buffer, 0, minOf(read, maxBytes), Charsets.UTF_8)
            return ReadTextResult.Text(entry, text, truncated)
        }
    }

    override fun mkdir(root: RegisteredRoot, relPath: String): FsEntry {
        val target = resolve(root, relPath)
        if (target.exists() && !target.isDirectory) {
            throw FsException(FsErrorCode.EXISTS, "path exists as file")
        }
        if (!target.exists() && !target.mkdirs()) {
            throw FsException(FsErrorCode.IO_ERROR, "mkdirs failed")
        }
        return target.toFsEntry(PathSandbox.normalize(relPath))
    }

    override fun move(
        root: RegisteredRoot,
        fromRel: String,
        toRel: String,
        overwrite: Boolean,
    ): FsEntry {
        val source = resolve(root, fromRel)
        if (!source.exists()) throw FsException(FsErrorCode.NOT_FOUND, "source not found")
        val dest = resolve(root, toRel)
        if (dest.exists()) {
            if (!overwrite) throw FsException(FsErrorCode.EXISTS, "destination exists")
            if (!dest.delete()) throw FsException(FsErrorCode.IO_ERROR, "could not overwrite")
        }
        dest.parentFile?.mkdirs()
        if (!source.renameTo(dest)) {
            try {
                source.copyTo(dest, overwrite = false)
                source.delete()
            } catch (e: IOException) {
                throw FsException(FsErrorCode.IO_ERROR, e.message ?: "move failed")
            }
        }
        return dest.toFsEntry(PathSandbox.normalize(toRel))
    }

    override fun copy(
        root: RegisteredRoot,
        fromRel: String,
        toRel: String,
        overwrite: Boolean,
    ): FsEntry {
        val source = resolve(root, fromRel)
        if (!source.exists()) throw FsException(FsErrorCode.NOT_FOUND, "source not found")
        if (source.isDirectory) throw FsException(FsErrorCode.IS_A_DIR, "copy of directories not supported")
        val dest = resolve(root, toRel)
        if (dest.exists() && !overwrite) throw FsException(FsErrorCode.EXISTS, "destination exists")
        dest.parentFile?.mkdirs()
        try {
            source.copyTo(dest, overwrite = overwrite)
        } catch (e: IOException) {
            throw FsException(FsErrorCode.IO_ERROR, e.message ?: "copy failed")
        }
        return dest.toFsEntry(PathSandbox.normalize(toRel))
    }

    override fun delete(
        root: RegisteredRoot,
        relPath: String,
        useTrash: Boolean,
    ): FileSystemBackend.DeleteResult {
        val file = resolve(root, relPath)
        if (!file.exists()) throw FsException(FsErrorCode.NOT_FOUND, "not found")
        if (!file.deleteRecursively()) {
            throw FsException(FsErrorCode.IO_ERROR, "delete failed")
        }
        return FileSystemBackend.DeleteResult(deleted = true, trashed = false)
    }

    private fun resolve(root: RegisteredRoot, relPath: String): File {
        val rootDir = File(root.absolutePath)
        if (!rootDir.exists()) {
            throw FsException(FsErrorCode.BACKEND_UNAVAILABLE, "root path missing: ${root.absolutePath}")
        }
        val rootCanonical = rootDir.canonicalFile
        val segs = PathSandbox.segments(relPath)
        var cursor = rootDir
        for (seg in segs) cursor = File(cursor, seg)
        val canonical = try {
            cursor.canonicalFile
        } catch (e: IOException) {
            cursor.absoluteFile
        }
        if (!canonical.path.startsWith(rootCanonical.path)) {
            throw FsException(FsErrorCode.PATH_ESCAPE, "resolved outside root")
        }
        return canonical
    }

    private fun navigateDir(root: RegisteredRoot, relPath: String): File {
        val f = resolve(root, relPath)
        if (!f.exists()) throw FsException(FsErrorCode.NOT_FOUND, "directory not found")
        if (!f.isDirectory) throw FsException(FsErrorCode.NOT_A_DIR, "not a directory")
        return f
    }

    private fun walk(
        start: File,
        startRel: String,
        maxDepth: Int,
        visit: (FsEntry) -> Boolean,
    ) {
        data class Frame(val node: File, val rel: String, val depth: Int)
        val queue = ArrayDeque<Frame>()
        queue.addLast(Frame(start, startRel, 0))
        while (queue.isNotEmpty()) {
            val frame = queue.removeFirst()
            if (!frame.node.isDirectory) continue
            val children = frame.node.listFiles().orEmpty()
            for (child in children) {
                val rel = joinRel(frame.rel, child.name)
                val entry = child.toFsEntry(rel)
                if (!visit(entry)) return
                if (child.isDirectory && frame.depth + 1 <= maxDepth) {
                    queue.addLast(Frame(child, rel, frame.depth + 1))
                }
            }
        }
    }

    private fun joinRel(parent: String, name: String): String =
        if (parent.isBlank()) name else "$parent/$name"

    private fun File.toFsEntry(relPath: String): FsEntry = FsEntry(
        name = name,
        relPath = relPath,
        isDir = isDirectory,
        sizeBytes = if (isDirectory) null else length(),
        mimeType = if (isDirectory) null else mime.mimeTypeFor(this),
        lastModifiedMs = lastModified().takeIf { it > 0 },
    )
}
