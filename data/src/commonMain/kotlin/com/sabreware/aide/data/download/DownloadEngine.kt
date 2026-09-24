package com.sabreware.aide.data.download

import com.sabreware.aide.data.net.KtorClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readRemaining
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.buffer

// Range-resumable. HEAD is authoritative; catalog size is hint only. .part bigger than
// server → truncate and restart (avoids 416 loop). Ktor [HttpClient] (platform engine): streams the body
// off a [io.ktor.utils.io.ByteReadChannel] in chunks; the Range/206/offset state machine is unchanged.
// commonMain (okio): the byte plumbing that was RandomAccessFile is now an appending okio sink — identical
// resume/finalize semantics (okio.FileSystem.SYSTEM is the same OS filesystem on Android + JVM), just no
// java.io. Callers own the storage layout and pass the `.part`/final okio paths.
class DownloadEngine(
    private val fs: FileSystem,
    private val ioDispatcher: CoroutineDispatcher,
    private val client: HttpClient = KtorClientFactory.download(),
) {

    // Callers that own their own storage layout (LLM + speech pipelines) pass the resolved okio paths.
    suspend fun downloadFile(
        url: String,
        authToken: String? = null,
        partFile: Path,
        finalFile: Path,
        onProgress: suspend (downloaded: Long, total: Long, bytesPerSec: Long) -> Unit,
    ) = withContext(ioDispatcher) {
        val serverTotal = headTotal(url, authToken)

        val finalSize = fs.metadataOrNull(finalFile)?.size
        if (finalSize != null && (serverTotal <= 0 || finalSize == serverTotal)) {
            onProgress(finalSize, finalSize, 0L)
            return@withContext
        }

        var partSize = fs.metadataOrNull(partFile)?.size ?: -1L // -1 = absent

        if (partSize >= 0 && serverTotal > 0 && partSize == serverTotal) {
            finalizeRename(partFile, finalFile)
            onProgress(serverTotal, serverTotal, 0L)
            return@withContext
        }

        if (partSize >= 0 && serverTotal > 0 && partSize > serverTotal) {
            fs.delete(partFile)
            partSize = -1L
        }

        val existing = if (partSize >= 0) partSize else 0L

        // execute {} returns true if the block already finalized (the 416 past-EOF case), so the
        // outer code skips the trailing rename. (`return@withContext` can't cross the non-inline
        // execute lambda, hence the flag.)
        val finalizedInBlock = client.prepareGet(url) {
            if (existing > 0) header("Range", "bytes=$existing-")
            if (!authToken.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $authToken")
        }.execute { response ->
            if (response.status.value == 416 && existing > 0 && serverTotal > 0 && existing >= serverTotal) {
                // Already past end-of-file. Truncate the part to total and finalize.
                fs.openReadWrite(partFile).use { it.resize(serverTotal) }
                finalizeRename(partFile, finalFile)
                onProgress(serverTotal, serverTotal, 0L)
                return@execute true
            }
            if (!response.status.isSuccess()) {
                throw IOException("HTTP ${response.status.value} ${response.status.description}")
            }

            val total = computeTotal(response, existing, serverTotal)
            val resuming = response.status.value == 206

            // Fresh transfer (200) over a stale part: drop it so the appending sink starts at 0.
            if (!resuming && existing > 0) {
                fs.delete(partFile)
            }

            // appendingSink continues at the current file end: `existing` bytes for a 206 resume, 0 for a
            // fresh 200 (part was just deleted / never existed). Segment writes flush to disk as they fill,
            // and close() (via use{}) flushes the tail — so on cancellation the `.part` is a clean prefix.
            var downloaded = if (resuming) existing else 0L
            fs.appendingSink(partFile).buffer().use { sink ->
                val channel = response.bodyAsChannel()
                var lastTick = TimeSource.Monotonic.markNow()
                var bytesSinceTick = 0L
                var bps = 0L
                onProgress(downloaded, total, 0L)
                while (currentCoroutineContext().isActive && !channel.exhausted()) {
                    val bytes = channel.readRemaining(CHUNK_BYTES).readByteArray()
                    if (bytes.isEmpty()) continue
                    sink.write(bytes)
                    downloaded += bytes.size
                    bytesSinceTick += bytes.size
                    val elapsed = lastTick.elapsedNow()
                    if (elapsed >= TICK_INTERVAL) {
                        val elapsedNs = elapsed.inWholeNanoseconds
                        bps = if (elapsedNs > 0) (bytesSinceTick * 1_000_000_000L) / elapsedNs else 0L
                        lastTick = TimeSource.Monotonic.markNow()
                        bytesSinceTick = 0L
                        onProgress(downloaded, total, bps)
                    }
                }
                sink.flush()
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("download cancelled")
                }
                onProgress(downloaded, total, bps)
            }
            false
        }

        if (!finalizedInBlock) finalizeRename(partFile, finalFile)
    }

    private fun finalizeRename(partFile: Path, finalFile: Path) {
        if (fs.exists(finalFile)) fs.delete(finalFile)
        // okio.atomicMove throws IOException if the OS rename fails (same failure mode as the old renameTo).
        fs.atomicMove(partFile, finalFile)
    }

    private suspend fun headTotal(url: String, authToken: String?): Long = runCatching {
        val resp = client.head(url) {
            if (!authToken.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $authToken")
        }
        if (!resp.status.isSuccess()) return@runCatching -1L
        resp.headers["Content-Length"]?.toLongOrNull() ?: -1L
    }.getOrDefault(-1L)

    private fun computeTotal(response: HttpResponse, existing: Long, serverHeadTotal: Long): Long {
        val contentRange = response.headers["Content-Range"]
        if (contentRange != null) {
            val total = contentRange.substringAfter('/', "").toLongOrNull()
            if (total != null && total > 0) return total
        }
        val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
        if (response.status.value == 206 && contentLength > 0) return existing + contentLength
        if (contentLength > 0) return contentLength
        return serverHeadTotal
    }

    companion object {
        private const val CHUNK_BYTES = 64L * 1024L
        private val TICK_INTERVAL = 500.milliseconds
    }
}
