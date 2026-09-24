package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Gemini embeddings, ported from `google-embedding-model.test.ts`.
 *
 * The endpoint split is the load-bearing behaviour: one value must go to `:embedContent` and several to
 * `:batchEmbedContents`, each with its own body envelope — the single endpoint rejects the batch
 * envelope, so getting this wrong is a 400 on every one-value call.
 */
class GoogleEmbeddingModelTest {

    private val values = listOf("sunny day at the beach", "rainy day in the city")

    private val batchResponse =
        """{"embeddings":[{"values":[0.1,0.2,0.3,0.4,0.5]},{"values":[0.6,0.7,0.8,0.9,1.0]}]}"""

    private val singleResponse = """{"embedding":{"values":[0.1,0.2,0.3,0.4,0.5]}}"""

    private fun model(server: TestServer) = GoogleEmbeddingModel(
        modelId = "gemini-embedding-001",
        http = server.http(),
        headers = { mapOf("x-goog-api-key" to "test-api-key") },
    )

    @Test
    fun `several values go to the batch endpoint, in submission order`() = runTest {
        val server = TestServer(TestServer.json(batchResponse))

        val result = model(server).doEmbed(EmbeddingCallOptions(values = values))

        assertEquals(
            listOf(listOf(0.1, 0.2, 0.3, 0.4, 0.5), listOf(0.6, 0.7, 0.8, 0.9, 1.0)),
            result.embeddings,
        )
        val call = server.request()
        assertEquals("v1beta/models/gemini-embedding-001:batchEmbedContents", call.path)
        // Each request wraps the model name AGAIN and carries a role; the single endpoint has neither.
        val first = call.bodyJson().arr("requests")!!.first().jsonObject
        assertEquals("models/gemini-embedding-001", first["model"].string())
        assertEquals("user", first.obj("content")?.get("role").string())
        assertEquals(
            "sunny day at the beach",
            first.obj("content")?.get("parts")?.jsonArray?.first()?.jsonObject?.get("text").string(),
        )
    }

    @Test
    fun `one value goes to the single endpoint, without the batch envelope`() = runTest {
        val server = TestServer(TestServer.json(singleResponse))

        val result = model(server).doEmbed(EmbeddingCallOptions(values = listOf(values[0])))

        assertEquals(listOf(listOf(0.1, 0.2, 0.3, 0.4, 0.5)), result.embeddings)
        val call = server.request()
        assertEquals("v1beta/models/gemini-embedding-001:embedContent", call.path)
        call.assertBodyKeys("model", "content")
        // No role on the single endpoint — the reference sends none and Google documents none.
        assertEquals(null, call.bodyJson().obj("content")?.get("role"))
    }

    @Test
    fun `taskType and outputDimensionality ride every request of a batch`() = runTest {
        val server = TestServer(TestServer.json(batchResponse))

        model(server).doEmbed(
            EmbeddingCallOptions(
                values = values,
                providerOptions = mapOf(
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        put("taskType", "RETRIEVAL_QUERY")
                        put("outputDimensionality", 64)
                    },
                ),
            ),
        )

        server.request().bodyJson().arr("requests")!!.forEach { request ->
            assertEquals("RETRIEVAL_QUERY", request.jsonObject["taskType"].string())
            assertEquals("64", request.jsonObject["outputDimensionality"].string())
        }
    }

    @Test
    fun `headers merge provider, then call`() = runTest {
        val server = TestServer(TestServer.json(batchResponse))

        model(server).doEmbed(
            EmbeddingCallOptions(values = values, headers = mapOf("Custom-Request-Header" to "v")),
        )

        val call = server.request()
        call.assertHeader("x-goog-api-key", "test-api-key")
        call.assertHeader("Custom-Request-Header", "v")
    }

    @Test
    fun `the 101st value fails before any request, naming the ceiling`() = runTest {
        val server = TestServer(TestServer.json(batchResponse))

        val failure = assertFailsWith<TooManyEmbeddingValuesForCallError> {
            model(server).doEmbed(EmbeddingCallOptions(values = List(101) { "test" }))
        }

        assertEquals(100, failure.maxEmbeddingsPerCall)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `multimodal content merges after the text of its value`() = runTest {
        val server = TestServer(TestServer.json(singleResponse))

        model(server).doEmbed(
            EmbeddingCallOptions(
                values = listOf("describe this"),
                providerOptions = mapOf(
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        putJsonArray("content") {
                            add(
                                kotlinx.serialization.json.buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "inlineData",
                                                buildJsonObject {
                                                    put("mimeType", "image/png")
                                                    put("data", "base64-image")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    },
                ),
            ),
        )

        val parts = server.request().bodyJson().obj("content")?.get("parts")?.jsonArray!!
        assertEquals(2, parts.size)
        assertEquals("describe this", parts[0].jsonObject["text"].string())
        // The multimodal entry goes through VERBATIM — its shape is Google's to define, and
        // re-validating it here would reject a part shape Google adds tomorrow.
        assertEquals("image/png", parts[1].jsonObject.obj("inlineData")?.get("mimeType").string())
    }

    @Test
    fun `a null multimodal entry stays text-only in a batch`() = runTest {
        val server = TestServer(TestServer.json(batchResponse))

        model(server).doEmbed(
            EmbeddingCallOptions(
                values = values,
                providerOptions = mapOf(
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        putJsonArray("content") {
                            add(kotlinx.serialization.json.JsonNull)
                            add(
                                kotlinx.serialization.json.buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "fileData",
                                                buildJsonObject {
                                                    put("fileUri", "gs://bucket/doc.pdf")
                                                    put("mimeType", "application/pdf")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    },
                ),
            ),
        )

        val requests = server.request().bodyJson().arr("requests")!!
        val firstParts = requests[0].jsonObject.obj("content")?.get("parts")?.jsonArray!!
        assertEquals(1, firstParts.size)
        val secondParts = requests[1].jsonObject.obj("content")?.get("parts")?.jsonArray!!
        assertEquals(2, secondParts.size)
        assertEquals(
            "gs://bucket/doc.pdf",
            secondParts[1].jsonObject.obj("fileData")?.get("fileUri").string(),
        )
    }

    @Test
    fun `a content list of the wrong length fails before any request`() = runTest {
        val server = TestServer(TestServer.json(batchResponse))

        assertFailsWith<InvalidArgumentError> {
            model(server).doEmbed(
                EmbeddingCallOptions(
                    values = values,
                    providerOptions = mapOf(
                        GOOGLE_PROVIDER_ID to buildJsonObject {
                            putJsonArray("content") { add(kotlinx.serialization.json.JsonNull) }
                        },
                    ),
                ),
            )
        }
        assertEquals(0, server.callCount)
    }
}
