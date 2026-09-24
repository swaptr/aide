package com.sabreware.aide.aisdk.providers.voyage

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertCompatibility
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * Conformance against the reference's own recorded Voyage fixtures and its expected request bodies.
 *
 * The vectors and scores below are `packages/voyage/src/__fixtures__` verbatim: they were recorded from
 * the live API, so matching them is the strongest check available without a key.
 */
class VoyageTest {

    private val values = listOf("sunny day at the beach", "rainy day in the city")

    private fun provider(server: TestServer, extraHeaders: Map<String, String> = emptyMap()) =
        VoyageProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-api-key",
            extraHeaders = extraHeaders,
        )

    // --- Embedding ------------------------------------------------------------------------------

    private val embeddingFixture = """
        {
          "object": "list",
          "data": [
            {
              "object": "embedding",
              "embedding": [0.000344163, -0.022529466, 0.010127448, 0.063431956, 0.016145896],
              "index": 0,
              "text": null
            },
            {
              "object": "embedding",
              "embedding": [0.018987041, -0.029901529, -0.005134966, 0.082804598, -0.008740067],
              "index": 1,
              "text": null
            }
          ],
          "model": "voyage-3.5",
          "usage": { "total_tokens": 12 }
        }
    """.trimIndent()

    @Test
    fun `embedding returns the reference's recorded vectors and token count`() = runTest {
        val server = TestServer(TestServer.json(embeddingFixture))

        val result = provider(server).embeddingModel("voyage-3.5")
            .doEmbed(EmbeddingCallOptions(values))

        assertEquals(
            listOf(
                listOf(0.000344163, -0.022529466, 0.010127448, 0.063431956, 0.016145896),
                listOf(0.018987041, -0.029901529, -0.005134966, 0.082804598, -0.008740067),
            ),
            result.embeddings,
        )
        assertEquals(12, result.usage)
        assertEquals("voyage-3.5", result.response?.modelId)
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `embedding reorders the batch by index rather than trusting arrival order`() = runTest {
        val server = TestServer(
            TestServer.json("""{"data":[{"index":1,"embedding":[0.3]},{"index":0,"embedding":[0.1]}]}"""),
        )

        val result = provider(server).embeddingModel("voyage-3.5")
            .doEmbed(EmbeddingCallOptions(values))

        // A vector matched to the wrong input retrieves the wrong document forever, and nothing detects it.
        assertEquals(listOf(listOf(0.1), listOf(0.3)), result.embeddings)
    }

    @Test
    fun `embedding sends only the model and the values when no options are given`() = runTest {
        val server = TestServer(TestServer.json(embeddingFixture))

        provider(server).embeddingModel("voyage-3.5").doEmbed(EmbeddingCallOptions(values))

        val call = server.request()
        assertEquals("v1/embeddings", call.path)
        call.assertBodyKeys("input", "model")
        call.assertBodyJson { body ->
            assertEquals("voyage-3.5", body["model"].string())
            assertEquals(values, body["input"]!!.jsonArray.map { it.string() })
        }
    }

    @Test
    fun `embedding sends the whole option surface from providerOptions`() = runTest {
        val server = TestServer(TestServer.json(embeddingFixture))

        provider(server).embeddingModel("voyage-3.5").doEmbed(
            EmbeddingCallOptions(
                values = values,
                providerOptions = mapOf(
                    VOYAGE_PROVIDER_ID to buildJsonObject {
                        put("inputType", "document")
                        put("truncation", true)
                        put("outputDimension", 256)
                        put("outputDtype", "int8")
                    },
                ),
            ),
        )

        val call = server.request()
        call.assertBodyKeys("input", "model", "input_type", "truncation", "output_dimension", "output_dtype")
        call.assertBodyJson { body ->
            // input_type is the whole reason this provider is not an OpenAI-compatible one.
            assertEquals("document", body["input_type"].string())
            assertEquals(256, body["output_dimension"].int())
            assertEquals("int8", body["output_dtype"].string())
        }
    }

    @Test
    fun `embedding merges provider headers with per-call headers`() = runTest {
        val server = TestServer(TestServer.json(embeddingFixture))

        provider(server, mapOf("Custom-Provider-Header" to "provider-header-value"))
            .embeddingModel("voyage-3.5")
            .doEmbed(EmbeddingCallOptions(values, headers = mapOf("Custom-Request-Header" to "request")))

        val call = server.request()
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        // `options.headers` had zero readers across the provider layer; this is the regression that pins it.
        call.assertHeader("Custom-Request-Header", "request")
    }

    @Test
    fun `embedding refuses a batch past the documented ceiling instead of letting the server 400`() =
        runTest {
            val server = TestServer(TestServer.json(embeddingFixture))
            val tooMany = List(129) { "value $it" }

            val error = assertFailsWith<TooManyEmbeddingValuesForCallError> {
                provider(server).embeddingModel("voyage-3.5").doEmbed(EmbeddingCallOptions(tooMany))
            }

            assertEquals(128, error.maxEmbeddingsPerCall)
            assertEquals(0, server.callCount, "the request must not go out")
        }

    // --- Reranking ------------------------------------------------------------------------------

    private val rerankFixture = """
        {
          "object": "list",
          "data": [
            { "relevance_score": 0.5703125, "index": 1 },
            { "relevance_score": 0.255859375, "index": 0 }
          ],
          "model": "rerank-2.5",
          "usage": { "total_tokens": 12 }
        }
    """.trimIndent()

    @Test
    fun `reranking sends text documents unchanged and returns the recorded ranking`() = runTest {
        val server = TestServer(TestServer.json(rerankFixture))

        val result = provider(server).rerankingModel("rerank-2.5").doRerank(
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Text(values),
                query = "rainy day",
                topN = 2,
            ),
        )

        val call = server.request()
        call.assertBodyKeys("documents", "model", "query", "top_k")
        call.assertBodyJson { body ->
            assertEquals(values, body["documents"]!!.jsonArray.map { it.string() })
            assertEquals("rainy day", body["query"].string())
            // Voyage spells the cut-off top_k; every other reranking wire spells it top_n.
            assertEquals(2, body["top_k"].int())
        }
        assertEquals(listOf(1, 0), result.ranking.map { it.index })
        assertEquals(listOf(0.5703125, 0.255859375), result.ranking.map { it.relevanceScore })
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `reranking stringifies object documents and says so`() = runTest {
        val server = TestServer(TestServer.json(rerankFixture))

        val result = provider(server).rerankingModel("rerank-2.5").doRerank(
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Objects(
                    listOf(
                        buildJsonObject { put("example", "sunny day at the beach") },
                        buildJsonObject { put("example", "rainy day in the city") },
                    ),
                ),
                query = "rainy day",
                topN = 2,
            ),
        )

        server.request().assertBodyJson { body ->
            assertEquals(
                listOf(
                    """{"example":"sunny day at the beach"}""",
                    """{"example":"rainy day in the city"}""",
                ),
                body["documents"]!!.jsonArray.map { it.string() },
            )
        }
        // The caller's field structure is gone by the time the model scores it, which is a Compatibility
        // warning rather than Unsupported: the call still ran.
        result.warnings.assertCompatibility(
            feature = "object documents",
            details = "Object documents are converted to strings.",
        )
    }

    @Test
    fun `reranking sends its own options and merges per-call headers`() = runTest {
        val server = TestServer(TestServer.json(rerankFixture))

        provider(server, mapOf("Custom-Provider-Header" to "provider-header-value"))
            .rerankingModel("rerank-2.5")
            .doRerank(
                RerankingCallOptions(
                    documents = RerankingCallOptions.Documents.Text(values),
                    query = "rainy day",
                    providerOptions = mapOf(
                        VOYAGE_PROVIDER_ID to buildJsonObject {
                            put("returnDocuments", true)
                            put("truncation", false)
                        },
                    ),
                    headers = mapOf("Custom-Request-Header" to "request"),
                ),
            )

        val call = server.request()
        // No topN was given, so no top_k goes out — the absence is the assertion.
        call.assertBodyKeys("documents", "model", "query", "return_documents", "truncation")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request")
    }
}
