package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A WebSocket to a provider, over the same **injected** [HttpClient] the HTTP verbs use.
 *
 * This exists because live speech has no HTTP shape. Every vendor that transcribes or translates a
 * running microphone — ElevenLabs, Cartesia, OpenAI's realtime endpoint, xAI — does it over a socket,
 * and there is no chunked-HTTP fallback to reach them by. So `TranscriptionModel.doStream` and the
 * translation contract were declared and permanently unimplementable until this landed.
 *
 * The shape is deliberately narrow. It is not a general socket client: it opens one session, pushes what
 * the caller sends, and hands back a cold [Flow] of text frames. Framing, protocol handshakes and
 * keep-alives belong to the provider, because only the provider knows what its vendor's protocol is —
 * the same division [ProviderHttp.postBytes] already makes for AWS's binary event stream.
 *
 * Cancelling the collector closes the socket. That is why nothing here takes an abort parameter, and it
 * is what makes "the user stopped talking" and "the user closed the app" the same code path.
 *
 * **The injected client must have Ktor's `WebSockets` plugin installed.** This module cannot install it:
 * it does not build the client, deliberately, and a plugin added to someone else's client changes
 * behaviour for every other caller of it. A client without the plugin fails on the first session with a
 * message naming the plugin, which is the failure this paragraph exists to shorten.
 */
public class ProviderSocket(
    private val client: HttpClient,
    private val json: Json = ProviderJson,
) {

    /**
     * Opens [url], runs [send] against the session, and emits every text frame the server returns.
     *
     * [send] is launched as a CHILD of the flow's scope rather than called ahead of the read loop,
     * because a transcription session is duplex in both directions at once. A sequential send would not
     * return until the microphone stopped, so nothing the server said in the meantime could be read —
     * and every vendor that gates audio on a `session_started` frame (ElevenLabs, xAI) would deadlock
     * outright, each side waiting on the other.
     *
     * @param url the `ws`/`wss` endpoint — see [webSocketUrl].
     * @param headers extra request headers for the handshake — authentication, mostly.
     * @param protocols the WebSocket subprotocols to offer — see [SUBPROTOCOL_HEADER]. Empty offers none.
     * @param onClose told why the server hung up, when the close was not an orderly goodbye.
     * @param send writes the caller's half of the session — audio frames, control messages.
     */
    public fun textFrames(
        url: String,
        headers: Map<String, String> = emptyMap(),
        protocols: List<String> = emptyList(),
        // Before `send`, so `send` stays the trailing lambda every caller passes as a block. A parameter
        // added after it silently rebinds those blocks to this one, which type-checks at the declaration
        // and fails at every call site.
        onClose: (SocketClosed) -> Unit = {},
        send: suspend (SocketSender) -> Unit = {},
    ): Flow<String> = channelFlow {
        val session = try {
            client.webSocketSession(url) {
                headers.forEach { (k, v) -> header(k, v) }
                // One comma-joined header rather than one per protocol: RFC 6455 defines the field as a
                // comma-separated list, and a repeated header is what makes a server pick the first
                // entry and ignore the rest — which for OpenAI means dropping the credential half.
                if (protocols.isNotEmpty()) header(SUBPROTOCOL_HEADER, protocols.joinToString(", "))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            val missingPlugin = e.namesMissingWebSocketsPlugin()
            throw APICallError(
                message = if (missingPlugin) {
                    "WebSocket to $url needs Ktor's `WebSockets` plugin on the injected HttpClient, " +
                        "which this library deliberately does not build: ${e.message}"
                } else {
                    "WebSocket to $url failed to open: ${e.message ?: e::class.simpleName}"
                },
                url = url,
                // A socket that never opened is the same class of failure as a connection that dropped,
                // and the caller's retry policy should treat it the same way. A missing plugin is the one
                // exception: nothing about the client changes between attempts, so retrying spends the
                // whole budget rediscovering it and reports the last attempt instead of the cause.
                isRetryable = !missingPlugin,
                cause = e,
            )
        }

        val pump = launch { send(SocketSender(session, json)) }
        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> channel.send(frame.readText())
                    // Binary frames carry audio on the translation wire; a provider that wants them
                    // reads the session itself rather than being handed a lossy text decoding here.
                    else -> Unit
                }
            }
            // The server closed. Whether that was orderly is the provider's to judge — a close after the
            // final transcript is the normal end, and the identical close one frame earlier is a failure
            // the caller needs described. Only the socket knows the code and reason, so they are reported
            // here rather than left as "the flow ended", which is what every close looked like before.
            val reason = session.closeReason.await()
            if (reason != null && reason.code != NORMAL_CLOSURE) {
                onClose(SocketClosed(reason.code, reason.message))
            }
        } finally {
            pump.cancel()
            // `close` rather than `cancel`: a vendor that is mid-transcript flushes what it has on a
            // clean close and discards it on an abort, so the difference is the tail of the transcript.
            // NonCancellable because the common way to leave this block is the collector cancelling,
            // and a suspend call in an already-cancelled scope returns without ever sending the frame.
            withContext(NonCancellable) { runCatching { session.close() } }
        }
    }
}

