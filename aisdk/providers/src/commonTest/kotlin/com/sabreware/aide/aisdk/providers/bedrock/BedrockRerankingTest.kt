package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.AwsCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bedrock reranking over the agent runtime, ported from the reference's
 * `reranking/amazon-bedrock-reranking-model.test.ts`.
 *
 * The reference builds its model with a `us-east-1` base URL and a `us-west-2` region for the ARN — a
 * test artefact, not a wire fact. Ours has one region, so it is `us-west-2` here and the ARN matches
 * the recorded snapshot exactly.
 */
class BedrockRerankingTest {

    private fun model(server: TestServer, modelId: String = "cohere.rerank-v3-5:0") = BedrockRerankingModel(
        modelId = modelId,
        http = server.http().withErrorStructure(BedrockErrors),
        credentials = { AwsCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY") },
        region = "us-west-2",
        now = { 1_705_314_645_000L },
    )

    private fun recorded() = TestServer(TestServer.json(BedrockModalityFixtures.RERANK_1))

    /** The reference's options — `nextToken` and a pass-through model field — under [key]. */
    private fun options(key: String = BEDROCK_PROVIDER_ID) = mapOf(
        key to buildJsonObject {
            put("nextToken", "test-token")
            putJsonObject("additionalModelRequestFields") { put("test", "test-value") }
        },
    )

    private val jsonDocuments = RerankingCallOptions.Documents.Objects(
        listOf(
            buildJsonObject { put("example", "sunny day at the beach") },
            buildJsonObject { put("example", "rainy day in the city") },
        ),
    )

    private val textDocuments = RerankingCallOptions.Documents.Text(
        listOf("sunny day at the beach", "rainy day in the city"),
    )

    @Test
    fun `json documents go out verbatim, in the recorded request shape`() = runTest {
        val server = recorded()

        val result = model(server).doRerank(
            RerankingCallOptions(
                documents = jsonDocuments,
                query = "rainy day",
                topN = 2,
                providerOptions = options(),
            ),
        )

        assertEquals("https://bedrock-agent-runtime.us-west-2.amazonaws.com/rerank", server.request().url)
        server.request().assertBodyEquals(
            """
            {
              "nextToken": "test-token",
              "queries": [
                {
                  "textQuery": {
                    "text": "rainy day"
                  },
                  "type": "TEXT"
                }
              ],
              "rerankingConfiguration": {
                "bedrockRerankingConfiguration": {
                  "modelConfiguration": {
                    "additionalModelRequestFields": {
                      "test": "test-value"
                    },
                    "modelArn": "arn:aws:bedrock:us-west-2::foundation-model/cohere.rerank-v3-5:0"
                  },
                  "numberOfResults": 2
                },
                "type": "BEDROCK_RERANKING_MODEL"
              },
              "sources": [
                {
                  "inlineDocumentSource": {
                    "jsonDocument": {
                      "example": "sunny day at the beach"
                    },
                    "type": "JSON"
                  },
                  "type": "INLINE"
                },
                {
                  "inlineDocumentSource": {
                    "jsonDocument": {
                      "example": "rainy day in the city"
                    },
                    "type": "JSON"
                  },
                  "type": "INLINE"
                }
              ]
            }
            """,
        )
        assertEquals(listOf(0, 5), result.ranking.map { it.index })
        assertEquals(0.5110583305358887, result.ranking[0].relevanceScore)
        assertEquals(0.30241215229034424, result.ranking[1].relevanceScore)
        result.warnings.assertNoWarnings()
        assertNull(result.providerMetadata)
    }

    @Test
    fun `the AWS-required bedrockRerankingConfiguration member is spelled exactly`() = runTest {
        val server = recorded()

        model(server).doRerank(RerankingCallOptions(documents = textDocuments, query = "rainy day"))

        // Renamed, the required member goes out as null and AWS answers 400. Asserted on its own so a
        // future rename fails here, not in the whole-body comparison a snapshot refresh would rewrite.
        server.request().assertBodyJson { body ->
            assertTrue(
                body.obj("rerankingConfiguration", "bedrockRerankingConfiguration") != null,
                body.toString(),
            )
        }
    }

    @Test
    fun `text documents go out as textDocument sources`() = runTest {
        val server = recorded()

        val result = model(server).doRerank(
            RerankingCallOptions(
                documents = textDocuments,
                query = "rainy day",
                topN = 2,
                providerOptions = options(),
            ),
        )

        server.request().assertBodyEquals(
            """
            {
              "nextToken": "test-token",
              "queries": [
                {
                  "textQuery": {
                    "text": "rainy day"
                  },
                  "type": "TEXT"
                }
              ],
              "rerankingConfiguration": {
                "bedrockRerankingConfiguration": {
                  "modelConfiguration": {
                    "additionalModelRequestFields": {
                      "test": "test-value"
                    },
                    "modelArn": "arn:aws:bedrock:us-west-2::foundation-model/cohere.rerank-v3-5:0"
                  },
                  "numberOfResults": 2
                },
                "type": "BEDROCK_RERANKING_MODEL"
              },
              "sources": [
                {
                  "inlineDocumentSource": {
                    "textDocument": {
                      "text": "sunny day at the beach"
                    },
                    "type": "TEXT"
                  },
                  "type": "INLINE"
                },
                {
                  "inlineDocumentSource": {
                    "textDocument": {
                      "text": "rainy day in the city"
                    },
                    "type": "TEXT"
                  },
                  "type": "INLINE"
                }
              ]
            }
            """,
        )
        assertEquals(listOf(0, 5), result.ranking.map { it.index })
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `requests are signed for the bedrock service and carry the caller's headers`() = runTest {
        val server = recorded()

        model(server).doRerank(
            RerankingCallOptions(
                documents = textDocuments,
                query = "rainy day",
                headers = mapOf("config-header" to "config-value"),
            ),
        )

        val request = server.request()
        request.assertHeader("content-type", "application/json")
        request.assertHeader("config-header", "config-value")
        request.assertHeader("x-amz-date", "20240115T103045Z")
        val auth = request.header("Authorization")!!
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 "), auth)
        // The agent runtime is a different host in the SAME signing scope as bedrock-runtime.
        assertTrue(auth.contains("/20240115/us-west-2/bedrock/aws4_request"), auth)
        assertTrue(auth.contains("SignedHeaders=config-header;content-type;host;x-amz-date"), auth)
    }

    @Test
    fun `the response carries headers and the raw body, as the reference's does`() = runTest {
        val result = model(recorded()).doRerank(RerankingCallOptions(documents = textDocuments, query = "q"))

        assertEquals("application/json", result.response?.headers?.get("content-type"))
        assertEquals(TestServer.FIXED_NOW, result.response?.timestamp)
        assertTrue(result.response?.body!!.contains("\"relevanceScore\":0.5110583305358887"), result.response?.body)
        assertTrue(result.request?.body!!.contains("\"modelArn\""), result.request?.body)
    }

    @Test
    fun `the legacy bedrock options key is read when ours is absent`() = runTest {
        val server = recorded()

        model(server).doRerank(
            RerankingCallOptions(documents = textDocuments, query = "q", providerOptions = options(key = "bedrock")),
        )

        server.request().assertBodyJson { body -> assertEquals("test-token", body["nextToken"].string()) }
    }

    @Test
    fun `a paged response surfaces its nextToken as provider metadata`() = runTest {
        val server = TestServer(
            TestServer.json("""{"results":[{"index":1,"relevanceScore":0.9}],"nextToken":"page-2"}"""),
        )

        val result = model(server).doRerank(RerankingCallOptions(documents = textDocuments, query = "q"))

        assertEquals(
            "page-2",
            result.providerMetadata?.get(BEDROCK_PROVIDER_ID)?.get("nextToken")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `an ARN model id is sent as-is, and no topN means no numberOfResults`() = runTest {
        val server = recorded()
        val arn = "arn:aws:bedrock:us-west-2:123456789012:application-inference-profile/abc"

        model(server, modelId = arn).doRerank(RerankingCallOptions(documents = textDocuments, query = "q"))

        server.request().assertBodyJson { body ->
            val config = body.obj("rerankingConfiguration", "bedrockRerankingConfiguration")!!
            assertEquals(arn, config.obj("modelConfiguration")?.get("modelArn").string())
            assertNull(config["numberOfResults"])
            assertNull(body["nextToken"])
        }
    }

    @Test
    fun `a rejected request surfaces the agent runtime's message`() = runTest {
        val server = TestServer(
            TestServer.error(400, """{"message":"Input validation failed"}"""),
        )

        val error = assertFailsWith<APICallError> {
            model(server).doRerank(RerankingCallOptions(documents = textDocuments, query = "q"))
        }

        assertEquals(400, error.statusCode)
        assertTrue(error.message!!.contains("Input validation failed"), error.message)
    }
}
