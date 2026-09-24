package com.sabreware.aide.aisdk.providers.perplexity

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * Conformance against `packages/perplexity/src/perplexity-embedding-model.test.ts`.
 *
 * The reference encodes its expected vectors rather than hard-coding base64, and so does this: the
 * assertion is that a round trip through Perplexity's quantization comes back with the same numbers,
 * which a pasted string would not prove.
 */
class PerplexityTest {

    private val values = listOf("sunny day at the beach", "rainy day in the city")

    /** The reference's `dummyEmbeddings`, chosen to straddle both ends of the signed byte range. */
    private val vectors = listOf(
        listOf(1.0, -2.0, 3.0, 127.0, -128.0),
        listOf(-1.0, 2.0, -3.0, 100.0, -50.0),
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun encode(vector: List<Double>): String =
        Base64.encode(ByteArray(vector.size) { vector[it].toInt().toByte() })

    private fun fixture(cost: String? = null, promptTokens: Int = 8) = """
        {
          "object": "list",
          "data": [
            { "object": "embedding", "index": 0, "embedding": "${encode(vectors[0])}" },
            { "object": "embedding", "index": 1, "embedding": "${encode(vectors[1])}" }
          ],
          "model": "pplx-embed-v1-4b",
          "usage": { "prompt_tokens": $promptTokens, "total_tokens": $promptTokens${cost.orEmpty()} }
        }
    """.trimIndent()

    private fun provider(server: TestServer, extraHeaders: Map<String, String> = emptyMap()) =
        PerplexityProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-api-key",
            extraHeaders = extraHeaders,
        )

    @Test
    fun `base64 int8 embeddings decode to signed values`() = runTest {
        val server = TestServer(TestServer.json(fixture(promptTokens = 20)))

        val result = provider(server).embeddingModel("pplx-embed-v1-4b")
            .doEmbed(EmbeddingCallOptions(values))

        // An OpenAI-compatible reader cannot produce this at all: it expects `embedding` to be an array.
        assertEquals(vectors, result.embeddings)
        assertEquals(20, result.usage)
        assertNull(result.providerMetadata, "no cost block means no metadata")
    }

    @Test
    fun `base64 binary embeddings decode unsigned, because those bytes are packed bits`() = runTest {
        // 0x80 is dimension 128 set, not −128. Reading it signed inverts every high bit in the vector.
        val server = TestServer(
            TestServer.json("""{"data":[{"index":0,"embedding":"${encodeRaw(byteArrayOf(0x01, -0x80, 0x7F))}"}]}"""),
        )

        val result = provider(server).embeddingModel("pplx-embed-v1-4b").doEmbed(
            EmbeddingCallOptions(
                values = listOf("a"),
                providerOptions = mapOf(
                    PERPLEXITY_PROVIDER_ID to buildJsonObject { put("encodingFormat", "base64_binary") },
                ),
            ),
        )

        assertEquals(listOf(listOf(1.0, 128.0, 127.0)), result.embeddings)
    }

    @Test
    fun `the default encoding format is sent explicitly`() = runTest {
        val server = TestServer(TestServer.json(fixture()))

        provider(server).embeddingModel("pplx-embed-v1-4b").doEmbed(EmbeddingCallOptions(values))

        val call = server.request()
        assertEquals("v1/embeddings", call.path)
        call.assertBodyKeys("model", "input", "encoding_format")
        call.assertBodyJson { body ->
            assertEquals("pplx-embed-v1-4b", body["model"].string())
            assertEquals(values, body["input"]!!.jsonArray.map { it.string() })
            // Nothing in the response says which quantization it is, so the request has to decide.
            assertEquals("base64_int8", body["encoding_format"].string())
        }
    }

    @Test
    fun `dimensions and encoding format come from providerOptions, headers merge`() = runTest {
        val server = TestServer(TestServer.json(fixture()))

        provider(server, mapOf("Custom-Provider-Header" to "provider-header-value"))
            .embeddingModel("pplx-embed-v1-4b")
            .doEmbed(
                EmbeddingCallOptions(
                    values = values,
                    providerOptions = mapOf(
                        PERPLEXITY_PROVIDER_ID to buildJsonObject {
                            put("dimensions", 256)
                            put("encodingFormat", "base64_binary")
                        },
                    ),
                    headers = mapOf("Custom-Request-Header" to "request"),
                ),
            )

        val call = server.request()
        call.assertBodyKeys("model", "input", "dimensions", "encoding_format")
        call.assertBodyJson { body ->
            assertEquals(256, body["dimensions"].int())
            assertEquals("base64_binary", body["encoding_format"].string())
        }
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Custom-Provider-Header", "provider-header-value")
        call.assertHeader("Custom-Request-Header", "request")
    }

    @Test
    fun `the cost breakdown is surfaced as provider metadata`() = runTest {
        val cost = ""","cost":{"input_cost":0.0001,"total_cost":0.0001,"currency":"USD"}"""
        val server = TestServer(TestServer.json(fixture(cost = cost)))

        val result = provider(server).embeddingModel("pplx-embed-v1-4b")
            .doEmbed(EmbeddingCallOptions(values))

        val block = result.providerMetadata?.get(PERPLEXITY_PROVIDER_ID)?.obj("cost")
        assertEquals(0.0001, block?.get("inputCost").double())
        assertEquals(0.0001, block?.get("totalCost").double())
        assertEquals("USD", block?.get("currency").string())
    }

    @Test
    fun `a batch past 512 is refused before the request goes out`() = runTest {
        val server = TestServer(TestServer.json(fixture()))
        val model = provider(server).embeddingModel("pplx-embed-v1-4b")

        val error = assertFailsWith<TooManyEmbeddingValuesForCallError> {
            model.doEmbed(EmbeddingCallOptions(List(513) { "value $it" }))
        }

        assertEquals(512, error.maxEmbeddingsPerCall)
        assertEquals(0, server.callCount, "the request must not go out")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeRaw(bytes: ByteArray): String = Base64.encode(bytes)
}