/**
 * `Sec-WebSocket-Protocol`, the handshake's one extension point for saying what the session speaks.
 *
 * It exists here because a vendor uses it for **authentication**, not for content negotiation. OpenAI's
 * realtime endpoint takes no `Authorization` header — it reads the API key out of a subprotocol entry
 * (`realtime`, then `openai-insecure-api-key.<token>`), because the browser WebSocket API cannot set a
 * request header and this is the only field it can populate. Without this parameter that endpoint is
 * unreachable from any client, browser or not, which is why it was the one thing blocking the realtime
 * port rather than a provider-shaped gap.
 */
public const val SUBPROTOCOL_HEADER: String = "Sec-WebSocket-Protocol"

/**
 * Why the server hung up, when it was not an orderly goodbye.
 *
 * A provider needs both halves to say anything useful: the code separates a policy rejection from a
 * server fault, and the reason is where a vendor puts the sentence a user could act on. Reporting "the
 * stream ended" for all of them is what makes a mid-session failure indistinguishable from success.
 */
public data class SocketClosed(val code: Short, val reason: String)

/** RFC 6455's orderly shutdown. Anything else ended the session before the provider was finished. */
private const val NORMAL_CLOSURE: Short = 1000

/**
 * The `ws`/`wss` form of an HTTP base URL.
 *
 * Every vendor documents its socket endpoint against the same host as its REST base, and a caller
 * pointing the provider at a proxy or a test host configures one base URL, not two. Handing Ktor an
 * `https` URL for a socket is an upgrade that never happens rather than a legible error, so the
 * conversion belongs beside the transport instead of being re-derived in each provider.
 */
public fun webSocketUrl(url: String): String = when {
    url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
    url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
    else -> url
}

/**
 * Ktor raises this from the plugin lookup, before any I/O, when the injected client never installed
 * `WebSockets`. Matched on the wording rather than the exception type because Ktor spells a failed
 * connect with the same type, and the two differ in whether retrying can ever help.
 */
private fun Throwable.namesMissingWebSocketsPlugin(): Boolean =
    message?.let { it.contains("WebSockets") && it.contains("not installed") } == true

/** The half of a session a provider writes to. */
public class SocketSender internal constructor(
    private val session: io.ktor.websocket.WebSocketSession,
    private val json: Json,
) {

    /** Sends one text frame as-is. */
    public suspend fun sendText(text: String) {
        session.send(Frame.Text(text))
    }

    /** Sends [value] serialized, as a text frame — the shape most vendor control messages take. */
    public suspend fun sendJson(value: JsonElement) {
        session.send(Frame.Text(json.encodeToString(JsonElement.serializer(), value)))
    }

    /** Sends one binary frame — audio, on every wired vendor. */
    public suspend fun sendBytes(bytes: ByteArray) {
        session.send(Frame.Binary(fin = true, data = bytes))
    }
}
