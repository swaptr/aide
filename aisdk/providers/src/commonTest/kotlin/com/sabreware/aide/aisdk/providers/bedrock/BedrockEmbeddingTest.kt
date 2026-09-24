package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.TooManyEmbeddingValuesForCallError
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Bedrock embeddings: one endpoint, three request dialects, four response shapes.
 *
 * The dialect split is the whole point — a Titan body sent to a Cohere model is not a degraded call,
 * it is a schema rejection, and Cohere's missing `input_type` fails the same way.
 */
class BedrockEmbeddingTest {

    private var lastRequest: HttpRequestData? = null

    private fun model(
        body: String,
        modelId: String,
        headers: io.ktor.http.Headers = headersOf(),
    ) = BedrockEmbeddingModel(
        modelId = modelId,
        http = ProviderHttp(
            HttpClient(
                MockEngine { request ->
                    lastRequest = request
                    respond(content = body, headers = headers)
                },
            ),
        ),
        credentials = { AwsCredentials("AKIAIOSFODNN7EXAMPLE", "secret") },
        region = "us-east-1",
        now = { 1_705_314_645_000L },
    )

    private fun sentBody() =
        parseJsonObject((lastRequest!!.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())

    @Test
    fun `titan takes one inputText and reports its token count in the body`() = runTest {
        val result = model(
            body = """{"embedding":[0.1,0.2,0.3],"inputTextTokenCount":8}""",
            modelId = "amazon.titan-embed-text-v2:0",
        ).doEmbed(
            EmbeddingCallOptions(
                values = listOf("hello"),
                providerOptions = mapOf(
                    BEDROCK_PROVIDER_ID to buildJsonObject { put("dimensions", 512) },
                ),
            ),
        )

        assertTrue(lastRequest!!.url.toString().endsWith("/model/amazon.titan-embed-text-v2%3A0/invoke"))
        assertEquals("hello", sentBody()["inputText"]!!.jsonPrimitive.content)
        assertEquals(512, sentBody()["dimensions"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf(0.1, 0.2, 0.3), result.embeddings.single())
        assertEquals(8, result.usage)
    }

    @Test
    fun `titan takes one value per call, said up front rather than as a schema rejection`() = runTest {
        val error = assertFailsWith<TooManyEmbeddingValuesForCallError> {
            model("""{}""", modelId = "amazon.titan-embed-text-v1")
                .doEmbed(EmbeddingCallOptions(values = listOf("a", "b")))
        }
        assertEquals(1, error.maxEmbeddingsPerCall)
    }

    @Test
    fun `cohere always sends input_type and reads its token count off the response header`() = runTest {
        val result = model(
            body = """{"embeddings":[[0.1,0.2],[0.3,0.4]]}""",
            modelId = "cohere.embed-multilingual-v3",
            headers = headersOf("x-amzn-bedrock-input-token-count", "12"),
        ).doEmbed(EmbeddingCallOptions(values = listOf("a", "b")))

        // Required: without it Bedrock tries the other schema branches and rejects the request.
        assertEquals("search_query", sentBody()["input_type"]!!.jsonPrimitive.content)
        assertEquals(2, sentBody()["texts"]!!.jsonArray.size)
        assertEquals(listOf(0.1, 0.2), result.embeddings[0])
        assertEquals(listOf(0.3, 0.4), result.embeddings[1])
        assertEquals(12, result.usage)
    }

    @Test
    fun `cohere v4 nests its vectors under float and the parser follows`() = runTest {
        val result = model(
            body = """{"embeddings":{"float":[[0.5,0.6]]}}""",
            modelId = "us.cohere.embed-v4:0",
        ).doEmbed(
            EmbeddingCallOptions(
                values = listOf("a"),
                providerOptions = mapOf(
                    BEDROCK_PROVIDER_ID to buildJsonObject { put("outputDimension", 1024) },
                ),
            ),
        )

        assertEquals(1024, sentBody()["output_dimension"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf(0.5, 0.6), result.embeddings.single())
    }

    @Test
    fun `nova wraps the value in singleEmbeddingParams and types its response`() = runTest {
        val result = model(
            body = """{"embeddings":[{"embeddingType":"FLOAT","embedding":[0.7,0.8]}],"inputTokenCount":5}""",
            modelId = "us.amazon.nova-embed-text-v1:0",
        ).doEmbed(EmbeddingCallOptions(values = listOf("hello")))

        val params = sentBody()["singleEmbeddingParams"]!!.let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("GENERIC_INDEX", params["embeddingPurpose"]!!.jsonPrimitive.content)
        assertEquals("hello", (params["text"] as kotlinx.serialization.json.JsonObject)["value"]!!.jsonPrimitive.content)
        assertEquals(listOf(0.7, 0.8), result.embeddings.single())
        assertEquals(5, result.usage)
    }

    @Test
    fun `an ARN names no family, so modelFamily in the options picks the dialect`() = runTest {
        model(
            body = """{"embeddings":[[0.1]]}""",
            modelId = "arn:aws:bedrock:us-east-1:123:application-inference-profile/abc",
        ).doEmbed(
            EmbeddingCallOptions(
                values = listOf("a"),
                providerOptions = mapOf(
                    BEDROCK_PROVIDER_ID to buildJsonObject { put("modelFamily", "cohere") },
                ),
            ),
        )

        // Without the override this ARN would default to the Titan dialect and be rejected.
        assertEquals("search_query", sentBody()["input_type"]!!.jsonPrimitive.content)
        assertNull(sentBody()["inputText"])
    }
}
