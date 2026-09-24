package com.sabreware.aide.core.common.media

import com.sabreware.aide.core.common.storage.PlatformPaths
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * What an attached file IS, decided by extension at pick time — which decides how it travels:
 *
 *  - [Image]/[Audio] — the existing native attachment slots (gated on vision/audio capability).
 *  - [Pdf] — a native binary document ([com.sabreware.aide.core.domain.chat.AidePart.DocumentFile]), gated on
 *    `documentIn`; the provider receives the actual PDF.
 *  - [Text] — anything text-extractable. Inlined into the turn as plain text at send time, so it works on
 *    EVERY model — including on-device — with no capability gate (the "upload as text" pattern).
 *  - [Unsupported] — binary we can't represent (zip, xlsx, exe…). Refused at pick time with the reason;
 *    sending opaque bytes to a text model produces confident hallucination, not an error.
 */
enum class AttachmentKind { Image, Audio, Pdf, Text, Unsupported }

fun classifyAttachment(fileName: String): AttachmentKind {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in ImageExts -> AttachmentKind.Image
        in AudioExts -> AttachmentKind.Audio
        "pdf" -> AttachmentKind.Pdf
        in TextExts -> AttachmentKind.Text
        else -> AttachmentKind.Unsupported
    }
}

// The strict cross-provider set: Anthropic accepts ONLY jpeg/png/gif/webp, and file-picked images are sent
// in their original format (no re-encode). HEIC/BMP/AVIF photos still attach via the Photos tile, which
// re-encodes to JPEG.
private val ImageExts = setOf("png", "jpg", "jpeg", "webp", "gif")
private val AudioExts = setOf("wav", "mp3", "m4a", "aac", "ogg", "oga", "flac", "opus")
private val TextExts = setOf(
    "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "cfg",
    "conf", "properties", "env", "log", "diff", "patch", "tex", "rst", "adoc", "srt", "vtt",
    "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "c", "cc", "cpp", "h", "hpp", "rs", "go",
    "rb", "sh", "bash", "zsh", "swift", "sql", "html", "htm", "css", "scss", "gradle", "dart", "lua",
    "pl", "r", "scala", "cs", "fs", "php", "ex", "exs", "clj", "hs", "svelte", "vue", "proto", "graphql",
)

/** A picked file staged on the composer, awaiting send. */
data class PendingFileAttachment(val path: String, val name: String, val kind: AttachmentKind)

/**
 * Stages picked files as durable attachments. commonMain over okio + [PlatformPaths] — one impl for every
 * target; the picker (FileKit) hands us name + bytes, so no platform URI types cross this boundary.
 *
 * Files land in `filesDir/attachments` (NOT cache): a sent message references its PDF by path, so it must
 * outlive cache eviction to stay re-sendable. Same dir + naming convention as image/clip attachments.
 */
class FileAttachmentStore(
    private val paths: PlatformPaths,
    private val fileSystem: FileSystem,
    private val io: CoroutineDispatcher,
) {

    /** Copy [bytes] into the attachments dir; returns the stored path, or null on caps/IO failure. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun import(bytes: ByteArray, fileName: String): String? = withContext(io) {
        val kind = classifyAttachment(fileName)
        val cap = when (kind) {
            AttachmentKind.Text -> MAX_TEXT_BYTES
            else -> MAX_BINARY_BYTES
        }
        if (bytes.isEmpty() || bytes.size > cap) return@withContext null
        runCatching {
            val dir = paths.filesDir / "attachments"
            fileSystem.createDirectories(dir)
            val ext = fileName.substringAfterLast('.', "").lowercase().ifBlank { "bin" }
            val file = dir / "aide-file-${Uuid.random()}.$ext"
            fileSystem.write(file) { write(bytes) }
            file.toString()
        }.getOrNull()
    }

    /** UTF-8 content of a staged [AttachmentKind.Text] file — read back at send time for inlining. */
    suspend fun readText(path: String): String? = withContext(io) {
        runCatching { fileSystem.read(path.toPath()) { readUtf8() } }.getOrNull()
    }

    suspend fun delete(path: String) {
        withContext(io) { runCatching { fileSystem.delete(path.toPath()) } }
    }

    companion object {
        /** Inline-text cap — beyond this the turn stops being a prompt and starts being a context bomb. */
        const val MAX_TEXT_BYTES = 512_000
        /** Binary cap, sized to provider request limits (Anthropic caps requests ~32 MB total). */
        const val MAX_BINARY_BYTES = 20_000_000
    }
}
