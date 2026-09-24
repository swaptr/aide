package com.sabreware.aide.aisdk.providers.alibaba

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.providers.openaicompatible.reasoningTrace
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Alibaba's chat departures, pinned against Model Studio's own documentation.
 *
 * Every one of these is silent when wrong: a cache write unreported, a marker past the fourth dropped
 * with no word, `top_k` withheld from a model that accepts it, `frequency_penalty` sent to a schema
 * that has no such field.
 */
class AlibabaLanguageModelTest {

    private fun provider(server: TestServer) = AlibabaProvider(
        client = HttpClient(server.engine()),
        apiKey = "k",
    )

    private fun sse(vararg objects: String) = TestServer(
        TestServer.sse(objects.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"),
    )

    private fun done(usage: String? = null) =
        """{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}]""" +
            (usage?.let { ""","usage":$it""" } ?: "") + "}"

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))
    private val call = CallOptions(prompt = prompt)

    @Test
    fun `chat goes to compatible-mode, not the native DashScope root`() = runTest {
        val server = sse(done())

        provider(server).languageModel("qwen-plus").doStream(call).stream.toList()

        // Embeddings and video speak the native API; pointing chat at that root is a 404.
        assertEquals(
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
            server.request().url,
        )
    }

    @Test
    fun `a cache write is reported, and both cache counters come out of the prompt total`() = runTest {
        // Model Studio: "This value is part of usage.prompt_tokens" — so the uncached share is the
        // prompt minus BOTH counters. Subtracting only the read over-reports full-rate tokens by the
        // size of the cache write, which is the number that explains a bill.
        val server = sse(
            done(
                """{"prompt_tokens":1000,"completion_tokens":40,""" +
                    """"prompt_tokens_details":{"cached_tokens":600,"cache_creation_input_tokens":300,""" +
                    """"cache_type":"explicit"}}""",
            ),
        )

        val finish = provider(server).languageModel("qwen-plus").doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Finish>().single()

        assertEquals(1000, finish.usage.inputTokens.total)
        assertEquals(600, finish.usage.inputTokens.cacheRead)
        assertEquals(300, finish.usage.inputTokens.cacheWrite)
        assertEquals(100, finish.usage.inputTokens.noCache)
        // `cache_type` names which caching mode served the request, and the two are priced apart. It is
        // undocumented in the public response schema, so it rides in raw rather than being modelled.
        assertEquals("explicit", finish.usage.raw?.get("prompt_tokens_details")?.jsonObject?.get("cache_type").string())
    }

    @Test
    fun `a per-message cache marker reaches the message it was attached to`() = runTest {
        val server = sse(done())
        val marked = listOf(
            ModelMessage.System("rules"),
            ModelMessage.User(
                listOf(UserPart.Text("hi")),
                providerOptions = mapOf(
                    ALIBABA_PROVIDER_ID to buildJsonObject {
                        put("cacheControl", buildJsonObject { put("type", "ephemeral") })
                    },
                ),
            ),
        )

        provider(server).languageModel("qwen-plus").doStream(CallOptions(prompt = marked)).stream.toList()

        val messages = server.request().bodyJson()["messages"]!!.jsonArray
        assertNull(messages[0].jsonObject["cache_control"])
        // The camelCase spelling is this port's; only the snake_case one is Alibaba's.
        assertEquals("ephemeral", messages[1].jsonObject["cache_control"]?.jsonObject?.get("type").string())
        assertNull(messages[1].jsonObject["cacheControl"])
    }

    @Test
    fun `a fifth cache marker warns, because only the last four take effect`() = runTest {
        val server = sse(done())
        val marker = mapOf(
            ALIBABA_PROVIDER_ID to buildJsonObject {
                put("cache_control", buildJsonObject { put("type", "ephemeral") })
            },
        )
        val overloaded = (1..5).map {
            ModelMessage.User(listOf(UserPart.Text("turn $it")), providerOptions = marker)
        }

        val parts = provider(server).languageModel("qwen-plus")
            .doStream(CallOptions(prompt = overloaded)).stream.toList()

        val warnings = parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
        assertTrue(
            warnings.any { it is Warning.Other && it.message.contains("Only the last 4") },
            "expected the breakpoint warning, got $warnings",
        )
        // All five still go out: Alibaba, not this client, decides which four win.
        assertEquals(5, server.request().bodyJson()["messages"]!!.jsonArray.size)
    }

    @Test
    fun `top_k is sent, because Model Studio documents it as supported`() = runTest {
        val server = sse(done())

        val parts = provider(server).languageModel("qwen-plus")
            .doStream(call.copy(topK = 20)).stream.toList()

        assertEquals(20, server.request().bodyJson()["top_k"].string()?.toInt())
        assertTrue(
            parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
                .none { it is Warning.Unsupported && it.feature == "topK" },
        )
    }

    @Test
    fun `frequency_penalty is withheld and warned, since the schema has no such field`() = runTest {
        val server = sse(done())

        val parts = provider(server).languageModel("qwen-plus")
            .doStream(call.copy(frequencyPenalty = 0.5)).stream.toList()

        assertNull(server.request().bodyJson()["frequency_penalty"])
        assertTrue(
            parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
                .any { it is Warning.Unsupported && it.feature == "frequencyPenalty" },
        )
    }

    @Test
    fun `thinking options translate to the wire's own field names`() = runTest {
        val server = sse(done())

        provider(server).languageModel("qwen-plus").doStream(
            call.copy(
                providerOptions = mapOf(
                    ALIBABA_PROVIDER_ID to buildJsonObject {
                        put("enableThinking", true)
                        put("thinkingBudget", 2048)
                    },
                ),
            ),
        ).stream.toList()

        val body = server.request().bodyJson()
        assertEquals(true, body["enable_thinking"].string()?.toBoolean())
        assertEquals(2048, body["thinking_budget"].string()?.toInt())
        // The camelCase names are this port's spelling; sending both would hand Alibaba two fields.
        assertNull(body["enableThinking"])
        assertNull(body["thinkingBudget"])
    }

    @Test
    fun `a neutral reasoning setting still reaches a vendor that spells it its own way`() = runTest {
        val server = sse(done())

        provider(server).languageModel("qwen-plus")
            .doStream(call.copy(reasoning = ReasoningEffort.None)).stream.toList()

        assertEquals(false, server.request().bodyJson()["enable_thinking"].string()?.toBoolean())
    }

    @Test
    fun `parallel_tool_calls is sent only alongside tools`() = runTest {
        val options = mapOf(
            ALIBABA_PROVIDER_ID to buildJsonObject { put("parallelToolCalls", false) },
        )

        val without = sse(done())
        provider(without).languageModel("qwen-plus")
            .doStream(call.copy(providerOptions = options)).stream.toList()
        assertNull(without.request().bodyJson()["parallel_tool_calls"])

        val with = sse(done())
        provider(with).languageModel("qwen-plus").doStream(
            call.copy(
                providerOptions = options,
                tools = listOf(Tool.Function(name = "t", inputSchema = buildJsonObject { })),
            ),
        ).stream.toList()
        assertEquals(false, with.request().bodyJson()["parallel_tool_calls"].string()?.toBoolean())
    }

    // --- ai@7.0.102 -----------------------------------------------------------------------------

    @Test
    fun `reasoning stays one block across deltas that carry an empty tool_calls array`() = runTest {
        // The reference's fixture (`0096850`); the fix is the compat engine's, this pins it on this wire.
        val server = sse(
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"role":"assistant","content":"","reasoning_content":"Think ","tool_calls":[]},""" +
                """"finish_reason":null}]}""",
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"content":"","reasoning_content":"more...","tool_calls":[]},""" +
                """"finish_reason":null}]}""",
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"content":"Hello","reasoning_content":"","tool_calls":[]},""" +
                """"finish_reason":"stop"}]}""",
        )

        val parts = provider(server).languageModel("qwen-plus").doStream(call).stream.toList()

        assertEquals(
            listOf("start reasoning-0", "delta reasoning-0 Think ", "delta reasoning-0 more...", "end reasoning-0"),
            parts.reasoningTrace(),
        )
    }
}
