package com.sabreware.aide.aisdk.providers.mistral

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/** Conformance against `packages/mistral/src/mistral-embedding-model.test.ts`. */
class MistralTest {

    private val values = listOf("sunny day at the beach", "rainy day in the city")

    private val fixture = """
        {
          "id": "b322cfc2b9d34e2f8e14fc99874faee5",
          "object": "list",
          "data": [
            { "object": "embedding", "embedding": [0.1, 0.2, 0.3, 0.4, 0.5], "index": 0 },
            { "object": "embedding", "embedding": [0.6, 0.7, 0.8, 0.9, 1.0], "index": 1 }
          ],
          "model": "mistral-embed",
          "usage": { "prompt_tokens": 20, "total_tokens": 20 }
        }
    """.trimIndent()

    private fun provider(server: TestServer, extraHeaders: Map<String, String> = emptyMap()) =
        MistralProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-api-key",
            extraHeaders = extraHeaders,
        )

    @Test
    fun `embedding returns the vectors and the prompt token count`() = runTest {
        val server = TestServer(TestServer.json(fixture))

        val result = provider(server).embeddingModel("mistral-embed")
            .doEmbed(EmbeddingCallOptions(values))

        assertEquals(
            listOf(listOf(0.1, 0.2, 0.3, 0.4, 0.5), listOf(0.6, 0.7, 0.8, 0.9, 1.0)),
            result.embeddings,
        )
        // Mistral reports `prompt_tokens`, not the `total_tokens` Voyage and Alibaba use.
        assertEquals(20, result.usage)
        assertEquals("mistral-embed", result.response?.modelId)
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `embedding pins the encoding format rather than trusting an undocumented default`() = runTest {
        val server = TestServer(TestServer.json(fixture))

        provider(server).embeddingModel("mistral-embed").doEmbed(EmbeddingCallOptions(values))

        val call = server.request()
        assertEquals("v1/embeddings", call.path)
        call.assertBodyKeys("model", "input", "encoding_format")
        call.assertBodyJson { body ->
            assertEquals("mistral-embed", body["model"].string())
            assertEquals(values, body["input"]!!.jsonArray.map { it.string() })
            assertEquals("float", body["encoding_format"].string())
        }
    }

    @Test
    fun `embedding sends its option surface and merges per-call headers`() = runTest {
        val server = TestServer(TestServer.json(fixture))

        provider(server, mapOf("Custom-Provider-Header" to "provider-header-value"))
            .embeddingModel("mistral-embed")
            .doEmbed(
                EmbeddingCallOptions(
                    values = values,
                    providerOptions = mapOf(
                        MISTRAL_PROVIDER_ID to buildJsonObject {
                            put("outputDimension", 512)
                            put("outputDtype", "int8")
                            put("metadata", buildJsonObject { put("run", "nightly-index") })
                        },
                    ),
                    headers = mapOf("Custom-Request-Header" to "request"),
                ),
            )

        val call = server.request()
        call.assertBodyKeys("model", "input", "encoding_format", "output_dimension", "output_dtype", "metadata")
        call.assertBodyJson { body -> assertEquals(512, body["output_dimension"].int()) }
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request")
    }

    @Test
    fun `embedding refuses a batch past 32 rather than letting the server 400`() = runTest {
        val server = TestServer(TestServer.json(fixture))
        val model = provider(server).embeddingModel("mistral-embed")

        val error = assertFailsWith<TooManyEmbeddingValuesForCallError> {
            model.doEmbed(EmbeddingCallOptions(List(33) { "value $it" }))
        }

        // Two orders of magnitude below the 2048 an OpenAI-compatible caller would assume.
        assertEquals(32, error.maxEmbeddingsPerCall)
        assertEquals(32, model.maxEmbeddingsPerCall())
        assertEquals(0, server.callCount, "the request must not go out")
    }

    @Test
    fun `embedding declares that concurrent calls are not supported`() = runTest {
        val server = TestServer(TestServer.json(fixture))

        // Fanning a corpus out across coroutines here trades throughput for 429s and their backoff.
        assertFalse(provider(server).embeddingModel("mistral-embed").supportsParallelCalls())
    }

    @Test
    fun `a PDF goes out as document_url, not OpenAI's file shape`() = runTest {
        val server = TestServer(
            TestServer.sse("""data: {"id":"c1","choices":[{"delta":{},"finish_reason":"stop"}]}

"""),
        )
        // Through MistralProvider, which is now the only entry point: the `Vendors.mistral` row this
        // used to reach is deleted.
        val chat = provider(server).languageModel("mistral-large-latest")

        chat.doStream(
            com.sabreware.aide.aisdk.CallOptions(
                prompt = listOf(
                    com.sabreware.aide.aisdk.ModelMessage.User(
                        listOf(
                            com.sabreware.aide.aisdk.UserPart.File(
                                data = com.sabreware.aide.aisdk.FileData.Url("https://docs/x.pdf"),
                                mediaType = "application/pdf",
                            ),
                        ),
                    ),
                ),
            ),
        ).stream.toList()

        val part = server.request().bodyJson()["messages"]!!.jsonArray[0]
            .let { it as kotlinx.serialization.json.JsonObject }["content"]!!.jsonArray[0]
            .let { it as kotlinx.serialization.json.JsonObject }
        // Mistral reads `document_url`; the OpenAI `file` shape is accepted there and then ignored,
        // which presents as a model that cannot see the attachment.
        assertEquals("document_url", part["type"].string())
        assertEquals("https://docs/x.pdf", part["document_url"].string())
    }
}
