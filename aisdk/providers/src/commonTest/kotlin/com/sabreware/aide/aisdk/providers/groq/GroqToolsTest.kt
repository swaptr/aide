package com.sabreware.aide.aisdk.providers.groq

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.openaicompatible.OpenAICompatibleProvider
import com.sabreware.aide.aisdk.providers.openaicompatible.ProviderToolDialect
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Groq's `browser_search`, against `groq-prepare-tools.test.ts`.
 *
 * Chat Completions has no provider-executed tools, so the shared model drops one with a warning. What
 * these pin is that Groq's opt-in reverses that for its own tool and ONLY on the models Groq serves it
 * on — the difference between a search that ran and a request that looked fine and searched nothing.
 */
class GroqToolsTest {

    private val supportedModel = "openai/gpt-oss-120b"

    private fun model(server: TestServer, modelId: String, dialect: ProviderToolDialect) =
        OpenAICompatibleProvider(
            client = HttpClient(server.engine()),
            providerId = "groq",
            baseUrl = "https://api.groq.com/openai/v1",
            apiKey = "test-key",
            providerToolDialect = dialect,
        ).languageModel(modelId)!!

    private fun chatServer() = TestServer(
        TestServer.sse(
            """data: {"id":"c","created":1,"model":"gpt-oss","choices":[{"index":0,""" +
                """"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""" + "\n\n" +
                "data: [DONE]\n\n",
        ),
    )

    private fun options(tools: List<Tool>) = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text("what is new")))),
        tools = tools,
    )

    @Test
    fun `the factory declares the reference's id, name and executor`() {
        val tool = GroqTools.browserSearch()

        assertEquals("groq.browser_search", tool.id)
        assertEquals("browser_search", tool.name)
        // Groq runs it; the runtime holds no browser and must never dispatch it.
        assertTrue(tool.providerExecuted)
        assertEquals(JsonObject(emptyMap()), tool.args)
    }

    @Test
    fun `on a supported model it goes out as a bare type entry`() = runTest {
        val server = chatServer()

        assembleGenerateResult(
            model(server, supportedModel, ProviderToolDialect.Groq)
                .doStream(options(listOf(GroqTools.browserSearch()))).stream,
        )

        // `{"type":"browser_search"}` — no `function` key at all, which is what the vendor accepts.
        val tools = server.request().bodyJson()["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("browser_search", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("function" !in tools[0].jsonObject)
    }

    @Test
    fun `on an unsupported model it is dropped with a warning naming the models that work`() = runTest {
        val server = chatServer()

        val result = assembleGenerateResult(
            model(server, "llama-3.3-70b-versatile", ProviderToolDialect.Groq)
                .doStream(options(listOf(GroqTools.browserSearch()))).stream,
        )

        result.warnings.assertUnsupported("provider-defined tool groq.browser_search")
        val details = result.warnings.filterIsInstance<com.sabreware.aide.aisdk.Warning.Unsupported>()
            .first().details.orEmpty()
        assertTrue("openai/gpt-oss-120b" in details, "expected the supported models named, got: $details")
        assertTrue("llama-3.3-70b-versatile" in details, "expected the current model named")
        assertTrue(server.request().bodyJson()["tools"] == null)
    }

    @Test
    fun `without the dialect it is dropped, as it is on any plain compatible server`() = runTest {
        val server = chatServer()

        val result = assembleGenerateResult(
            model(server, supportedModel, ProviderToolDialect.None)
                .doStream(options(listOf(GroqTools.browserSearch()))).stream,
        )

        result.warnings.assertUnsupported("provider-defined tool groq.browser_search")
        assertTrue(server.request().bodyJson()["tools"] == null)
    }

    @Test
    fun `a function tool still rides beside it`() = runTest {
        val server = chatServer()
        val fn = Tool.Function(
            name = "get_weather",
            inputSchema = buildJsonObject { put("type", "object") },
        )

        assembleGenerateResult(
            model(server, supportedModel, ProviderToolDialect.Groq)
                .doStream(options(listOf(fn, GroqTools.browserSearch()))).stream,
        )

        val tools = server.request().bodyJson()["tools"]!!.jsonArray
        assertEquals(listOf("function", "browser_search"), tools.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }
}
