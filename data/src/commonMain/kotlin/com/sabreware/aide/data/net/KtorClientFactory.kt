package com.sabreware.aide.data.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The transport knobs the common layer needs to vary, named in engine-neutral terms; the platform maps them
 * onto whatever engine it installs. Keeping this a parameter rather than a second `engineProvider` is what
 */
data class EngineOptions(
    /** Transport-level retry of a failed connection. Off for long-lived generation streams: a retry there
     *  replays a request whose tokens the caller has already begun consuming. */
    val retryOnConnectionFailure: Boolean = true,
)

/**
 * Ktor [HttpClient] factories — written ONCE in commonMain, run on every platform. The only platform
 * variable is the transport engine: the platform sets [engineProvider] at startup (OkHttp on Android/JVM,
 * Darwin on iOS) and fully configures it there (connection retry, redirects). Everything else — timeouts
 * (common `HttpTimeout`), SSE, ContentNegotiation, the streaming `Json`, per-request `Accept-Encoding`
 * pinning at the call sites — is common. Each call builds a fresh engine (via the provider) so a slow
 * search can't block model downloads (engine isolation, same as before).
 */
object KtorClientFactory {

    /**
     * Platform-injected transport. Set ONCE before any HTTP call (Android does it in `AideApp.onCreate`,
     * before `startKoin`). A fresh engine per invocation preserves per-client connection-pool isolation;
     * [EngineOptions] carries the per-client knobs the common layer varies.
     */
    lateinit var engineProvider: (EngineOptions) -> HttpClientEngine

    // Matches the stream clients' decoder: tolerate unknown keys, always emit defaults (stream/max_tokens),
    // omit nulls (some compat servers reject explicit JSON nulls).
    val streamingJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * How long a generation stream may go without a single byte before the socket is declared dead.
     * The socket timeout is BETWEEN reads, not overall — a generation can still run for hours, it just
     * cannot go silent: providers keep quiet periods alive (Anthropic sends `ping` events; token deltas
     * are otherwise continuous). Infinite was the old value, and it turned a dropped connection into a
     * reply that looked finished — the stream ended cleanly from the client's point of view and nothing
     * distinguished "server went away" from "model stopped talking".
     */
    private const val STREAM_IDLE_TIMEOUT_MS = 180_000L

    /**
     * Streaming client for SSE / NDJSON generations: short connect, INFINITE request timeout (a generation
     * can run for minutes) but a bounded idle gap ([STREAM_IDLE_TIMEOUT_MS]), with the `SSE` plugin.
     * `expectSuccess = false` so non-2xx is handled at the call site. Callers still pin
     * `Accept-Encoding: identity` per-request so gzip can't batch the token stream.
     */
    fun streaming(json: Json = streamingJson): HttpClient = HttpClient(engineProvider(EngineOptions())) {
        expectSuccess = false
        followRedirects = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = STREAM_IDLE_TIMEOUT_MS
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
        install(SSE)
    }

    /**
     * Client for the MCP StreamableHTTP transport: just the platform engine + the `SSE` plugin (the transport
     * needs it). No timeouts/ContentNegotiation — the MCP SDK owns request shaping. Engine-injected like the
     * rest, so the client is common (no OkHttp coupling in `McpClientImpl`).
     */
    fun mcp(): HttpClient = HttpClient(engineProvider(EngineOptions())) { install(SSE) }

    /**
     * Client for the model/asset download stack: 30s connect / 60s socket (catch stalled transfers) but no
     * overall request timeout (large files). No ContentNegotiation/SSE — downloads are raw bytes.
     */
    fun download(): HttpClient = HttpClient(engineProvider(EngineOptions())) {
        expectSuccess = false
        followRedirects = true
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    /**
     * One-shot client for search / fetch (finite timeouts). A read timeout surfaces as Ktor's own
     * `io.ktor.client.network.sockets.SocketTimeoutException` — the type `DuckDuckGoSearchClient` already
     * catches (alongside `java.net.SocketTimeoutException`) and maps to its `TIMEOUT` bucket.
     */
    fun finite(
        connectMs: Long = 10_000,
        readMs: Long = 15_000,
        json: Json = streamingJson,
    ): HttpClient = HttpClient(engineProvider(EngineOptions())) {
        expectSuccess = false
        followRedirects = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = connectMs
            socketTimeoutMillis = readMs
        }
    }

}
