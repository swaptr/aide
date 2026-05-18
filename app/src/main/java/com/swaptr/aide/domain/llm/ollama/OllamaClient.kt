package com.swaptr.aide.domain.llm.ollama

import android.util.Log
import com.swaptr.aide.data.provider.ConnectionTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resumeWithException

class OllamaClient(
    private val http: OkHttpClient,
    private val baseUrlProvider: () -> String?,
    private val bearerProvider: () -> String?,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun chat(request: OllamaChatRequest): Flow<OllamaChatStreamChunk> = channelFlow {
        val baseUrl = requireBaseUrl()
        val bodyText = json.encodeToString(OllamaChatRequest.serializer(), request)
        val body = bodyText.toRequestBody(JSON_MEDIA)
        val callId = nextCallId()
        Log.i(
            "AidePerf",
            "ollama.chat[$callId] POST $baseUrl/api/chat model=${request.model} " +
                "messages=${request.messages.size} tools=${request.tools?.size ?: 0} " +
                "think=${request.think} bodyBytes=${bodyText.length}",
        )
        val call = http.newCall(
            Request.Builder()
                .url("$baseUrl/api/chat")
                .post(body)
                .withStreamHeaders()
                .withAuth()
                .build(),
        )
        val callStartNs = System.nanoTime()
        var firstLineNs = 0L
        var lineCount = 0
        streamCall(call, tag = "chat[$callId]") { line ->
            if (firstLineNs == 0L) {
                firstLineNs = System.nanoTime()
                Log.i(
                    "AidePerf",
                    "ollama.chat[$callId] firstByteMs=${(firstLineNs - callStartNs) / 1_000_000}",
                )
            }
            lineCount++
            val chunk = runCatching { json.decodeFromString(OllamaChatStreamChunk.serializer(), line) }
                .getOrElse {
                    Log.w("AidePerf", "ollama.chat[$callId] bad NDJSON: ${it.message} line=${line.take(160)}")
                    trySend(OllamaChatStreamChunk(error = "Bad NDJSON: ${it.message}"))
                    return@streamCall
                }
            trySend(chunk)
            if (chunk.error != null) {
                Log.w("AidePerf", "ollama.chat[$callId] server-error: ${chunk.error}")
                close(IOException(chunk.error))
            }
            if (chunk.done) {
                Log.i(
                    "AidePerf",
                    "ollama.chat[$callId] done reason=${chunk.doneReason} " +
                        "lines=$lineCount totalMs=${(System.nanoTime() - callStartNs) / 1_000_000}",
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        runCatching { listTags() }
            .fold(
                onSuccess = { ConnectionTestResult.Ok(it.models.size) },
                onFailure = { ConnectionTestResult.Failed(it.message ?: it::class.java.simpleName) },
            )
    }

    suspend fun showModel(name: String): OllamaShowResponse = withContext(Dispatchers.IO) {
        val baseUrl = requireBaseUrl()
        val body = json.encodeToString(
            OllamaShowRequest.serializer(),
            OllamaShowRequest(model = name),
        ).toRequestBody(JSON_MEDIA)
        val call = http.newCall(
            Request.Builder()
                .url("$baseUrl/api/show")
                .post(body)
                .withAuth()
                .build(),
        )
        executeAwait(call).use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${resp.message}")
            val text = resp.body?.string().orEmpty()
            json.decodeFromString(OllamaShowResponse.serializer(), text)
        }
    }

    suspend fun listTags(): OllamaTagsResponse = withContext(Dispatchers.IO) {
        val baseUrl = requireBaseUrl()
        val call = http.newCall(
            Request.Builder().url("$baseUrl/api/tags").get().withAuth().build(),
        )
        executeAwait(call).use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${resp.message}")
            val text = resp.body?.string().orEmpty()
            json.decodeFromString(OllamaTagsResponse.serializer(), text)
        }
    }

    private fun Request.Builder.withAuth(): Request.Builder {
        val token = bearerProvider()
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        return this
    }

    // Pin Accept-Encoding: identity — OkHttp's default gzip + InflaterSource batches
    // NDJSON until the server closes the response, defeating per-token streaming.
    private fun Request.Builder.withStreamHeaders(): Request.Builder {
        header("Accept-Encoding", "identity")
        return this
    }

    private fun requireBaseUrl(): String = baseUrlProvider()
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("Ollama provider not configured (no base URL)")

    fun pull(name: String): Flow<OllamaPullStreamChunk> = channelFlow {
        val baseUrl = requireBaseUrl()
        val body = json.encodeToString(
            OllamaPullRequest.serializer(),
            OllamaPullRequest(model = name),
        ).toRequestBody(JSON_MEDIA)
        val call = http.newCall(
            Request.Builder()
                .url("$baseUrl/api/pull")
                .post(body)
                .withStreamHeaders()
                .withAuth()
                .build(),
        )
        streamCall(call) { line ->
            val chunk = runCatching { json.decodeFromString(OllamaPullStreamChunk.serializer(), line) }
                .getOrElse {
                    trySend(OllamaPullStreamChunk(error = "Bad NDJSON: ${it.message}"))
                    return@streamCall
                }
            trySend(chunk)
            if (chunk.error != null) close(IOException(chunk.error))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> ProducerScope<T>.streamCall(
        call: Call,
        tag: String = "stream",
        onLine: (String) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                executeAwait(call).use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string().orEmpty()
                        Log.w(
                            "AidePerf",
                            "ollama.$tag HTTP ${resp.code} ${resp.message} body=${errBody.take(200)}",
                        )
                        throw IOException("HTTP ${resp.code} ${resp.message}: $errBody")
                    }
                    val source = resp.body?.source()
                        ?: throw IOException("empty response body")
                    while (currentCoroutineContext().isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        onLine(line)
                    }
                }
            } catch (ce: CancellationException) {
                Log.i("AidePerf", "ollama.$tag cancelled")
                runCatching { call.cancel() }
                throw ce
            } catch (t: Throwable) {
                Log.w(
                    "AidePerf",
                    "ollama.$tag aborted: ${t.javaClass.simpleName}: ${t.message}",
                    t,
                )
                runCatching { call.cancel() }
                close(t)
                return@withContext
            }
            close()
        }
    }

    private suspend fun executeAwait(call: Call): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (cont.isActive) cont.resumeWith(Result.success(response))
                    else response.close()
                }
            })
        }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val callCounter = java.util.concurrent.atomic.AtomicLong()
        private fun nextCallId(): Long = callCounter.incrementAndGet()
    }
}
