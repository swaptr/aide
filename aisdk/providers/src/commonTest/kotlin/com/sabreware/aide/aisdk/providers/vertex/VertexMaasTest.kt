package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Vertex MaaS, ported from `google-vertex-maas-provider.test.ts` — every base URL the reference
 * snapshots (global, regional, multi-region, custom, trailing slash, empty string), driven through the
 * wire it mocks away — plus what Google's own MaaS pages add: usage on an unasked-for final chunk, R1's
 * inline `<think>` tags, and `chat_template_kwargs` as the thinking switch.
 */
class VertexMaasTest {

    private fun provider(
        server: TestServer,
        location: String? = null,
        baseUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) = if (location == null) {
        VertexMaasProvider(
            client = HttpClient(server.engine()),
            projectId = "test-project",
            accessToken = { "test-token" },
            baseUrl = baseUrl,
            extraHeaders = headers,
        )
    } else {
        VertexMaasProvider(
            client = HttpClient(server.engine()),
            projectId = "test-project",
            accessToken = { "test-token" },
            location = location,
            baseUrl = baseUrl,
            extraHeaders = headers,
        )
    }

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello")))))

    private fun maasServer() = TestServer(TestServer.sse(VertexOpenApiFixtures.MAAS_STREAM))

    private suspend fun urlFor(location: String? = null, baseUrl: String? = null): String {
        val server = maasServer()
        provider(server, location, baseUrl).languageModel("test-model").doGenerate(call)
        return server.request().url
    }

    // --- the host rule, every case the reference snapshots -----------------------------------------

