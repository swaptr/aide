package com.sabreware.aide.aisdk.providers.alibaba

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Conformance against `packages/alibaba/src/alibaba-embedding-model.test.ts`. */
class AlibabaTest {

    private val values = listOf("sunny day at the beach", "rainy day in the city")

    /** Deliberately out of order: DashScope does not promise the batch comes back as it went out. */
    private val reversedFixture = """
        {
          "output": {
            "embeddings": [
              { "embedding": [0.4, 0.5, 0.6], "text_index": 1 },
              { "embedding": [0.1, 0.2, 0.3], "text_index": 0 }
            ]
          },
          "usage": { "total_tokens": 20 }
        }
    """.trimIndent()

    private fun provider(server: TestServer, extraHeaders: Map<String, String> = emptyMap()) =
        AlibabaProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-api-key",
            extraHeaders = extraHeaders,
        )

    @Test
    fun `embeddings come back in input order and usage is the total token count`() = runTest {
        val server = TestServer(TestServer.json(reversedFixture))

        val result = provider(server).embeddingModel("text-embedding-v4")
            .doEmbed(EmbeddingCallOptions(values))

        assertEquals(listOf(listOf(0.1, 0.2, 0.3), listOf(0.4, 0.5, 0.6)), result.embeddings)
        assertEquals(20, result.usage)
        assertNull(result.providerMetadata)
    }

    @Test
    fun `the request uses DashScope's nested body, not the OpenAI one`() = runTest {
        val server = TestServer(TestServer.json(reversedFixture))

        provider(server).embeddingModel("text-embedding-v4").doEmbed(EmbeddingCallOptions(values))

        val call = server.request()
        // A different service on the same domain from the chat models' `compatible-mode/v1`.
        assertEquals("api/v1/services/embeddings/text-embedding/text-embedding", call.path)
        call.assertBodyKeys("model", "input", "parameters")
        call.assertBodyJson { body ->
            assertEquals("text-embedding-v4", body["model"].string())
            assertEquals(values, body.obj("input")?.arr("texts")?.map { it.string() })
            assertTrue(body.obj("parameters")!!.isEmpty(), "parameters is present but empty")
        }
    }

    @Test
    fun `provider options fill the parameters block and headers merge`() = runTest {
        val server = TestServer(TestServer.json(reversedFixture))

        provider(server, mapOf("Custom-Provider-Header" to "provider-header-value"))
            .embeddingModel("text-embedding-v4")
            .doEmbed(
                EmbeddingCallOptions(
                    values = values,
                    providerOptions = mapOf(
                        ALIBABA_PROVIDER_ID to buildJsonObject {
                            put("textType", "query")
                            put("dimension", 768)
                            put("outputType", "dense&sparse")
                        },
                    ),
                    headers = mapOf("Custom-Request-Header" to "request"),
                ),
            )

        val call = server.request()
        call.assertBodyJson { body ->
            val parameters = body.obj("parameters")!!
            // text_type is Alibaba's spelling of input_type: asymmetric retrieval embeds the two apart.
            assertEquals("query", parameters["text_type"].string())
            assertEquals(768, parameters["dimension"].int())
            assertEquals("dense&sparse", parameters["output_type"].string())
        }
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request")
    }

    @Test
    fun `sparse vectors ride in provider metadata, keyed to their input`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {
                  "output": {
                    "embeddings": [
                      {
                        "embedding": [0.1],
                        "text_index": 0,
                        "sparse_embedding": [{ "index": 100, "value": 0.8, "token": "token-0" }]
                      },
                      {
                        "embedding": [0.2],
                        "text_index": 1,
                        "sparse_embedding": [{ "index": 101, "value": 0.8, "token": "token-1" }]
                      }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = provider(server).embeddingModel("text-embedding-v4").doEmbed(
            EmbeddingCallOptions(
                values = values,
                providerOptions = mapOf(
                    ALIBABA_PROVIDER_ID to buildJsonObject { put("outputType", "dense&sparse") },
                ),
            ),
        )

        val sparse = result.providerMetadata?.get(ALIBABA_PROVIDER_ID)?.arr("sparseEmbeddings")
        assertEquals(listOf(0, 1), sparse?.map { it.jsonObject["textIndex"].int() })
        // `token` is what lets a caller explain WHY a document matched, which dense retrieval cannot.
        val first = sparse?.first()?.jsonObject?.arr("sparseEmbedding")?.first()?.jsonObject
        assertEquals("token-0", first?.get("token").string())
        assertEquals(0.8, first?.get("value").double())
        // The dense half is untouched by the sparse one riding alongside it.
        assertEquals(listOf(listOf(0.1), listOf(0.2)), result.embeddings)
    }

    @Test
    fun `sparse-only output is refused rather than answered with empty vectors`() = runTest {
        val server = TestServer(TestServer.json(reversedFixture))

        assertFailsWith<UnsupportedFunctionalityError> {
            provider(server).embeddingModel("text-embedding-v4").doEmbed(
                EmbeddingCallOptions(
                    values = values,
                    providerOptions = mapOf(
                        ALIBABA_PROVIDER_ID to buildJsonObject { put("outputType", "sparse") },
                    ),
                ),
            )
        }

        // Empty vectors would index cleanly and then never retrieve anything.
        assertEquals(0, server.callCount, "the request must not go out")
    }

    @Test
    fun `a batch past 10 is refused before the request goes out`() = runTest {
        val server = TestServer(TestServer.json(reversedFixture))

        val error = assertFailsWith<TooManyEmbeddingValuesForCallError> {
            provider(server).embeddingModel("text-embedding-v4")
                .doEmbed(EmbeddingCallOptions(List(11) { "value $it" }))
        }

        assertEquals(10, error.maxEmbeddingsPerCall)
        assertEquals(0, server.callCount, "the request must not go out")
    }
}
