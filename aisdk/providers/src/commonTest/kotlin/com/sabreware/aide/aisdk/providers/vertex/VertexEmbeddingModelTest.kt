package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Vertex embedding wire, pinned against the reference's own recorded fixture — a DIFFERENT wire
 * from the Gemini API's `:batchEmbedContents`, which is the whole reason the model exists twice.
 */
class VertexEmbeddingModelTest {

    private fun provider(server: TestServer) = VertexProvider(
        client = HttpClient(server.engine()),
        projectId = "test-project",
        location = "us-central1",
        accessToken = { "test-token" },
    )

    @Test
    fun `predict carries instances with snake_case task_type and camelCase parameters`() = runTest {
        val server = TestServer(TestServer.json(VertexFixtures.EMBEDDING_PREDICT))

        provider(server).embeddingModel("textembedding-gecko@001").doEmbed(
            EmbeddingCallOptions(
                values = listOf("test text one", "test text two"),
                providerOptions = mapOf(
                    VERTEX_PROVIDER_ID to buildJsonObject {
                        put("outputDimensionality", 768)
                        put("taskType", "SEMANTIC_SIMILARITY")
                        put("title", "test title")
                        put("autoTruncate", false)
                    },
                ),
            ),
        )

        val call = server.request()
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/test-project" +
                "/locations/us-central1/publishers/google/models/textembedding-gecko@001:predict",
            call.url,
        )
        assertEquals("Bearer test-token", call.header("Authorization"))
        // The wire mixes its cases — snake_case inside an instance, camelCase in `parameters` — and
        // that inconsistency is Google's, ported verbatim: normalising either way is a silent no-op.
        call.assertBodyEquals(
            """{"instances":[
                {"content":"test text one","task_type":"SEMANTIC_SIMILARITY","title":"test title"},
                {"content":"test text two","task_type":"SEMANTIC_SIMILARITY","title":"test title"}],
                "parameters":{"outputDimensionality":768,"autoTruncate":false}}""",
        )
    }

    @Test
    fun `vectors come back in submission order and usage is the summed token_count`() = runTest {
        val server = TestServer(TestServer.json(VertexFixtures.EMBEDDING_PREDICT))

        val result = provider(server).embeddingModel("textembedding-gecko@001")
            .doEmbed(EmbeddingCallOptions(values = listOf("test text one", "test text two")))

        assertEquals(2, result.embeddings.size)
        assertEquals(-0.017999587580561638, result.embeddings[0][0])
        assertEquals(-0.06007182598114014, result.embeddings[1][0])
        // 5 + 6 from the fixture's per-prediction statistics — the sum IS the call's usage.
        assertEquals(11, result.usage)
        server.request().assertBodyEquals(
            """{"instances":[{"content":"test text one"},{"content":"test text two"}],"parameters":{}}""",
        )
    }

    @Test
    fun `gemini-embedding-2 goes to embedContent, one value per call`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"embedding":{"values":[0.1,0.2]},"usageMetadata":{"promptTokenCount":4}}""",
            ),
        )

        val model = provider(server).embeddingModel("gemini-embedding-2")
        assertEquals(1, model.maxEmbeddingsPerCall())

        val result = model.doEmbed(
            EmbeddingCallOptions(
                values = listOf("hello"),
                providerOptions = mapOf(
                    VERTEX_PROVIDER_ID to buildJsonObject { put("taskType", "RETRIEVAL_QUERY") },
                ),
            ),
        )

        val call = server.request()
        assertTrue(call.url.endsWith("/models/gemini-embedding-2:embedContent"))
        call.assertBodyEquals(
            """{"content":{"parts":[{"text":"hello"}]},"embedContentConfig":{"taskType":"RETRIEVAL_QUERY"}}""",
        )
        assertEquals(listOf(listOf(0.1, 0.2)), result.embeddings)
        assertEquals(4, result.usage)
    }

    @Test
    fun `a batch over the model's ceiling fails before any request is spent`() = runTest {
        val server = TestServer(TestServer.json("{}"))

        assertFailsWith<TooManyEmbeddingValuesForCallError> {
            provider(server).embeddingModel("gemini-embedding-2")
                .doEmbed(EmbeddingCallOptions(values = listOf("one", "two")))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `the canonical google namespace is read and google-vertex wins field-by-field`() = runTest {
        val server = TestServer(TestServer.json(VertexFixtures.EMBEDDING_PREDICT))

        provider(server).embeddingModel("text-embedding-005").doEmbed(
            EmbeddingCallOptions(
                values = listOf("a", "b"),
                providerOptions = mapOf(
                    "google" to buildJsonObject {
                        put("taskType", "CLASSIFICATION")
                        put("outputDimensionality", 256)
                    },
                    VERTEX_PROVIDER_ID to buildJsonObject { put("taskType", "CLUSTERING") },
                ),
            ),
        )

        server.request().assertBodyEquals(
            """{"instances":[{"content":"a","task_type":"CLUSTERING"},{"content":"b","task_type":"CLUSTERING"}],
                "parameters":{"outputDimensionality":256}}""",
        )
    }
}
