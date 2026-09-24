package com.sabreware.aide.app.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.RedirectReceiver
import com.sabreware.aide.core.domain.connector.oauth.RedirectResult
import com.sabreware.aide.data.connector.oauth.PendingOAuthFlow
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Loopback redirect (MCP-ecosystem default, spec-compliant): binds a transient `ServerSocket` on
 * `127.0.0.1:<random>`, serves one "you can close this tab" page, and feeds the captured code/state into
 * [PendingOAuthFlow]. The gate is armed and the blocking `accept()` is launched **at construction** (before
 * the browser opens), so a redirect can never race ahead of arming. On `accept()` the flow is marked
 * receiving so a concurrent resume-cancel can't drop a completing sign-in. The socket is loopback-only
 * (backlog 1, not reachable off-device) and is always closed on exit.
 */
class LoopbackRedirectReceiver(
    scope: CoroutineScope,
    io: CoroutineDispatcher,
) : RedirectReceiver {

    private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    override val redirectUri: String = "http://127.0.0.1:${serverSocket.localPort}/cb"

    private val armed = PendingOAuthFlow.arm(onCancel = { runCatching { serverSocket.close() } })

    init {
        scope.launch(io) {
            runCatching {
                serverSocket.soTimeout = ACCEPT_TIMEOUT_MS
                serverSocket.accept().use { socket ->
                    PendingOAuthFlow.markReceiving(armed.token)
                    val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    val query = requestLine.split(' ').getOrNull(1)?.substringAfter('?', "").orEmpty()
                    socket.getOutputStream().bufferedWriter().apply { write(RESPONSE); flush() }
                    val params = parseQuery(query)
                    PendingOAuthFlow.deliver(armed.token, RedirectResult(params["code"], params["state"], params["error"]))
                }
            }.onFailure {
                // Socket closed by cancel, or the accept timed out — deliver only if this flow is still current.
                PendingOAuthFlow.deliver(armed.token, RedirectResult(null, null, it.message ?: "loopback timed out"))
            }
        }
    }

    override suspend fun await(): RedirectResult = try {
        armed.deferred.await()
    } finally {
        runCatching { serverSocket.close() }
    }

    override fun cancel() {
        runCatching { serverSocket.close() }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotBlank() }.mapNotNull { pair ->
            runCatching {
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }.getOrNull()
        }.toMap()

    private companion object {
        const val ACCEPT_TIMEOUT_MS = 5 * 60 * 1000
        const val RESPONSE =
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n" +
                "<!doctype html><html><body style=\"font-family:sans-serif;text-align:center;padding-top:3rem\">" +
                "<h3>You can close this tab and return to Aide.</h3></body></html>"
    }
}
