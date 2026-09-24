package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertCompatibility
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * Three rules read off Anthropic's own documentation rather than off the port we ported from.
 *
 * Each is a hard 400 rather than a degradation, which is why they are pinned here: a beta header the
 * vendor no longer recognizes, a thinking configuration that is legal on both halves but not together,
 * and a client toolset whose request and result shapes both differ from an ordinary tool's.
 */
class AnthropicToolsetTest {

    private val stream = TestServer.sse(
        "event: message_delta\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
            "\"usage\":{\"output_tokens\":4}}\n\n",
    )

    private suspend fun send(
        modelId: String,
        options: CallOptions,
    ): Triple<JsonObject, List<Warning>, TestServer> {
        val server = TestServer(stream)
        val model = AnthropicLanguageModel(modelId = modelId, http = server.http())
        val parts = model.doStream(options).stream.toList()
        val warnings = parts.filterIsInstance<StreamPart.StreamStart>().flatMap { it.warnings }
        return Triple(server.request().bodyJson(), warnings, server)
    }

    private fun user(text: String = "hi") = listOf(ModelMessage.User(listOf(UserPart.Text(text))))

    private fun toolset(id: String, args: JsonObject = buildJsonObject { }) =
        Tool.ProviderDefined(name = "ignored", id = id, args = args)

    // --- beta headers ---------------------------------------------------------------------------

    @Test
    fun `the tools the vendor documents as needing no beta send none`() = runTest {
        // Anthropic's tool reference marks the Beta header column "None" for every one of these, and the
        // bash page says so in as many words. An unrecognized beta value is a 400 naming the header, so a
        // stale beta does not degrade — it fails every request that carries the tool.
        // An adaptive model, so the ≤4.5 interleaved-thinking beta is not in play and the header
        // reflects the tools alone.
        val (_, _, server) = send(
            "claude-opus-5",
            CallOptions(
                prompt = user(),
                tools = listOf(
                    toolset("anthropic.bash_20250124"),
                    toolset("anthropic.memory_20250818"),
                    toolset("anthropic.text_editor_20250728"),
                    toolset("anthropic.code_execution_20250825"),
                    toolset("anthropic.web_search_20260209"),
                ),
            ),
        )

        assertNull(server.request().header("anthropic-beta"))
    }

    @Test
    fun `the legacy and gated tools still send the beta they genuinely need`() = runTest {
        val (_, _, server) = send(
            "claude-opus-5",
            CallOptions(prompt = user(), tools = listOf(toolset("anthropic.advisor_20260301"))),
        )

        assertEquals("advisor-tool-2026-03-01", server.request().header("anthropic-beta"))
    }

    // --- Opus 5: disabled thinking and effort ---------------------------------------------------

    @Test
    fun `Opus 5 keeps disabled thinking and drops the effort it cannot be paired with`() = runTest {
        val (body, warnings) = send(
            "claude-opus-5",
            CallOptions(
                prompt = user(),
                reasoning = ReasoningEffort.XHigh,
                providerOptions = mapOf(
                    ANTHROPIC_PROVIDER_ID to buildJsonObject {
                        put("thinking", buildJsonObject { put("type", "disabled") })
                    },
                ),
            ),
        )

        // Both halves are individually legal; together they are a 400 the vendor enforces per request.
        // The effort gives way because it is a dial, and with thinking off there is almost nothing left
        // for `xhigh` to mean — where overriding `disabled` would bill the caller for reasoning they
        // asked not to have.
        assertEquals("disabled", body.obj("thinking")?.get("type").string())
        assertNull(body.obj("output_config")?.get("effort"))
        warnings.assertCompatibility(
            "reasoning",
            "this model accepts thinking \"disabled\" only at effort \"high\" or below, " +
                "so effort \"xhigh\" was dropped rather than failing the request.",
        )
    }

    @Test
    fun `an effort inside the accepted range survives alongside disabled thinking`() = runTest {
        val (body, _) = send(
            "claude-opus-5",
            CallOptions(
                prompt = user(),
                reasoning = ReasoningEffort.Low,
                providerOptions = mapOf(
                    ANTHROPIC_PROVIDER_ID to buildJsonObject {
                        put("thinking", buildJsonObject { put("type", "disabled") })
                    },
                ),
            ),
        )

        assertEquals("disabled", body.obj("thinking")?.get("type").string())
        assertEquals("low", body.obj("output_config")?.get("effort").string())
    }

    @Test
    fun `a model without the restriction keeps both`() = runTest {
        val (body, _) = send(
            "claude-opus-4-8",
            CallOptions(
                prompt = user(),
                reasoning = ReasoningEffort.XHigh,
                providerOptions = mapOf(
                    ANTHROPIC_PROVIDER_ID to buildJsonObject {
                        put("thinking", buildJsonObject { put("type", "disabled") })
                    },
                ),
            ),
        )

        assertEquals("xhigh", body.obj("output_config")?.get("effort").string())
    }