    @Test
    fun `global is the bare host`() = runTest {
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/test-project/locations/global" +
                "/endpoints/openapi/chat/completions",
            urlFor(location = "global"),
        )
    }

    @Test
    fun `the default location is global`() = runTest {
        // "should default to global location when not specified".
        assertTrue(urlFor().startsWith("https://aiplatform.googleapis.com/v1/projects/test-project/locations/global/"))
    }

    @Test
    fun `a region prefixes the host - the form Google's REST examples use`() = runTest {
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/test-project/locations/us-central1" +
                "/endpoints/openapi/chat/completions",
            urlFor(location = "us-central1"),
        )
    }

    @Test
    fun `a multi-region location is a rep subdomain`() = runTest {
        // Where Grok is global-only, open models are served regionally, so the host has to name the
        // region — and `eu`/`us` are not a prefix but a `.rep.` subdomain (the reference's `getHost`).
        assertEquals(
            "https://aiplatform.eu.rep.googleapis.com/v1/projects/test-project/locations/eu" +
                "/endpoints/openapi/chat/completions",
            urlFor(location = "eu"),
        )
    }

    @Test
    fun `a custom base URL wins, loses its trailing slash, and an empty one falls back`() = runTest {
        assertEquals(
            "https://custom-endpoint.example.com/chat/completions",
            urlFor(baseUrl = "https://custom-endpoint.example.com/"),
        )
        // "should construct correct URL when baseURL is empty string".
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/test-project/locations/us-central1" +
                "/endpoints/openapi/chat/completions",
            urlFor(location = "us-central1", baseUrl = ""),
        )
    }

    // --- auth and headers ---------------------------------------------------------------------------

    @Test
    fun `the bearer and custom headers both reach the wire`() = runTest {
        val server = maasServer()

        provider(server, headers = mapOf("X-Custom" to "header-value")).languageModel("test-model")
            .doGenerate(call)

        // The reference moves custom headers into its auth-adding fetch; the wire is the same.
        assertEquals("Bearer test-token", server.request().header("Authorization"))
        assertEquals("header-value", server.request().header("X-Custom"))
    }

    // --- the body and the stream --------------------------------------------------------------------

    @Test
    fun `no stream_options is sent, and the unprompted final-chunk usage is still read`() = runTest {
        val server = maasServer()

        val parts = provider(server).languageModel("deepseek-ai/deepseek-v3.1-maas")
            .doStream(call).stream.toList()

        val body = server.request().bodyJson()
        assertEquals("deepseek-ai/deepseek-v3.1-maas", body["model"].string())
        assertEquals(true, body["stream"].bool())
        // The reference passes no `includeUsage`; Google's streaming example carries `usage` on the
        // last chunk of a request that asked for none.
        assertFalse("stream_options" in body, "stream_options reached the wire: $body")

        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(14, finish.usage.inputTokens.total)
        assertEquals(131, finish.usage.outputTokens.total)
        val text = parts.filterIsInstance<StreamPart.TextDelta>().joinToString("") { it.delta }
        assertEquals("Hello", text)
    }

    @Test
    fun `Llama 4 is told its 8192 output ceiling - an explicit value and every other model are left alone`() = runTest {
        // "should default Llama 4 max tokens without overriding explicit or other model settings".
        val server = maasServer()

        provider(server).languageModel("meta/llama-4-scout-17b-16e-instruct-maas").doGenerate(call)
        provider(server).languageModel("meta/llama-4-maverick-17b-128e-instruct-maas")
            .doGenerate(call.copy(maxOutputTokens = 64))
        provider(server).languageModel("openai/gpt-oss-20b-maas").doGenerate(call)

        assertEquals(8192, server.request(0).bodyJson()["max_tokens"].int())
        assertEquals(64, server.request(1).bodyJson()["max_tokens"].int())
        assertNull(server.request(2).bodyJson()["max_tokens"])
    }

    @Test
    fun `chat_template_kwargs reaches the body verbatim - Google's thinking switch`() = runTest {
        val server = maasServer()

        provider(server).languageModel("deepseek-ai/deepseek-v3.1-maas").doGenerate(
            call.copy(
                providerOptions = mapOf(
                    VERTEX_MAAS_PROVIDER_ID to buildJsonObject {
                        putJsonObject("chat_template_kwargs") { put("thinking", true) }
                    },
                ),
            ),
        )

        // Google's MaaS thinking guide: `"chat_template_kwargs": {"thinking": true}` turns reasoning on
        // for DeepSeek V3.1+. No knob to add — the compat model spreads the namespace into the body.
        assertEquals(
            true,
            server.request().bodyJson().obj("chat_template_kwargs")?.get("thinking").bool(),
        )
    }

    @Test
    fun `R1's inline think tags become a reasoning part rather than literal text`() = runTest {
        // Google's thinking guide for DeepSeek R1 0528: "reasoning is surrounded by <think></think>
        // tags in the content field. There is no reasoning_content field."
        val server = TestServer(
            TestServer.sse(
                """data: {"id":"r","model":"deepseek-ai/deepseek-r1-0528-maas","choices":[{"index":0,""" +
                    """"delta":{"role":"assistant","content":"<think>Two is the only one.</think>"},""" +
                    """"finish_reason":null}]}""" + "\n\n" +
                    """data: {"id":"r","choices":[{"index":0,"delta":{"content":"Only 2 is an even prime."},""" +
                    """"finish_reason":"stop"}]}""" + "\n\n" +
                    "data: [DONE]\n\n",
            ),
        )

        val result = provider(server).languageModel("deepseek-ai/deepseek-r1-0528-maas").doGenerate(call)

        val reasoning = assertIs<Content.Reasoning>(result.content[0])
        assertEquals("Two is the only one.", reasoning.text)
        val text = assertIs<Content.Text>(result.content[1])
        assertEquals("Only 2 is an even prime.", text.text)
    }

    @Test
    fun `a schema downgrades to json_object with a warning - the reference's default`() = runTest {
        val server = maasServer()

        val result = provider(server).languageModel("test-model").doGenerate(
            call.copy(responseFormat = ResponseFormat.Json(schema = buildJsonObject { put("type", "object") })),
        )

        // The reference leaves `supportsStructuredOutputs` unset for MaaS; Google's REST examples
        // document JSON mode only. A schema is not sent to a server that may reject the shape.
        assertEquals("json_object", server.request().bodyJson().obj("response_format")?.get("type").string())
        result.warnings.assertUnsupported("responseFormat")
    }

    @Test
    fun `the model reports the reference's provider name`() = runTest {
        assertEquals("vertex.maas", provider(maasServer()).languageModel("test-model").provider)
        assertEquals(VERTEX_MAAS_PROVIDER_ID, provider(maasServer()).providerId)
    }

    @Test
    fun `modalities MaaS does not document resolve to null rather than a 404 in waiting`() {
        // The reference's provider type inherits completion, embedding and image accessors from the
        // generic compat interface; Google documents only `chat/completions` on this surface.
        val provider = provider(maasServer())

        assertNull(provider.embeddingModel("multilingual-e5-large-instruct-maas"))
        assertNull(provider.imageModel("any"))
    }
}
