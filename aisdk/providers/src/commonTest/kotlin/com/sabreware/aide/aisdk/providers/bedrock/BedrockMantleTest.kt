package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.AwsCredentials
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Bedrock Mantle, ported from the reference's `mantle/bedrock-mantle-provider.test.ts` — which mocks
 * both model classes and asserts on what they were constructed WITH. The URL, header and auth facts it
 * pins are pinned here on the wire instead, through [TestServer], because the wire is what a
 * constructor-argument assertion cannot see.
 */
class BedrockMantleTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello"))))

    private val credentials = AwsCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")

    private fun chatServer() = TestServer(TestServer.sse(BedrockModalityFixtures.MANTLE_CHAT_SSE))

    private fun responsesServer() = TestServer(TestServer.json(BedrockModalityFixtures.MANTLE_RESPONSES_JSON))

    private fun withKey(
        server: TestServer,
        region: String = "us-east-1",
        baseUrl: String = bedrockMantleBaseUrl(region),
        headers: Map<String, String> = emptyMap(),
    ) = BedrockMantleProvider(
        client = HttpClient(server.engine()),
        region = region,
        auth = BedrockMantleAuth.ApiKey("test-api-key"),
        baseUrl = baseUrl,
        extraHeaders = headers,
    )

    private fun withSigV4(server: TestServer) = BedrockMantleProvider(
        client = HttpClient(server.engine()),
        region = "us-east-1",
        auth = BedrockMantleAuth.SigV4(credentials = { credentials }, now = { 1_705_314_645_000L }),
    )

    @Test
    fun `chat completions go to the regional endpoint with the key as a bearer token`() = runTest {
        val server = chatServer()

        val result = withKey(server).languageModel("openai.gpt-oss-20b").doGenerate(CallOptions(prompt = prompt))

        val request = server.request()
        assertEquals("https://bedrock-mantle.us-east-1.api.aws/v1/chat/completions", request.url)
        request.assertHeader("Authorization", "Bearer test-api-key")
        request.assertNoHeader("x-amz-date")
        request.assertBodyJson { body ->
            assertEquals("openai.gpt-oss-20b", body["model"].string())
            assertEquals(true, body["stream"].bool())
        }
        assertTrue(request.bodyText.contains("\"Hello\""), request.bodyText)
        assertEquals("Hello from Mantle.", (result.content.single() as Content.Text).text)
        assertEquals(BEDROCK_MANTLE_PROVIDER_ID, withKey(server).languageModel("openai.gpt-oss-20b").provider)
    }

    @Test
    fun `a custom base URL replaces the regional one`() = runTest {
        val server = chatServer()

        withKey(server, baseUrl = "https://custom-mantle.example.com/v1")
            .languageModel("test-model")
            .doGenerate(CallOptions(prompt = prompt))

        assertEquals("https://custom-mantle.example.com/v1/chat/completions", server.request().url)
    }

    @Test
    fun `the responses model posts to v1 responses and reports itself as the responses wire`() = runTest {
        val server = responsesServer()

        val model = withKey(server, region = "us-west-2").responsesLanguageModel("openai.gpt-oss-20b")
        val result = model.doGenerate(CallOptions(prompt = prompt))

        assertEquals("bedrock-mantle.responses", model.provider)
        assertEquals("https://bedrock-mantle.us-west-2.api.aws/v1/responses", server.request().url)
        server.request().assertHeader("Authorization", "Bearer test-api-key")
        server.request().assertBodyJson { body -> assertEquals("openai.gpt-oss-20b", body["model"].string()) }
        assertEquals("Hello from the Responses API.", (result.content.single() as Content.Text).text)
    }

    @Test
    fun `aws credentials sign every request in the bedrock-mantle scope`() = runTest {
        val chat = chatServer()
        withSigV4(chat).languageModel("openai.gpt-oss-20b").doGenerate(CallOptions(prompt = prompt))

        val request = chat.request()
        // One Authorization header, and it is the signature — no bearer token beside it.
        val auth = request.header("Authorization")!!
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 "), auth)
        // Mantle's OWN service scope — a signature in `bedrock`'s scope is a 403 here.
        assertTrue(auth.contains("/20240115/us-east-1/bedrock-mantle/aws4_request"), auth)
        assertTrue(auth.contains("SignedHeaders=host;x-amz-date"), auth)
        assertEquals("20240115T103045Z", request.header("x-amz-date"))

        // The Responses model rides the same signing client.
        val responses = responsesServer()
        withSigV4(responses).responsesLanguageModel("openai.gpt-oss-20b").doGenerate(CallOptions(prompt = prompt))
        assertTrue(responses.request().header("Authorization")!!.contains("/bedrock-mantle/aws4_request"))
    }

    @Test
    fun `extra headers reach both wires`() = runTest {
        val chat = chatServer()
        val provider = withKey(chat, headers = mapOf("Custom-Header" to "custom-value"))
        provider.languageModel("openai.gpt-oss-20b").doGenerate(CallOptions(prompt = prompt))
        chat.request().assertHeader("Custom-Header", "custom-value")

        val responses = responsesServer()
        withKey(responses, headers = mapOf("Custom-Header" to "custom-value"))
            .responsesLanguageModel("openai.gpt-oss-20b")
            .doGenerate(CallOptions(prompt = prompt))
        responses.request().assertHeader("Custom-Header", "custom-value")
        responses.request().assertHeader("Authorization", "Bearer test-api-key")
    }

    @Test
    fun `mantle serves language models only, and says so with null`() {
        val provider = withKey(chatServer())

        assertEquals(BEDROCK_MANTLE_PROVIDER_ID, provider.providerId)
        assertNull(provider.embeddingModel("invalid-model-id"))
        assertNull(provider.imageModel("invalid-model-id"))
    }

    @Test
    fun `a blank API key is refused at construction rather than silently falling back`() {
        assertFailsWith<IllegalArgumentException> { BedrockMantleAuth.ApiKey("   ") }
    }
}