    // --- thinking-mode classification -----------------------------------------------------------

    @Test
    fun `Mythos Preview is adaptive AND extended, where Fable and Mythos 5 are adaptive only`() {
        // The vendor's table lists Mythos Preview as "Adaptive, extended", rejecting only `disabled`.
        // A substring match on "mythos" put it in the adaptive-only family with Mythos 5.
        assertTrue(anthropicModelCapabilities("claude-mythos-preview").supportsExtendedThinking)
        assertTrue(anthropicModelCapabilities("claude-mythos-preview").rejectsDisabledThinking)

        assertTrue(!anthropicModelCapabilities("claude-mythos-5").supportsExtendedThinking)
        assertTrue(anthropicModelCapabilities("claude-mythos-5").rejectsDisabledThinking)
        assertTrue(!anthropicModelCapabilities("claude-fable-5").supportsExtendedThinking)
        assertTrue(anthropicModelCapabilities("claude-fable-5").rejectsDisabledThinking)

        // All three are always-on, so none of them derives a `disabled` type.
        listOf("claude-mythos-preview", "claude-mythos-5", "claude-fable-5").forEach {
            assertEquals(AnthropicThinkingMode.AdaptiveAlwaysOn, anthropicThinkingMode(it), it)
        }
    }

    @Test
    fun `the always-on families are known models, not guesses`() {
        // They used to fall through to the unknown-newer branch, which warns about a limit it invented.
        listOf("claude-mythos-5", "claude-mythos-preview", "claude-fable-5").forEach {
            assertTrue(anthropicModelCapabilities(it).known, it)
        }
    }

    // --- client toolsets: the request half -------------------------------------------------------

    @Test
    fun `a toolset entry carries its type and no name at all`() = runTest {
        val (body, _) = send(
            "claude-opus-5",
            CallOptions(prompt = user(), tools = listOf(toolset("anthropic.computer_toolset_20260801"))),
        )

        val entry = body.arr("tools")?.single()?.jsonObject
        assertEquals("computer_toolset_20260801", entry?.get("type").string())
        // The dated type fixes the member names, so a `name` here is the API's own rejection case — and
        // it is exactly what an ordinary tool entry would have sent.
        assertNull(entry?.get("name"))
    }

    @Test
    fun `member configs keep enabled and defer_loading and drop anything else`() = runTest {
        val (body, warnings) = send(
            "claude-opus-5",
            CallOptions(
                prompt = user(),
                tools = listOf(
                    toolset(
                        "anthropic.browser_toolset_20260801",
                        buildJsonObject {
                            put(
                                "configs",
                                buildJsonObject {
                                    put(
                                        "screenshot",
                                        buildJsonObject {
                                            put("enabled", true)
                                            put("deferLoading", false)
                                            put("displayWidthPx", 1024)
                                        },
                                    )
                                },
                            )
                        },
                    ),
                ),
            ),
        )

        val member = body.arr("tools")?.single()?.jsonObject?.obj("configs")?.obj("screenshot")
        assertEquals("true", member?.get("enabled").string())
        assertEquals("false", member?.get("defer_loading").string())
        // A member accepts only those two; anything else is refused by the API, so it is dropped here
        // with a warning naming the key rather than sent and answered with a 400 naming the request.
        assertNull(member?.get("displayWidthPx"))
        warnings.assertUnsupported("provider-defined tool anthropic.browser_toolset_20260801")
    }

    @Test
    fun `defer_loading on the entry is refused, because it belongs on each member`() = runTest {
        val (body, warnings) = send(
            "claude-opus-5",
            CallOptions(
                prompt = user(),
                tools = listOf(
                    toolset(
                        "anthropic.computer_toolset_20260801",
                        buildJsonObject { put("deferLoading", true) },
                    ),
                ),
            ),
        )

        assertNull(body.arr("tools")?.single()?.jsonObject?.get("defer_loading"))
        warnings.assertUnsupported("provider-defined tool anthropic.computer_toolset_20260801")
    }

    @Test
    fun `allowed_callers keeps direct and refuses a code-execution caller`() = runTest {
        val (body, warnings) = send(
            "claude-opus-5",
            CallOptions(
                prompt = user(),
                tools = listOf(
                    toolset(
                        "anthropic.computer_toolset_20260801",
                        buildJsonObject {
                            put(
                                "allowedCallers",
                                buildJsonArray {
                                    add("direct")
                                    add("code_execution_20260120")
                                },
                            )
                        },
                    ),
                ),
            ),
        )

        // Only `["direct"]` is accepted on a toolset: no programmatic tool calling.
        val callers = body.arr("tools")?.single()?.jsonObject?.arr("allowed_callers")
        assertEquals(1, callers?.size)
        assertEquals("direct", callers?.single().string())
        warnings.assertUnsupported("provider-defined tool anthropic.computer_toolset_20260801")
    }

