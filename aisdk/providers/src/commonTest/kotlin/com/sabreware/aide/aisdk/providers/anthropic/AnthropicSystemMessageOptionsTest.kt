package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * Mid-conversation system messages and the two per-message controls the reference added on top of
 * them (`4d25a08`): `clearAt` and a per-turn `effort`, each with its own beta.
 *
 * Bodies are lifted from `anthropic-language-model.test.ts` ("should send clearAt and per-turn effort
 * with their beta headers") and `convert-to-anthropic-prompt.test.ts` ("should serialize clearAt and
 * effort on individual mid-conversation system messages"). The tool-change case pins the mechanism
 * the controls ride on, which this port gained in the same change.
 */
class AnthropicSystemMessageOptionsTest {

    private val stream = TestServer.sse(
        "event: message_delta\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
            "\"usage\":{\"output_tokens\":4}}\n\n",
    )

    private class Sent(val body: JsonObject, val betas: Set<String>, val warnings: List<Warning>) {
        val messages: JsonArray get() = body["messages"]!!.jsonArray
    }

    private suspend fun send(options: CallOptions): Sent {
        val server = TestServer(stream)
        val parts = AnthropicLanguageModel(modelId = "claude-fable-5", http = server.http()).doStream(options).stream.toList()
        val call = server.request()
        val betas = call.header("anthropic-beta").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        return Sent(call.bodyJson(), betas, parts.filterIsInstance<StreamPart.StreamStart>().flatMap { it.warnings })
    }

    private fun user(text: String) = ModelMessage.User(listOf(UserPart.Text(text)))
    private fun assistant(text: String) = ModelMessage.Assistant(listOf(AssistantPart.Text(text)))
    private fun system(text: String, options: JsonObject? = null) = ModelMessage.System(
        content = text,
        providerOptions = options?.let { mapOf(ANTHROPIC_PROVIDER_ID to it) },
    )

    @Test
    fun `clearAt and a per-turn effort go out on a mid-conversation system message with their betas`() = runTest {
        val sent = send(
            CallOptions(
                prompt = listOf(
                    user("Draft an answer."),
                    assistant("Draft."),
                    system("", buildJsonObject { put("clearAt", "next_user_message"); put("effort", "xhigh") }),
                    user("Now finalize it."),
                ),
            ),
        )

        assertTrue(
            parseJsonObject(
                """{"role":"system","content":[],"clear_at":"next_user_message","output_config":{"effort":"xhigh"}}""",
            ) in sent.messages,
            sent.messages.toString(),
        )
        assertTrue("mid-conversation-system-clear-at-2026-08-21" in sent.betas, sent.betas.toString())
        assertTrue("mid-conversation-effort-2026-08-01" in sent.betas, sent.betas.toString())
        assertTrue("mid-conversation-system-2026-04-07" in sent.betas, sent.betas.toString())
    }

    @Test
    fun `each mid-conversation system message keeps its own controls`() = runTest {
        val sent = send(
            CallOptions(
                prompt = listOf(
                    system("initial"),
                    user("hi"),
                    assistant("hello"),
                    system("", buildJsonObject { put("clearAt", "next_user_message"); put("effort", "high") }),
                    system("this instruction persists"),
                    user("go"),
                ),
            ),
        )

        assertEquals(JsonPrimitive("initial"), sent.body["system"])
        assertTrue(
            parseJsonObject(
                """{"role":"system","content":[],"clear_at":"next_user_message","output_config":{"effort":"high"}}""",
            ) in sent.messages,
            sent.messages.toString(),
        )
        assertTrue(
            parseJsonObject("""{"role":"system","content":[{"type":"text","text":"this instruction persists"}]}""") in sent.messages,
            sent.messages.toString(),
        )
        assertTrue("mid-conversation-system-clear-at-2026-08-21" in sent.betas, sent.betas.toString())
        assertTrue("mid-conversation-effort-2026-08-01" in sent.betas, sent.betas.toString())
    }

    @Test
    fun `controls on the initial system prompt are dropped with a warning`() = runTest {
        val sent = send(
            CallOptions(
                prompt = listOf(
                    system("sys", buildJsonObject { put("clearAt", "next_user_message"); put("effort", "low") }),
                    user("hi"),
                ),
            ),
        )

        // Hoisted as plain text; the API rejects both controls on the initial prompt.
        assertEquals(JsonPrimitive("sys"), sent.body["system"])
        assertTrue(sent.messages.none { (it as JsonObject)["role"] == JsonPrimitive("system") }, sent.messages.toString())
        assertTrue(sent.betas.none { it.startsWith("mid-conversation") }, sent.betas.toString())
        assertTrue(
            sent.warnings.any { it is Warning.Other && "clearAt and effort on the initial system message" in it.message },
            sent.warnings.toString(),
        )
    }

    @Test
    fun `a plain later system message with no initial prompt is still hoisted`() = runTest {
        // The hoisting this converter always did for plain text is preserved: nothing has claimed the
        // top-level slot, and the message carries nothing that is only valid inline.
        val sent = send(CallOptions(prompt = listOf(user("hi"), assistant("hello"), system("late"), user("go"))))

        assertEquals(JsonPrimitive("late"), sent.body["system"])
        assertTrue(sent.messages.none { (it as JsonObject)["role"] == JsonPrimitive("system") }, sent.messages.toString())
    }

    @Test
    fun `tool changes on a mid-conversation system message become tool_reference blocks`() = runTest {
        val sent = send(
            CallOptions(
                prompt = listOf(
                    user("hi"),
                    assistant("hello"),
                    system(
                        "",
                        buildJsonObject {
                            put(
                                "toolChanges",
                                buildJsonArray {
                                    add(buildJsonObject { put("type", "tool_addition"); put("toolName", "lookup") })
                                },
                            )
                        },
                    ),
                    user("go"),
                ),
                tools = listOf(Tool.Function(name = "lookup", inputSchema = buildJsonObject { put("type", "object") })),
            ),
        )

        assertTrue(
            parseJsonObject(
                """{"role":"system","content":[{"type":"tool_addition","tool":{"type":"tool_reference","name":"lookup"}}]}""",
            ) in sent.messages,
            sent.messages.toString(),
        )
        assertTrue("mid-conversation-tool-changes-2026-07-01" in sent.betas, sent.betas.toString())
        assertTrue("mid-conversation-system-2026-04-07" in sent.betas, sent.betas.toString())
    }
}
