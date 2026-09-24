package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertNoWarningAbout
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Grok on Vertex, ported from `google-vertex-xai-provider.test.ts` — with the wire the reference mocks
 * away actually exercised: the URL it snapshots, the bearer its node wrapper adds, the body its
 * `transformRequestBody` rewrites, and the usage its `convertUsage` recomputes all go through
 * [TestServer] here, so an assertion is about what Vertex would receive rather than about which
 * function was handed to a mock.
 */
class VertexXaiTest {

    private var tokenReads = 0

    private fun provider(
        server: TestServer,
        location: String = "global",
        baseUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
        token: () -> String = { "test-token" },
    ) = VertexXaiProvider(
        client = HttpClient(server.engine()),
        projectId = "test-project",
        accessToken = { token() },
        location = location,
        baseUrl = baseUrl,
        extraHeaders = headers,
    )

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Who are you?"))))

    private val call = CallOptions(prompt = prompt)

    private fun grokServer() = TestServer(TestServer.sse(VertexOpenApiFixtures.XAI_STREAM))

    // --- the endpoint -------------------------------------------------------------------------------

    @Test
    fun `the global location builds the documented endpoint with the bearer and stream_options`() = runTest {
        val server = grokServer()

        provider(server).languageModel("xai/grok-4.1-fast-reasoning").doGenerate(call)

        val request = server.request()
        // The reference's inline snapshot for `location: 'global'`, plus the chat path.
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/test-project/locations/global" +
                "/endpoints/openapi/chat/completions",
            request.url,
        )
        assertEquals("Bearer test-token", request.header("Authorization"))
        val body = request.bodyJson()
        assertEquals("xai/grok-4.1-fast-reasoning", body["model"].string())
        assertEquals(true, body["stream"].bool())
        // `includeUsage: true` in the reference: streamed usage is asked for explicitly.
        assertEquals(true, body.obj("stream_options")?.get("include_usage").bool())
        assertEquals("Who are you?", (body.arr("messages")?.first() as? JsonObject)?.get("content").string())
    }

    @Test
    fun `a regional location keeps the bare host - Grok is served from the global endpoint only`() = runTest {
        val server = grokServer()

        provider(server, location = "us-central1").languageModel("xai/grok-4.1-fast-reasoning").doGenerate(call)

        // The reference's snapshot for `location: 'us-central1'`: the location is in the path and the
        // host stays bare, unlike every other Vertex surface here. Grok's model cards list "Supported
        // regions: Global" and nothing else, so a regional host would have nothing to answer with.
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/test-project/locations/us-central1" +
                "/endpoints/openapi/chat/completions",
            server.request().url,
        )
    }

    @Test
    fun `an explicit base URL replaces the constructed one, and a blank one falls back to it`() = runTest {
        val custom = grokServer()
        provider(custom, baseUrl = "https://custom-endpoint.example.com/").languageModel("xai/grok-4.20-reasoning")
            .doGenerate(call)
        assertEquals("https://custom-endpoint.example.com/chat/completions", custom.request().url)

        // "should construct the default base URL when baseURL is an empty string".
        val blank = grokServer()
        provider(blank, baseUrl = "").languageModel("xai/grok-4.20-reasoning").doGenerate(call)
        assertTrue(
            blank.request().url.startsWith("https://aiplatform.googleapis.com/v1/projects/test-project/"),
            blank.request().url,
        )
    }

    @Test
    fun `extra headers ride beside the bearer`() = runTest {
        val server = grokServer()

        provider(server, headers = mapOf("X-Custom" to "header-value"))
            .languageModel("xai/grok-4.1-fast-reasoning").doGenerate(call)

        // The reference's node wrapper merges custom headers with the auth header; same wire here.
        assertEquals("header-value", server.request().header("X-Custom"))
        assertEquals("Bearer test-token", server.request().header("Authorization"))
    }

    // --- auth: the token follows the request ---------------------------------------------------------

    @Test
    fun `the token is read per request, not captured at construction`() = runTest {
        val server = grokServer()
        val model = provider(server, token = { "tok-${++tokenReads}" }).languageModel("xai/grok-4.1-fast-reasoning")

        model.doGenerate(call)
        model.doGenerate(call)

        // Vertex tokens rotate hourly; a compat provider built once with a snapshot would send the
        // first token forever and fail as a 401 that reads like a bad key.
        assertEquals("Bearer tok-1", server.request(0).header("Authorization"))
        assertEquals("Bearer tok-2", server.request(1).header("Authorization"))
    }

    @Test
    fun `the compat provider is rebuilt only when the token changes`() = runTest {
        var builds = 0
        val tokens = ArrayDeque(listOf("a", "a", "b", "b", "a"))
        val compat = VertexBearerCompat(accessToken = { tokens.removeFirst() }) { bearer ->
            builds++
            OpenAICompatibleProvider(
                client = HttpClient(grokServer().engine()),
                providerId = VERTEX_XAI_PROVIDER_ID,
                baseUrl = "https://example.com",
                apiKey = bearer,
            )
        }

        val first = compat.resolve()
        assertTrue(first === compat.resolve(), "same token, same provider")
        val second = compat.resolve()
        assertFalse(first === second, "a new token is a new provider")
        assertTrue(second === compat.resolve())
        // Back to the first token: rebuilt again rather than served from a stale entry — one provider
        // per token, never one that outlives its token.
        assertFalse(second === compat.resolve())
        assertEquals(3, builds)
    }

    // --- reasoning_effort is refused ----------------------------------------------------------------

    @Test
    fun `reasoning_effort is stripped from the body and the caller is told`() = runTest {
        val server = grokServer()

        val result = provider(server).languageModel("xai/grok-4.1-fast-reasoning").doGenerate(
            call.copy(
                reasoning = ReasoningEffort.High,
                // The second spelling: a caller spreading the wire key through their own options.
                providerOptions = mapOf(
                    VERTEX_XAI_PROVIDER_ID to buildJsonObject {
                        put("reasoning_effort", "high")
                        put("user", "vertex-user")
                    },
                ),
            ),
        )

        val body = server.request().bodyJson()
        // "Grok reasoning models don't support reasoning_effort" — Google's reasoning guide; the
        // reference's `transformRequestBody` strips it. Its neighbour survives.
        assertFalse("reasoning_effort" in body, "reasoning_effort reached the wire: $body")
        assertEquals("vertex-user", body["user"].string())
        // Where the reference strips silently, the caller is told — the Perplexity precedent.
        result.warnings.assertUnsupported("reasoning")
    }

    @Test
    fun `the strip is exact - the reference's own transform input`() {
        // `transformRequestBody({ model, reasoning_effort: 'high', messages: [] })` snapshot.
        val transformed = withoutReasoningEffort(
            parseJsonObject("""{"model":"xai/grok-4.1-fast-reasoning","reasoning_effort":"high","messages":[]}"""),
        )

        assertEquals(parseJsonObject("""{"model":"xai/grok-4.1-fast-reasoning","messages":[]}"""), transformed)
    }

    @Test
    fun `an unset effort passes through with no warning`() = runTest {
        val server = grokServer()

        val result = provider(server).languageModel("xai/grok-4.1-fast-reasoning").doGenerate(call)

        result.warnings.assertNoWarningAbout("reasoning")
        assertFalse("reasoning_effort" in server.request().bodyJson())
    }

    // --- usage: reasoning counted apart from completion --------------------------------------------

    @Test
    fun `Grok reasoning tokens are counted separately from completion tokens`() {
        // The reference's inline snapshot for `convertUsage`, number for number.
        val usage = vertexXaiUsage(parseJsonObject(VertexOpenApiFixtures.XAI_USAGE))

        assertEquals(
            Usage(
                inputTokens = Usage.InputTokens(total = 663, noCache = 9, cacheRead = 654, cacheWrite = null),
                // 50 + 124: disjoint counters, so the total is their sum and the text share is the
                // completion count itself — NOT 50 total with a text share clamped to zero.
                outputTokens = Usage.OutputTokens(total = 174, text = 50, reasoning = 124),
                raw = parseJsonObject(VertexOpenApiFixtures.XAI_USAGE),
            ),
            usage,
        )
    }

    @Test
    fun `the converter is wired - the streamed usage block arrives through it`() = runTest {
        val parts = provider(grokServer()).languageModel("xai/grok-4.1-fast-reasoning")
            .doStream(call).stream.toList()

        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(174, finish.usage.outputTokens.total)
        assertEquals(50, finish.usage.outputTokens.text)
        assertEquals(124, finish.usage.outputTokens.reasoning)
        assertEquals(663, finish.usage.inputTokens.total)
        assertEquals(654, finish.usage.inputTokens.cacheRead)
        assertEquals(9, finish.usage.inputTokens.noCache)
        // The vendor's extra counters survive under `raw`, where a caller who wants them can look.
        assertEquals("0", finish.usage.raw?.get("cost_in_usd_ticks").string())
    }

    @Test
    fun `absent detail blocks count as zero in the arithmetic and stay unreported`() {
        val usage = vertexXaiUsage(parseJsonObject("""{"prompt_tokens":10,"completion_tokens":4}"""))

        assertEquals(10, usage.inputTokens.noCache)
        assertEquals(4, usage.outputTokens.total)
        // Never-reported is not reported-as-zero: the reference coerces both to 0, the house does not.
        assertNull(usage.inputTokens.cacheRead)
        assertNull(usage.outputTokens.reasoning)
    }

    // --- the rest of the reference's configuration -------------------------------------------------

    @Test
    fun `the answer maps through the shared Chat Completions reader`() = runTest {
        val result = provider(grokServer()).languageModel("xai/grok-4.1-fast-reasoning").doGenerate(call)

        assertEquals("I am Grok, an AI assistant built by xAI...", (result.content.single() as Content.Text).text)
        assertEquals("knTMaJC0EJfM5OMP7I3xkAk", result.response?.metadata?.id)
        assertEquals("xai/grok-4.1-fast-reasoning", result.response?.metadata?.modelId)
        assertEquals(VERTEX_XAI_PROVIDER_ID, provider(grokServer()).languageModel("x").provider)
    }

    @Test
    fun `structured outputs go out as json_schema`() = runTest {
        val server = grokServer()

        val result = provider(server).languageModel("xai/grok-4.1-fast-reasoning").doGenerate(
            call.copy(
                responseFormat = ResponseFormat.Json(
                    schema = buildJsonObject { put("type", "object") },
                    name = "answer",
                ),
            ),
        )

        // `supportsStructuredOutputs: true` in the reference; without it the schema would be
        // downgraded to `json_object` with a warning.
        val format = server.request().bodyJson().obj("response_format")
        assertEquals("json_schema", format?.get("type").string())
        assertEquals("answer", format?.obj("json_schema")?.get("name").string())
        result.warnings.assertNoWarningAbout("responseFormat")
    }

    @Test
    fun `HTTP image URLs are declared fetchable, and only those`() = runTest {
        val urls = provider(grokServer()).languageModel("xai/grok-4.1-fast-reasoning").supportedUrls()

        // The reference's `supportedUrls`: one pattern, `image/*` only.
        assertEquals(setOf("image/*"), urls.keys)
        assertTrue(urls.getValue("image/*").single().matches("https://example.com/a.png"))
        assertTrue(urls.getValue("image/*").single().matches("http://example.com/a.png"))
        assertFalse(urls.getValue("image/*").single().matches("gs://bucket/a.png"))
    }

    @Test
    fun `modalities Grok does not serve resolve to null rather than throwing`() {
        // The reference throws NoSuchModelError from `embeddingModel` and `imageModel`; the house
        // contract says a provider with no models of a kind returns null (DESIGN.md).
        val provider = provider(grokServer())

        assertNull(provider.embeddingModel("any"))
        assertNull(provider.imageModel("any"))
        assertNull(provider.speechModel("any"))
    }
}
