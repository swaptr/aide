package com.sabreware.aide.data.llm.gemini

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A stored Gemini base URL from before the `:aisdk` switch names no API version; the provider builds
 * `{base}/models/{id}:…` and 404s on it. The normaliser is what keeps those configs working.
 */
class GeminiBaseUrlTest {

    @Test
    fun `a bare host gets the provider's default version appended`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            GeminiModels.baseUrl("https://generativelanguage.googleapis.com"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            GeminiModels.baseUrl("https://generativelanguage.googleapis.com/"),
        )
    }

    @Test
    fun `a URL that names a version is honoured as typed`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            GeminiModels.baseUrl("https://generativelanguage.googleapis.com/v1beta/"),
        )
        assertEquals("https://generativelanguage.googleapis.com/v1", GeminiModels.baseUrl("https://generativelanguage.googleapis.com/v1"))
        assertEquals("https://proxy.example/google/v1alpha", GeminiModels.baseUrl("https://proxy.example/google/v1alpha"))
    }

    @Test
    fun `nothing stored is the provider default`() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta", GeminiModels.baseUrl(null))
        assertEquals("https://generativelanguage.googleapis.com/v1beta", GeminiModels.baseUrl("   "))
    }
}
