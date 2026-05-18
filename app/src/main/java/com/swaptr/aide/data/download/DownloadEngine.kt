package com.swaptr.aide.data.download

import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.storage.ModelStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

// Range-resumable. HEAD is authoritative; catalog size is hint only. .part bigger than
// server → truncate and restart (avoids 416 loop).
class DownloadEngine(
    private val storage: ModelStorage,
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun download(
        spec: ModelSpec,
        authToken: String? = null,
        onProgress: suspend (downloaded: Long, total: Long, bytesPerSec: Long) -> Unit,
    ) {
        val downloadUrl = spec.downloadUrl
            ?: throw IllegalArgumentException("ModelSpec '${spec.id}' has no downloadUrl (remote provider?)")
        downloadFile(
            url = downloadUrl,
            authToken = authToken,
            partFile = storage.partFile(spec),
            finalFile = storage.modelFile(spec),
            onProgress = onProgress,
        )
    }

    // For callers that own their own storage layout (e.g. speech-asset pipeline).
    suspend fun downloadFile(
        url: String,
        authToken: String? = null,
        partFile: File,
        finalFile: File,
        onProgress: suspend (downloaded: Long, total: Long, bytesPerSec: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val downloadUrl = url

        val serverTotal = headTotal(downloadUrl, authToken)

        if (finalFile.exists() && (serverTotal <= 0 || finalFile.length() == serverTotal)) {
            onProgress(finalFile.length(), finalFile.length(), 0L)
            return@withContext
        }

        if (partFile.exists() && serverTotal > 0 && partFile.length() == serverTotal) {
            finalizeRename(partFile, finalFile)
            onProgress(serverTotal, serverTotal, 0L)
            return@withContext
        }

        if (partFile.exists() && serverTotal > 0 && partFile.length() > serverTotal) {
            partFile.delete()
        }

        val existing = if (partFile.exists()) partFile.length() else 0L

        val requestBuilder = Request.Builder().url(downloadUrl)
        if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")
        if (!authToken.isNullOrBlank()) requestBuilder.header("Authorization", "Bearer $authToken")

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 416 && existing > 0 && serverTotal > 0 && existing >= serverTotal) {
                // Already past end-of-file. Truncate the part to total and finalize.
                RandomAccessFile(partFile, "rw").use { it.setLength(serverTotal) }
                finalizeRename(partFile, finalFile)
                onProgress(serverTotal, serverTotal, 0L)
                return@withContext
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} ${response.message}")
            }

            val total = computeTotal(response, existing, serverTotal)
            val resuming = response.code == 206
            val startOffset = if (resuming) existing else 0L

            if (!resuming && existing > 0) {
                partFile.delete()
            }

            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(startOffset)
                response.body!!.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = startOffset
                    var lastTickNs = System.nanoTime()
                    var bytesSinceTick = 0L
                    var bps = 0L
                    onProgress(downloaded, total, 0L)
                    while (currentCoroutineContext().isActive) {
                        val n = input.read(buf)
                        if (n == -1) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        bytesSinceTick += n
                        val now = System.nanoTime()
                        val elapsedNs = now - lastTickNs
                        if (elapsedNs >= 500_000_000L) {
                            bps = (bytesSinceTick * 1_000_000_000L) / elapsedNs
                            lastTickNs = now
                            bytesSinceTick = 0L
                            onProgress(downloaded, total, bps)
                        }
                    }
                    if (!currentCoroutineContext().isActive) {
                        throw CancellationException("download cancelled")
                    }
                    onProgress(downloaded, total, bps)
                }
            }
        }

        finalizeRename(partFile, finalFile)
    }

    private fun finalizeRename(partFile: java.io.File, finalFile: java.io.File) {
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            throw IOException("Failed to finalize ${finalFile.name}")
        }
    }

    private fun headTotal(url: String, authToken: String?): Long {
        val req = Request.Builder().url(url).head().apply {
            if (!authToken.isNullOrBlank()) header("Authorization", "Bearer $authToken")
        }.build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use -1L
                resp.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        }.getOrDefault(-1L)
    }

    private fun computeTotal(response: Response, existing: Long, serverHeadTotal: Long): Long {
        val contentRange = response.headers["Content-Range"]
        if (contentRange != null) {
            val total = contentRange.substringAfter('/', "").toLongOrNull()
            if (total != null && total > 0) return total
        }
        val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
        if (response.code == 206 && contentLength > 0) return existing + contentLength
        if (contentLength > 0) return contentLength
        return serverHeadTotal
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
