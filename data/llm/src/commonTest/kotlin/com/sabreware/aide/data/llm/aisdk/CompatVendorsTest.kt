package com.sabreware.aide.data.llm.aisdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * One stored config, many vendor rows: the base URL decides which `:aisdk` row backs the OpenAI provider
 * id. What is pinned is the routing table and the one consequence that motivated it — a row's modality
 * set, so a self-hosted server never advertises an image endpoint it does not have.
 */
class CompatVendorsTest {

    private val client = HttpClient(MockEngine { respond("{}") })

    private fun providerId(url: String, key: String? = "k") = CompatVendors.resolve(client, url, key).providerId

    @Test
    fun `known hosts resolve to their vendor row`() {
        assertEquals("openai", providerId("https://api.openai.com/v1"))
        assertEquals("openrouter", providerId("https://openrouter.ai/api/v1"))
        assertEquals("groq", providerId("https://api.groq.com/openai/v1/"))
        assertEquals("togetherai", providerId("https://api.together.xyz/v1"))
        assertEquals("fireworks", providerId("https://api.fireworks.ai/inference/v1"))
        assertEquals("deepinfra", providerId("https://api.deepinfra.com/v1/openai"))
        assertEquals("cerebras", providerId("https://api.cerebras.ai/v1"))
        assertEquals("perplexity", providerId("https://api.perplexity.ai"))
        assertEquals("xai", providerId("https://api.x.ai/v1"))
        assertEquals("huggingface", providerId("https://router.huggingface.co/v1"))
        assertEquals("gmicloud", providerId("https://api.gmi-serving.com/v1"))
        assertEquals("baseten", providerId("https://inference.baseten.co/v1"))
        assertEquals("gateway", providerId("https://ai-gateway.vercel.sh/v1"))
    }

    @Test
    fun `both Ollama forms are Ollama, and LM Studio is matched on its port`() {
        assertEquals("ollama", providerId("https://ollama.com/v1"))
        assertEquals("ollama", providerId("http://localhost:11434/v1", key = null))
        assertEquals("ollama", providerId("http://192.168.1.20:11434/v1", key = null))
        assertEquals("lmstudio", providerId("http://localhost:1234/v1", key = null))
    }

    @Test
    fun `an unknown host is the generic row under the OpenAI id, which is today's behaviour`() {
        assertEquals("openai", providerId("http://myserver.local:8000/v1", key = null))
        assertEquals("openai", providerId("https://llm.example.com/v1"))
    }

    @Test
    fun `a known host without a key keeps its own row, so the vendor's 401 is the answer`() {
        // The row decides which endpoints exist and that does not depend on the credential: a keyless
        // OpenAI URL must still say "yes, images", and the failure the user then sees is OpenAI's own
        // "you didn't provide an API key" rather than a fabricated "serves no image models".
        assertEquals("groq", providerId("https://api.groq.com/openai/v1", key = null))
        val keyless = CompatVendors.resolve(client, "https://api.openai.com/v1", null)
        assertEquals("openai", keyless.providerId)
        assertNotNull(keyless.imageModel("gpt-image-1"))
    }

    @Test
    fun `the modality set follows the row, so an Ollama URL no longer claims image or speech`() {
        val openAi = CompatVendors.resolve(client, "https://api.openai.com/v1", "k")
        assertNotNull(openAi.imageModel("gpt-image-1"))
        assertNotNull(openAi.speechModel("gpt-4o-mini-tts"))
        assertNotNull(openAi.transcriptionModel("whisper-1"))

        val ollama = CompatVendors.resolve(client, "http://localhost:11434/v1", null)
        assertNotNull(ollama.languageModel("llama3"))
        assertNotNull(ollama.embeddingModel("nomic-embed-text"))
        assertNull(ollama.imageModel("anything"))
        assertNull(ollama.speechModel("anything"))
        assertNull(ollama.transcriptionModel("anything"))

        val groq = CompatVendors.resolve(client, "https://api.groq.com/openai/v1", "k")
        assertNull(groq.imageModel("anything"))
    }

    @Test
    fun `whitespace and a trailing slash are tolerated`() {
        assertEquals("openai", providerId("  https://api.openai.com/v1/  "))
        assertEquals("groq", providerId("https://API.GROQ.COM/openai/v1"))
    }
}
