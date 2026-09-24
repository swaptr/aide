package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The socket transport's two decisions that are checkable without a real upgrade: what the handshake
 * carries, and what a client missing the plugin is told.
 */
class ProviderSocketTest {

    /** Records the handshake request, then fails the upgrade — the frames are not what is under test. */
    private fun recordingClient(): Pair<HttpClient, MutableList<Map<String, List<String>>>> {
        val seen = mutableListOf<Map<String, List<String>>>()
        val client = HttpClient(
            MockEngine { request ->
                seen += request.headers.entries().associate { it.key to it.value }
                respond("no upgrade", HttpStatusCode.BadRequest)
            },
        ) {
            // The plugin has to be installed for the handshake to be ISSUED at all — without it the
            // session fails at plugin lookup, before any request, which is the third test below.
            install(WebSockets)
        }
        return client to seen
    }

    @Test
    fun `subprotocols travel as one comma-joined header`() = runTest {
        val (client, seen) = recordingClient()

        // The upgrade fails — MockEngine cannot serve one — but the handshake is already recorded.
        assertFailsWith<Throwable> {
            ProviderSocket(client)
                .textFrames(
                    url = "wss://example.test/realtime",
                    protocols = listOf("realtime", "openai-insecure-api-key.sk-test"),
                )
                .toList()
        }

        val header = seen.single()[SUBPROTOCOL_HEADER]?.joinToString(", ")
        // RFC 6455 defines the field as a comma-separated LIST; a repeated header makes a server take
        // the first entry and ignore the rest, which for OpenAI drops the credential half.
        assertEquals("realtime, openai-insecure-api-key.sk-test", header)
    }

    @Test
    fun `no subprotocols means no header at all`() = runTest {
        val (client, seen) = recordingClient()

        assertFailsWith<Throwable> {
            ProviderSocket(client).textFrames(url = "wss://example.test/live").toList()
        }

        // Every existing caller passes none, and an empty header is not the same as an absent one.
        assertNull(seen.single()[SUBPROTOCOL_HEADER])
    }

    @Test
    fun `a client without the WebSockets plugin is told which plugin, and not retried`() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })

        val failure = assertFailsWith<APICallError> {
            ProviderSocket(client).textFrames(url = "wss://example.test/live").toList()
        }

        assertTrue(failure.message.orEmpty().contains("WebSockets"), failure.message.orEmpty())
        // Nothing about the client changes between attempts, so retrying spends the whole budget
        // rediscovering the same cause.
        assertEquals(false, failure.isRetryable)
    }

    @Test
    fun `an https base becomes wss, and a ws url is left alone`() {
        assertEquals("wss://api.example.test/v1", webSocketUrl("https://api.example.test/v1"))
        assertEquals("ws://localhost:8080", webSocketUrl("http://localhost:8080"))
        // Already a socket URL: rewriting it again would corrupt the scheme.
        assertEquals("wss://api.example.test/v1", webSocketUrl("wss://api.example.test/v1"))
    }
}
