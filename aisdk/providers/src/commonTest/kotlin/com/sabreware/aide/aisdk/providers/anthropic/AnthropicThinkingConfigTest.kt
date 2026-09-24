package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The per-model thinking rules, checked against Anthropic's own configuration table rather than against
 * what we assumed.
 *
 * | Model            | Types                  | Rejected with 400        |
 * |------------------|------------------------|--------------------------|
 * | Fable 5          | Adaptive only          | `enabled`, `disabled`    |
 * | Mythos 5         | Adaptive only          | `enabled`, `disabled`    |
 * | Mythos Preview   | Adaptive, extended     | `disabled`               |
 * | Opus 5 / Sonnet 5| Adaptive only          | `enabled`                |
 * | Opus 4.7 / 4.8   | Adaptive only          | `enabled`                |
 * | Opus/Sonnet 4.6  | Adaptive, extended     | none (extended deprecated)|
 * | Opus/Sonnet/Haiku 4.5 | Extended only     | `adaptive`               |
 *
 * Every row below is a 400 we would otherwise ship.
 */
class AnthropicThinkingConfigTest {

    private var lastRequest: HttpRequestData? = null

    private fun model(modelId: String): AnthropicLanguageModel {
        val engine = MockEngine { request ->
            lastRequest = request
            respond(
                content = "event: message_delta\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
                    "\"usage\":{\"output_tokens\":120,\"output_tokens_details\":{\"thinking_tokens\":90}}}\n\n",
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        return AnthropicLanguageModel(modelId = modelId, http = ProviderHttp(HttpClient(engine)))
    }

    private fun call(
        effort: ReasoningEffort = ReasoningEffort.Medium,
        tools: List<Tool>? = null,
        maxTokens: Int? = null,
    ) = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))),
        reasoning = effort,
        tools = tools,
        maxOutputTokens = maxTokens,
    )

    private suspend fun sentBody(modelId: String, options: CallOptions): JsonObject {
        model(modelId).doStream(options).stream.toList()
        val text = lastRequest!!.body.let { body ->
            (body as io.ktor.http.content.TextContent).text
        }
        return com.sabreware.aide.aisdk.util.parseJsonObject(text)
    }

    private fun JsonObject.thinkingType(): String? =
        this["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content

    private fun JsonObject.effort(): String? =
        this["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content

    // --- mode selection -------------------------------------------------------------------------

    @Test
    fun `4_5 and earlier get extended thinking, never adaptive`() = runTest {
        // These reject "adaptive" with a 400.
        listOf("claude-opus-4-5", "claude-sonnet-4-5-20250929", "claude-haiku-4-5", "claude-opus-4-1")
            .forEach { id ->
                assertEquals("enabled", sentBody(id, call()).thinkingType(), "wrong mode for $id")
            }
    }

    @Test
    fun `4_6 and later get adaptive, never enabled`() = runTest {
        // 4.7+ reject "enabled" with a 400; on 4.6 it is deprecated, so adaptive is right there too.
        listOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-opus-4-7", "claude-opus-4-8", "claude-opus-5")
            .forEach { id ->
                assertEquals("adaptive", sentBody(id, call()).thinkingType(), "wrong mode for $id")
            }
    }

    @Test
    fun `always-on models never receive disabled`() = runTest {
        // Fable 5 and Mythos 5 reject BOTH "enabled" and "disabled"; the fix is to omit thinking entirely.
        listOf("claude-fable-5", "claude-mythos-5", "claude-mythos-preview").forEach { id ->
            val body = sentBody(id, call(effort = ReasoningEffort.None))
            assertNull(body["thinking"], "$id must not be sent a thinking object when off")
        }
    }

    @Test
    fun `a model that accepts disabled gets it when thinking is off`() = runTest {
        assertEquals("disabled", sentBody("claude-sonnet-5", call(effort = ReasoningEffort.None)).thinkingType())
    }

    // --- effort ---------------------------------------------------------------------------------

    @Test
    fun `opus 4_5 gets effort even though it is extended-only`() = runTest {
        // The bug this caught: effort was gated on "not extended", so Opus 4.5 — the one extended-only
        // model that supports effort — silently lost its only control beyond the budget.
        val body = sentBody("claude-opus-4-5", call(effort = ReasoningEffort.Low))

        assertEquals("enabled", body.thinkingType())
        assertEquals("low", body.effort())
    }

    @Test
    fun `other extended-only models get no effort`() = runTest {
        // Sonnet 4.5 and Haiku 4.5 take depth from budget_tokens alone.
        assertNull(sentBody("claude-sonnet-4-5", call(effort = ReasoningEffort.Low)).effort())
        assertNull(sentBody("claude-haiku-4-5", call(effort = ReasoningEffort.Low)).effort())
    }

    @Test
    fun `high is omitted because it is the API default`() = runTest {
        assertNull(sentBody("claude-opus-5", call(effort = ReasoningEffort.High)).effort())
        assertEquals("xhigh", sentBody("claude-opus-5", call(effort = ReasoningEffort.XHigh)).effort())
    }

    // --- budget ---------------------------------------------------------------------------------

    @Test
    fun `budget_tokens stays at or above the documented minimum and below max_tokens`() = runTest {
        val body = sentBody("claude-sonnet-4-5", call(maxTokens = 2000))

        val budget = body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt()
        val maxTokens = body["max_tokens"]!!.jsonPrimitive.content.toInt()
        assertTrue(budget >= 1024, "budget $budget is below the documented 1024 minimum")
        assertTrue(budget < maxTokens, "budget $budget must be below max_tokens $maxTokens")
    }

    @Test
    fun `max_tokens is raised to leave room for thinking, and the caller is told`() = runTest {
        val parts = model("claude-sonnet-4-5").doStream(call(maxTokens = 100)).stream.toList()

        val warnings = parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
        assertTrue(
            warnings.any { it.toString().contains("maxOutputTokens") },
            "raising max_tokens silently is how a caller ends up debugging a truncated reply: $warnings",
        )
    }

    // --- interleaved thinking -------------------------------------------------------------------

    @Test
    fun `4_5 with tools opts into interleaved thinking`() = runTest {
        val tools = listOf(Tool.Function(name = "t", inputSchema = buildJsonObject { }))
        sentBody("claude-sonnet-4-5", call(tools = tools))

        // Without this a 4.5-era model thinks once at the start and never reasons about a tool result.
        assertEquals(
            ANTHROPIC_INTERLEAVED_THINKING_BETA,
            lastRequest!!.headers["anthropic-beta"],
        )
    }

    @Test
    fun `adaptive models are not sent the beta header`() = runTest {
        val tools = listOf(Tool.Function(name = "t", inputSchema = buildJsonObject { }))
        sentBody("claude-opus-5", call(tools = tools))

        // They interleave automatically; the header would be inert noise in the request.
        assertNull(lastRequest!!.headers["anthropic-beta"])
    }

    @Test
    fun `no tools means no interleaving header even on 4_5`() = runTest {
        sentBody("claude-sonnet-4-5", call())

        assertNull(lastRequest!!.headers["anthropic-beta"])
    }

    // --- usage ----------------------------------------------------------------------------------

    @Test
    fun `thinking tokens are reported separately from text tokens`() = runTest {
        val parts = model("claude-opus-5").doStream(call()).stream.toList()

        val usage = parts.filterIsInstance<StreamPart.Finish>().single().usage
        assertEquals(120, usage.outputTokens.total)
        // output_tokens_details.thinking_tokens arrives only on the final message_delta.
        assertEquals(90, usage.outputTokens.reasoning)
        assertEquals(30, usage.outputTokens.text)
    }

    @Test
    fun `the thinking trace is requested rather than left omitted`() = runTest {
        // display defaults to "omitted" on newer models, which returns signatures with no readable text.
        val display = sentBody("claude-opus-5", call())["thinking"]!!
            .jsonObject["display"]?.jsonPrimitive?.content

        assertNotNull(display)
        assertEquals("summarized", display)
    }
}
