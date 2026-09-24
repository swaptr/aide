package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The three vendors whose divergence is a pure function over the request body or the usage block.
 *
 * Every expectation here was checked against the VENDOR'S OWN current API reference rather than against
 * the vendored port, and one of them contradicts it — see the Fireworks case.
 */
class VendorHooksTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))
    private val call = CallOptions(prompt = prompt)

    private fun sse(vararg objects: String) = TestServer(
        TestServer.sse(objects.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"),
    )

    private val stop = """{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}]}"""

    // --- Fireworks ------------------------------------------------------------------------------------

    @Test
    fun `Fireworks takes low in place of the one effort level its schema omits`() = runTest {
        val server = sse(stop)

        Vendors.fireworks(HttpClient(server.engine()), "k").languageModel("accounts/fireworks/x")!!
            .doStream(call.copy(reasoning = ReasoningEffort.Minimal)).stream.toList()

        // docs.fireworks.ai lists low/medium/high/xhigh/max/none/adaptive — `minimal` is not among them,
        // and sending it is a rejection rather than a downgrade.
        assertEquals("low", server.request().bodyJson()["reasoning_effort"].string())
    }

    @Test
    fun `Fireworks keeps the levels it does serve, including the one the reference clamps away`() =
        runTest {
            val server = sse(stop)

            Vendors.fireworks(HttpClient(server.engine()), "k").languageModel("accounts/fireworks/x")!!
                .doStream(
                    call.copy(
                        // Fireworks accepts `xhigh` today. The vendored reference clamps it to `high` on
                        // the premise that only three levels exist, which would silently downgrade a
                        // request the caller paid for — so that clamp is deliberately not ported, and a
                        // caller reaches the level through the options door.
                        providerOptions = mapOf("fireworks" to buildJsonObject { put("reasoning_effort", "xhigh") }),
                    ),
                ).stream.toList()

            assertEquals("xhigh", server.request().bodyJson()["reasoning_effort"].string())
        }

    @Test
    fun `an ordinary Fireworks call is left exactly as the engine built it`() = runTest {
        val server = sse(stop)

        Vendors.fireworks(HttpClient(server.engine()), "k").languageModel("accounts/fireworks/x")!!
            .doStream(call.copy(reasoning = ReasoningEffort.High)).stream.toList()

        assertEquals("high", server.request().bodyJson()["reasoning_effort"].string())
    }

    // --- Cerebras -------------------------------------------------------------------------------------

    @Test
    fun `Cerebras gets the only generation ceiling its schema defines`() = runTest {
        val server = sse(stop)

        Vendors.cerebras(HttpClient(server.engine()), "k").languageModel("llama-3.3-70b")!!
            .doStream(call.copy(maxOutputTokens = 128)).stream.toList()

        // inference-docs.cerebras.ai defines `max_completion_tokens` and no `max_tokens` at all — not a
        // deprecated alias, simply absent. Left alone, the caller's ceiling is never applied.
        val body = server.request().bodyJson()
        assertNull(body["max_tokens"])
        assertEquals(128, body["max_completion_tokens"].string()?.toInt())
    }

    @Test
    fun `a replayed Cerebras turn carries its reasoning under the name this API reads`() = runTest {
        val server = sse(stop)
        val replay = listOf(
            ModelMessage.User(listOf(UserPart.Text("q"))),
            ModelMessage.Assistant(
                listOf(
                    // As a real replay arrives: the reasoning channel is echoed only when the vendor
                    // originated it, which the model records under its own namespace.
                    AssistantPart.Reasoning(
                        "thought",
                        providerOptions = mapOf(
                            "cerebras" to buildJsonObject { put(REASONING_CONTENT_KEY, "thought") },
                        ),
                    ),
                    AssistantPart.Text("answer"),
                ),
            ),
            ModelMessage.User(listOf(UserPart.Text("again"))),
        )

        Vendors.cerebras(HttpClient(server.engine()), "k").languageModel("qwen-3-235b")!!
            .doStream(CallOptions(prompt = replay)).stream.toList()

        val assistant = server.request().bodyJson()["messages"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["role"].string() == "assistant" }
        // The field is `reasoning` here; replayed as `reasoning_content` it is ignored and the model
        // re-derives a chain of thought the caller already paid for.
        assertEquals("thought", assistant["reasoning"].string())
        assertNull(assistant["reasoning_content"])
    }

    // --- DeepInfra ------------------------------------------------------------------------------------

    @Test
    fun `DeepInfra counters that cannot be nested are added rather than subtracted`() = runTest {
        val server = sse(
            """{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":10,"completion_tokens":30,""" +
                """"completion_tokens_details":{"reasoning_tokens":70}}}""",
        )

        val finish = Vendors.deepInfra(HttpClient(server.engine()), "k").languageModel("google/gemma-3")!!
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        // Reasoning exceeds completion, so the two are siblings: the shared subtraction would report a
        // 30-token answer as 0 on exactly the turns where the model reasoned most.
        assertEquals(30, finish.usage.outputTokens.text)
        assertEquals(70, finish.usage.outputTokens.reasoning)
        assertEquals(100, finish.usage.outputTokens.total)
    }

    @Test
    fun `consistent DeepInfra counters keep the shared reading untouched`() = runTest {
        val server = sse(
            """{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":10,"completion_tokens":100,""" +
                """"completion_tokens_details":{"reasoning_tokens":40}}}""",
        )

        val finish = Vendors.deepInfra(HttpClient(server.engine()), "k").languageModel("deepseek-ai/x")!!
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        // Nested, as the OpenAI convention has it — the repair must not fire on a well-formed report.
        assertEquals(60, finish.usage.outputTokens.text)
        assertEquals(100, finish.usage.outputTokens.total)
    }
}