    @Test
    fun `the second entry of one toolset is dropped rather than failing the whole request`() = runTest {
        val (body, warnings) = send(
            "claude-opus-5",
            CallOptions(
                prompt = user(),
                tools = listOf(
                    toolset("anthropic.computer_toolset_20260801"),
                    toolset("anthropic.computer_toolset_20260801"),
                ),
            ),
        )

        assertEquals(1, body.arr("tools")?.size)
        warnings.assertUnsupported("provider-defined tool anthropic.computer_toolset_20260801")
    }

    @Test
    fun `both toolsets may be declared together`() = runTest {
        val (body, _) = send(
            "claude-opus-5",
            CallOptions(
                prompt = user(),
                tools = listOf(
                    toolset("anthropic.computer_toolset_20260801"),
                    toolset("anthropic.browser_toolset_20260801"),
                ),
            ),
        )

        assertEquals(2, body.arr("tools")?.size)
    }

    @Test
    fun `a configs that disables every member is dropped, since the API rejects it`() = runTest {
        val (body, warnings) = send(
            "claude-opus-5",
            CallOptions(
                prompt = user(),
                tools = listOf(
                    toolset(
                        "anthropic.computer_toolset_20260801",
                        buildJsonObject {
                            put(
                                "configs",
                                buildJsonObject {
                                    put("screenshot", buildJsonObject { put("enabled", false) })
                                },
                            )
                        },
                    ),
                ),
            ),
        )

        // The way to send nothing is to send no entry, so the entry survives without the refused configs.
        assertNull(body.arr("tools")?.single()?.jsonObject?.get("configs"))
        warnings.assertUnsupported("provider-defined tool anthropic.computer_toolset_20260801")
    }

    // --- client toolsets: the response half ------------------------------------------------------

    @Test
    fun `a member call carries the toolset it came from, so dispatch can use the pair`() = runTest {
        val server = TestServer(
            TestServer.sse(
                "event: content_block_start\n" +
                    "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":" +
                    "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"screenshot\"," +
                    "\"toolset_name\":\"computer\",\"input\":{}}}\n\n" +
                    "event: content_block_stop\n" +
                    "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                    "event: message_delta\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}," +
                    "\"usage\":{\"output_tokens\":4}}\n\n",
            ),
        )

        val parts = AnthropicLanguageModel(modelId = "claude-opus-5", http = server.http())
            .doStream(CallOptions(prompt = user())).stream.toList()

        val call = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        assertEquals("screenshot", call.toolName)
        // Both toolsets have a `screenshot`, and a caller's own tool may share the name — so the name
        // alone cannot dispatch, and the toolset rides beside it rather than being folded into it.
        assertEquals(
            "computer",
            call.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get(ANTHROPIC_TOOLSET_KEY).string(),
        )
    }

    @Test
    fun `a member result echoes the toolset back, which the API requires`() = runTest {
        val prompt = user() + listOf(
            ModelMessage.Tool(
                listOf(
                    ToolPart.Result(
                        toolCallId = "toolu_1",
                        toolName = "screenshot",
                        output = ToolOutput.Text("OK"),
                        providerOptions = mapOf(
                            ANTHROPIC_PROVIDER_ID to buildJsonObject {
                                put(ANTHROPIC_TOOLSET_KEY, "computer")
                            },
                        ),
                    ),
                ),
            ),
        )

        val (body, _) = send("claude-opus-5", CallOptions(prompt = prompt))

        // Tool results ride in a USER turn and merge with the text turn before them, so the block is
        // selected by type rather than by being the only one.
        val result = body.arr("messages")?.last()?.jsonObject?.arr("content")
            ?.map { it.jsonObject }?.single { it["type"].string() == "tool_result" }
        assertEquals("tool_result", result?.get("type").string())
        // Omitting this is a rejection, not a degradation — the vendor's own words.
        assertEquals("computer", result?.get("toolset_name").string())
    }

    @Test
    fun `an ordinary tool result still sends no toolset_name`() = runTest {
        val prompt = user() + listOf(
            ModelMessage.Tool(
                listOf(ToolPart.Result("toolu_1", "my_tool", ToolOutput.Text("OK"))),
            ),
        )

        val (body, _) = send("claude-opus-5", CallOptions(prompt = prompt))

        val result = body.arr("messages")?.last()?.jsonObject?.arr("content")
            ?.map { it.jsonObject }?.single { it["type"].string() == "tool_result" }
        assertNull(result?.get("toolset_name"))
    }
}
