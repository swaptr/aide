package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * The reference's `openai-compatible/src/embedding` cases, translated.
 *
 * The OpenAI-compatible embedding wire is the one every self-hosted and aggregator endpoint claims to
 * speak, so what matters is not that it works against one server but that the request is the shape the
 * reference sends and the response is read the way the reference reads it. Two things here are only
 * checkable by an assertion: that the vectors are ordered by the response's own `index` rather than by
 * arrival — a vector paired to the wrong input retrieves the wrong document forever and nothing
 * detects it — and that the documented per-call ceiling is enforced before the request rather than
 * discovered from a 400 that talks about token counts.
 *
 * These live in their own file because `ModalitiesTest` and the compat provider's `commonMain` belong
 * to another agent's change; nothing here edits either.
 */
class CompatibleEmbeddingConformanceTest {

    private val testValues = listOf("sunny day at the beach", "rainy day in the city")

    private val dummyEmbeddings = listOf(
        listOf(0.1, 0.2, 0.3, 0.4, 0.5),
        listOf(0.6, 0.7, 0.8, 0.9, 1.0),
    )

    /** The reference's own response body for this endpoint, values included. */
    private val responseBody = """
        {
          "object": "list",
          "data": [
            { "object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3, 0.4, 0.5] },
            { "object": "embedding", "index": 1, "embedding": [0.6, 0.7, 0.8, 0.9, 1.0] }
          ],
          "model": "text-embedding-3-large",
          "usage": { "prompt_tokens": 8, "total_tokens": 8 }
        }
    """.trimIndent()

    private fun model(server: TestServer, maxPerCall: Int = 2048) = OpenAICompatibleEmbeddingModel(
        provider = "test-provider",
        modelId = "text-embedding-3-large",
        http = server.http(),
        url = "https://my.api.com/v1/embeddings",
        headers = mapOf("Authorization" to "Bearer test-api-key"),
        maxPerCall = maxPerCall,
    )

    @Test
    fun `the vectors are read out of the data array in the response's own order`() = runTest {
        val server = TestServer(TestServer.json(responseBody))

        val result = model(server).doEmbed(EmbeddingCallOptions(testValues))

        assertEquals(dummyEmbeddings, result.embeddings)
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `a server that answers out of order is reordered by index, not trusted`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"data":[{"index":1,"embedding":[0.6]},{"index":0,"embedding":[0.1]}]}""",
            ),
        )

        val result = model(server).doEmbed(EmbeddingCallOptions(testValues))

        assertEquals(listOf(listOf(0.1), listOf(0.6)), result.embeddings)
    }

    @Test
    fun `usage is the prompt token count the endpoint reported`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"data":[],"usage":{"prompt_tokens":20,"total_tokens":20}}""",
            ),
        )

        assertEquals(20, model(server).doEmbed(EmbeddingCallOptions(testValues)).usage)
    }

    @Test
    fun `the model id and the values are what goes out`() = runTest {
        val server = TestServer(TestServer.json(responseBody))

        model(server).doEmbed(EmbeddingCallOptions(testValues))

        val call = server.request()
        assertEquals("v1/embeddings", call.path)
        call.assertBodyJson { body ->
            assertEquals("text-embedding-3-large", body["model"].string())
            assertEquals(testValues, body["input"]!!.jsonArray.map { it.string() })
        }
    }

    @Test
    fun `provider options ride through to the wire under their own names`() = runTest {
        val server = TestServer(TestServer.json(responseBody))

        model(server).doEmbed(
            EmbeddingCallOptions(
                values = testValues,
                providerOptions = mapOf(
                    "test-provider" to buildJsonObject { put("dimensions", 64) },
                ),
            ),
        )

        server.request().assertBodyJson { body -> assertEquals(64, body["dimensions"].int()) }
    }

    @Test
    fun `a provider option must not overwrite the model or the input`() = runTest {
        val server = TestServer(TestServer.json(responseBody))

        model(server).doEmbed(
            EmbeddingCallOptions(
                values = testValues,
                providerOptions = mapOf(
                    "test-provider" to buildJsonObject {
                        put("model", "something-else")
                        put("input", "not the values")
                    },
                ),
            ),
        )

        // The call's own subject is not a setting; letting an option name win here would send a request
        // for a different model than the one the caller resolved and paid attention to.
        server.request().assertBodyJson { body ->
            assertEquals("text-embedding-3-large", body["model"].string())
            assertEquals(testValues, body["input"]!!.jsonArray.map { it.string() })
        }
    }

    @Test
    fun `provider headers and per-call headers both reach the endpoint`() = runTest {
        val server = TestServer(TestServer.json(responseBody))

        model(server).doEmbed(
            EmbeddingCallOptions(
                values = testValues,
                headers = mapOf("Custom-Request-Header" to "request-header-value"),
            ),
        )

        val call = server.request()
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `a batch past the host's ceiling is refused before the request goes out`() = runTest {
        val server = TestServer(TestServer.json(responseBody))

        val error = assertFailsWith<TooManyEmbeddingValuesForCallError> {
            model(server, maxPerCall = 4).doEmbed(EmbeddingCallOptions(List(5) { "value $it" }))
        }

        assertEquals(4, error.maxEmbeddingsPerCall)
        assertEquals("text-embedding-3-large", error.modelId)
        assertEquals(0, server.callCount, "the request must not go out")
    }

    @Test
    fun `the ceiling the model reports is the one it enforces`() = runTest {
        val server = TestServer(TestServer.json(responseBody))

        // embedMany chunks from this number, which is what makes the error above structurally
        // unreachable through that path; the two must therefore be the same number.
        assertEquals(7, model(server, maxPerCall = 7).maxEmbeddingsPerCall())
    }

    /**
     * DEFECT — see the report. The reference sends `encoding_format: "float"` on every embedding
     * request; we send only the model and the input. OpenAI itself defaults to float, so this is
     * invisible there — but this wire is spoken by every self-hosted and aggregator endpoint, and one
     * that defaults to base64 answers with strings where the parser expects numbers.
     */
    @Test
    fun `the encoding format is stated rather than left to the host's default`() = runTest {
        val server = TestServer(TestServer.json(responseBody))

        model(server).doEmbed(EmbeddingCallOptions(testValues))

        server.request().assertBodyKeys("model", "input", "encoding_format")
        server.request().assertBodyJson { body ->
            assertEquals("float", body["encoding_format"].string())
        }
    }
}
