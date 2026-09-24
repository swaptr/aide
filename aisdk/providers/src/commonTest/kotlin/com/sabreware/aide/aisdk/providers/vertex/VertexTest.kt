package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.google.GOOGLE_PROVIDER_ID
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/**
 * Vertex, where the interesting property is what is NOT here: any signing.
 *
 * Vertex takes an OAuth2 bearer, and obtaining one is a platform concern — ADC, the GCE metadata server,
 * `gcloud auth`, or a signed service-account assertion, each right on a different platform. Taking a token
 * provider is what let the whole "RSA-SHA256 in commonMain" risk evaporate.
 */
class VertexTest {

    private var lastRequest: HttpRequestData? = null
    private var tokenReads = 0

    private fun provider(token: () -> String = { "tok-${++tokenReads}" }) = VertexProvider(
        client = HttpClient(
            MockEngine { request ->
                lastRequest = request
                respond(
                    content = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":" +
                        "[{\"text\":\"hi\"}]},\"finishReason\":\"STOP\"}]}\n\n",
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        ),
        projectId = "my-project",
        location = "us-central1",
        accessToken = { token() },
    )

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))))

    @Test
    fun `the URL carries the project, location and publisher`() = runTest {
        provider().languageModel("gemini-3-pro").doStream(call).stream.let {
            assembleGenerateResult(it)
        }

        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/my-project/locations/us-central1" +
                "/publishers/google/models/gemini-3-pro:streamGenerateContent?alt=sse",
            lastRequest!!.url.toString(),
        )
    }

    @Test
    fun `auth is a bearer token, and no API key is sent`() = runTest {
        assembleGenerateResult(provider().languageModel("gemini-3-pro").doStream(call).stream)

        assertTrue(lastRequest!!.headers[HttpHeaders.Authorization]!!.startsWith("Bearer "))
        // The Gemini API key header would be wrong here and is not sent.
        assertEquals(null, lastRequest!!.headers["x-goog-api-key"])
    }

    @Test
    fun `the token is read per request, not captured once`() = runTest {
        val model = provider().languageModel("gemini-3-pro")

        assembleGenerateResult(model.doStream(call).stream)
        val first = lastRequest!!.headers[HttpHeaders.Authorization]
        assembleGenerateResult(model.doStream(call).stream)
        val second = lastRequest!!.headers[HttpHeaders.Authorization]

        // Vertex tokens expire in about an hour; a captured one works until it rotates and then fails as
        // a 401 that reads like a bad key.
        assertTrue(first != second, "the token was captured rather than re-read: $first")
    }

    @Test
    fun `the shared Gemini mapping still applies`() = runTest {
        val result = assembleGenerateResult(
            provider().languageModel("gemini-3-pro").doStream(call).stream,
        )

        // Vertex is a transport; the model and its mapping are the same ones the direct API uses.
        assertEquals("hi", (result.content.single() as Content.Text).text)
        assertEquals(GOOGLE_PROVIDER_ID, provider().languageModel("gemini-3-pro").provider)
    }

    // --- Claude on Vertex: a different publisher, the Anthropic body ------------------------------

    private fun anthropicProvider(sse: String) = VertexProvider(
        client = HttpClient(
            MockEngine { request ->
                lastRequest = request
                respond(content = sse, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            },
        ),
        projectId = "my-project",
        location = "us-east5",
        accessToken = { "tok" },
    )

    @Test
    fun `Claude routes to the anthropic publisher and streamRawPredict`() = runTest {
        val sse = "event: message_delta\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"

        assembleGenerateResult(
            anthropicProvider(sse).languageModel("claude-opus-4-5").doStream(call).stream,
        )

        assertEquals(
            "https://us-east5-aiplatform.googleapis.com/v1/projects/my-project/locations/us-east5" +
                "/publishers/anthropic/models/claude-opus-4-5:streamRawPredict",
            lastRequest!!.url.toString(),
        )
    }

    @Test
    fun `the Claude body is the hosted shape - no model, vertex version, stream kept`() = runTest {
        val sse = "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"

        assembleGenerateResult(
            anthropicProvider(sse).languageModel("claude-opus-4-5").doStream(call).stream,
        )

        val body = parseJsonObject((lastRequest!!.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        assertEquals(null, body["model"])
        assertEquals("vertex-2023-10-16", body["anthropic_version"]?.jsonPrimitive?.content)
        // streamRawPredict REQUIRES it in the body, where Bedrock's endpoint rejects the same field.
        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a Claude conversation on Vertex still files signatures under anthropic`() {
        // The host changed; the model did not. Filing under a Vertex-specific namespace would silently
        // lose signatures when a chat moved between the direct API and Vertex.
        assertEquals(
            "anthropic",
            anthropicProvider("").languageModel("claude-opus-4-5").provider,
        )
    }
}
