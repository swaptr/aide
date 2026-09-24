package com.sabreware.aide.aisdk.providers.togetherai

import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Conformance against `packages/togetherai/src/reranking/togetherai-reranking-model.test.ts` and its
 * recorded fixture.
 */
class TogetherAiTest {

    private val values = listOf("sunny day at the beach", "rainy day in the city")

    private val fixture = """
        {
          "id": "oGs6Zt9-62bZhn-99529372487b1b0a",
          "object": "rerank",
          "model": "Salesforce/Llama-Rank-v1",
          "results": [
            { "index": 0, "relevance_score": 0.6475887154399037, "document": {} },
            { "index": 5, "relevance_score": 0.6323295373206566, "document": {} }
          ],
          "usage": { "prompt_tokens": 2966, "completion_tokens": 0, "total_tokens": 2966 }
        }
    """.trimIndent()

    private fun provider(server: TestServer, extraHeaders: Map<String, String> = emptyMap()) =
        TogetherAiProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-api-key",
            extraHeaders = extraHeaders,
        )

    private val rankFields = mapOf(
        TOGETHERAI_PROVIDER_ID to buildJsonObject {
            put("rankFields", buildJsonArray { add(JsonPrimitive("example")) })
        },
    )

    @Test
    fun `object documents go out structured, which is why this wire is worth having`() = runTest {
        val server = TestServer(TestServer.json(fixture))

        val result = provider(server).rerankingModel("Salesforce/Llama-Rank-v1").doRerank(
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Objects(
                    listOf(
                        buildJsonObject { put("example", "sunny day at the beach") },
                        buildJsonObject { put("example", "rainy day in the city") },
                    ),
                ),
                query = "rainy day",
                topN = 2,
                providerOptions = rankFields,
            ),
        )

        val call = server.request()
        assertEquals("v1/rerank", call.path)
        call.assertBodyKeys("model", "documents", "query", "top_n", "rank_fields", "return_documents")
        call.assertBodyJson { body ->
            // No stringification and no warning: Together scores the named fields of the object itself.
            assertEquals(
                listOf("sunny day at the beach", "rainy day in the city"),
                body["documents"]!!.jsonArray.map { it.jsonObject["example"].string() },
            )
            assertEquals(listOf("example"), body["rank_fields"]!!.jsonArray.map { it.string() })
            assertEquals(2, body["top_n"].int())
            assertEquals(false, body["return_documents"].bool())
        }
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `text documents go out as strings on the same endpoint`() = runTest {
        val server = TestServer(TestServer.json(fixture))

        val result = provider(server).rerankingModel("Salesforce/Llama-Rank-v1").doRerank(
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Text(values),
                query = "rainy day",
                topN = 2,
                providerOptions = rankFields,
            ),
        )

        server.request().assertBodyJson { body ->
            assertEquals(values, body["documents"]!!.jsonArray.map { it.string() })
        }
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `the ranking is the recorded one, indices into the submitted documents`() = runTest {
        val server = TestServer(TestServer.json(fixture))

        val result = provider(server).rerankingModel("Salesforce/Llama-Rank-v1").doRerank(
            RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Text(values),
                query = "rainy day",
            ),
        )

        assertEquals(listOf(0, 5), result.ranking.map { it.index })
        assertEquals(
            listOf(0.6475887154399037, 0.6323295373206566),
            result.ranking.map { it.relevanceScore },
        )
        // Together names the call in the body, which is more specific than a header request id.
        assertEquals("oGs6Zt9-62bZhn-99529372487b1b0a", result.response?.id)
        assertEquals("Salesforce/Llama-Rank-v1", result.response?.modelId)
    }

    @Test
    fun `no topN sends no cut-off, and headers merge`() = runTest {
        val server = TestServer(TestServer.json(fixture))

        provider(server, mapOf("Custom-Provider-Header" to "provider-header-value"))
            .rerankingModel("Salesforce/Llama-Rank-v1")
            .doRerank(
                RerankingCallOptions(
                    documents = RerankingCallOptions.Documents.Text(values),
                    query = "rainy day",
                    headers = mapOf("Custom-Request-Header" to "request"),
                ),
            )

        val call = server.request()
        call.assertBodyKeys("model", "documents", "query", "return_documents")
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request")
    }
}
