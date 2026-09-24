package com.sabreware.aide.data.search

import com.sabreware.aide.core.domain.search.FetchOutcome
import com.sabreware.aide.core.domain.search.WebFetchProvider
import com.sabreware.aide.core.domain.search.WebFetchProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Locks the fetch-chain semantics (mirror of the search chain): prefer Ollama when available, fall through
 * to DuckDuckGo on unavailability OR a typed Error, and treat Empty/Text as terminal (no needless fallback).
 */
class ChainedWebFetcherTest {

    private class Fake(
        override val id: WebFetchProviderId,
        private val available: Boolean,
        private val result: FetchOutcome,
    ) : WebFetchProvider {
        var called = false
        override suspend fun isAvailable(): Boolean = available
        override suspend fun fetch(url: String, maxChars: Int): FetchOutcome {
            called = true
            return result
        }
    }

    private fun chainOf(vararg p: Fake) = ChainedWebFetcher(p.associateBy { it.id })

    @Test
    fun prefersOllama_whenAvailable_ddgNotCalled() = runTest {
        val ddg = Fake(WebFetchProviderId.DUCKDUCKGO, available = true, FetchOutcome.Text("ddg", false))
        val out = chainOf(
            Fake(WebFetchProviderId.OLLAMA, available = true, FetchOutcome.Text("ollama", false)),
            ddg,
        ).fetchOutcome("https://x", 100)
        assertEquals("ollama", (out as FetchOutcome.Text).text)
        assertFalse(ddg.called)
    }

    @Test
    fun ollamaUnavailable_fallsToDuckDuckGo_ollamaNotCalled() = runTest {
        val ollama = Fake(WebFetchProviderId.OLLAMA, available = false, FetchOutcome.Text("ollama", false))
        val out = chainOf(
            ollama,
            Fake(WebFetchProviderId.DUCKDUCKGO, available = true, FetchOutcome.Text("ddg", false)),
        ).fetchOutcome("https://x", 100)
        assertEquals("ddg", (out as FetchOutcome.Text).text)
        assertFalse(ollama.called)
    }

    @Test
    fun ollamaErrors_fallsToDuckDuckGo() = runTest {
        val ollama = Fake(WebFetchProviderId.OLLAMA, available = true, FetchOutcome.Error("HTTP_5XX", "boom"))
        val out = chainOf(
            ollama,
            Fake(WebFetchProviderId.DUCKDUCKGO, available = true, FetchOutcome.Text("ddg", false)),
        ).fetchOutcome("https://x", 100)
        assertEquals("ddg", (out as FetchOutcome.Text).text)
        assertTrue(ollama.called)
    }

    @Test
    fun emptyIsTerminal_noFallback() = runTest {
        val ddg = Fake(WebFetchProviderId.DUCKDUCKGO, available = true, FetchOutcome.Text("ddg", false))
        val out = chainOf(
            Fake(WebFetchProviderId.OLLAMA, available = true, FetchOutcome.Empty("blank_body")),
            ddg,
        ).fetchOutcome("https://x", 100)
        assertTrue(out is FetchOutcome.Empty)
        assertFalse(ddg.called)
    }
}
