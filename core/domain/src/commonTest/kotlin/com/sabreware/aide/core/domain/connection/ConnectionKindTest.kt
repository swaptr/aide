package com.sabreware.aide.core.domain.connection

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionKindTest {

    private fun assertKind(expected: ConnectionKind, vararg urls: String) = urls.forEach { url ->
        assertEquals(expected, ConnectionKind.of(url), url)
    }

    @Test
    fun `private endpoints are self-hosted`() = assertKind(
        ConnectionKind.SelfHosted,
        "http://localhost:11434/v1",
        "http://127.0.0.1:8080",
        "http://10.0.0.5:11434/v1",
        "http://192.168.1.20/v1",
        "http://172.16.0.1/v1",
        "http://172.31.255.254:8000",
        "http://100.64.0.7:11434", // CGNAT / Tailscale
        "http://100.127.1.1",
        "http://ollama.local:11434/v1",
        "http://gpu-box:11434/v1", // a bare machine name
        "http://[::1]:11434/v1",
        "http://[fd00::1]/v1",
        "http://user@192.168.0.2:8080/v1",
    )

    @Test
    fun `public endpoints are cloud`() = assertKind(
        ConnectionKind.Cloud,
        "https://api.openai.com/v1",
        "https://ollama.com/v1",
        "https://openrouter.ai/api/v1",
        "http://172.32.0.1/v1", // just outside 172.16/12
        "http://100.128.0.1", // just outside 100.64/10
        "http://8.8.8.8",
    )

    @Test
    fun `a connection's kind follows its endpoint`() {
        val local = Connection(id = "openai-abc123", vendor = "openai", label = "Ollama", baseUrl = "http://localhost:11434/v1")
        assertEquals(ConnectionKind.SelfHosted, local.kind)
        assertEquals(ConnectionKind.Cloud, local.copy(baseUrl = "https://ollama.com/v1").kind)
    }
}
