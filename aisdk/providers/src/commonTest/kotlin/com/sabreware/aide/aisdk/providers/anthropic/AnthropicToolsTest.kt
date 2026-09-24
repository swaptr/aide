package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertNoWarningAbout
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * The `AnthropicTools` factories, pinned against `anthropic-tools.ts`, the `tool/` directory and the
 * inline snapshots in `anthropic-prepare-tools.test.ts`.
 *
 * Two things are being guarded. First, that a tool built through a factory produces exactly the request
 * body the reference's snapshots record — the factories replaced a private id table, and the whole
 * point of deriving the table from them is that nothing on the wire moved. Second, the properties a
 * wrong value makes silent: an id the vendor does not recognise, a wire name a result comes back under,
 * and the executed flag, which decides whether the runtime dispatches a call Anthropic is already
 * running.
 *
 * The beta header is asserted as THIS port sends it, not as the reference does. Three fixtures differ:
 * the reference attaches `web-fetch-2025-09-10` to `web_fetch_20250910` and
 * `code-execution-web-tools-2026-02-09` to the 2026-02-09 web tools, and Anthropic's tool reference
 * marks all three "None" — see `AnthropicToolsetTest` for the rule and the evidence.
 */
class AnthropicToolsTest {

    private val stream = TestServer.sse(
        "event: message_delta\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
            "\"usage\":{\"output_tokens\":4}}\n\n",
    )

    private class Sent(val body: JsonObject, val beta: String?, val warnings: List<Warning>) {
        val tool: JsonObject get() = body.arr("tools")!!.single().jsonObject
    }

    /** An adaptive model, so no thinking beta joins the header and it reflects the tools alone. */
    private suspend fun send(vararg tools: Tool): Sent {
        val server = TestServer(stream)
        val model = AnthropicLanguageModel(modelId = "claude-opus-5", http = server.http())
        val parts = model.doStream(
            CallOptions(
                prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))),
                tools = tools.toList(),
            ),
        ).stream.toList()
        val warnings = parts.filterIsInstance<StreamPart.StreamStart>().flatMap { it.warnings }
        val call = server.request()
        return Sent(call.bodyJson(), call.header("anthropic-beta"), warnings)
    }

    private fun assertTool(expected: String, sent: Sent) {
        assertEquals(parseJsonObject(expected), sent.tool, "the tools entry")
    }

    private fun display(width: Int, height: Int, number: Int, enableZoom: Boolean? = null) = buildJsonObject {
        put("displayWidthPx", width)
        put("displayHeightPx", height)
        put("displayNumber", number)
        enableZoom?.let { put("enableZoom", it) }
    }

    // --- the surface ----------------------------------------------------------------------------

    @Test
    fun `every tool the reference exports has a factory under the reference's id`() {
        val reference = listOf(
            "anthropic.advisor_20260301",
            "anthropic.bash_20241022",
            "anthropic.bash_20250124",
            "anthropic.code_execution_20250522",
            "anthropic.code_execution_20250825",
            "anthropic.code_execution_20260120",
            "anthropic.computer_20241022",
            "anthropic.computer_20250124",
            "anthropic.computer_20251124",
            "anthropic.memory_20250818",
            "anthropic.text_editor_20241022",
            "anthropic.text_editor_20250124",
            "anthropic.text_editor_20250429",
            "anthropic.text_editor_20250728",
            "anthropic.tool_search_bm25_20251119",
            "anthropic.tool_search_regex_20251119",
            "anthropic.web_fetch_20260209",
            "anthropic.web_fetch_20250910",
            "anthropic.web_search_20260209",
            "anthropic.web_search_20250305",
        )
        val ids = AnthropicTools.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size, "ids must be unique: $ids")
        reference.forEach { assertTrue(it in ids, "no factory for $it") }
        // The seven read off the vendor's documentation rather than the reference.
        assertEquals(
            setOf(
                "anthropic.code_execution_20260521",
                "anthropic.web_fetch_20260309",
                "anthropic.web_fetch_20260318",
                "anthropic.web_search_20260318",
                "anthropic.computer_toolset_20260801",
                "anthropic.browser_toolset_20260801",
                "anthropic.mcp_toolset",
            ),
            ids.toSet() - reference.toSet(),
        )
    }

    @Test
    fun `client tools are the runtime's to run, server tools are Anthropic's and may answer late`() {
        val clientSide = listOf(
            AnthropicTools.bash_20241022, AnthropicTools.bash_20250124,
            AnthropicTools.computer_20241022, AnthropicTools.computer_20250124, AnthropicTools.computer_20251124,
            AnthropicTools.memory_20250818,
            AnthropicTools.textEditor_20241022, AnthropicTools.textEditor_20250124,
            AnthropicTools.textEditor_20250429, AnthropicTools.textEditor_20250728,
            AnthropicTools.computerToolset_20260801, AnthropicTools.browserToolset_20260801,
        ).map { it.id }.toSet()

        AnthropicTools.all.forEach { factory ->
            val tool = factory()
            if (factory.id in clientSide) {
                assertFalse(tool.providerExecuted, "${factory.id} is a client tool")
                assertFalse(tool.supportsDeferredResults, "${factory.id} answers when the client does")
            } else {
                assertTrue(tool.providerExecuted, "${factory.id} runs on Anthropic's side")
                // Every server tool is held back when the model also called a client tool in the same
                // group — the vendor's mixing rule — except the Python-only sandbox, which the reference
                // leaves undeclared.
                assertEquals(
                    factory.id != "anthropic.code_execution_20250522",
                    tool.supportsDeferredResults,
                    "${factory.id} deferred",
                )
            }
        }
    }

    @Test
    fun `each id and wire name matches the vendor`() {
        assertEquals(
            mapOf(
                "anthropic.advisor_20260301" to "advisor",
                "anthropic.bash_20241022" to "bash",
                "anthropic.bash_20250124" to "bash",
                "anthropic.code_execution_20250522" to "code_execution",
                "anthropic.code_execution_20250825" to "code_execution",
                "anthropic.code_execution_20260120" to "code_execution",
                "anthropic.code_execution_20260521" to "code_execution",
                "anthropic.computer_20241022" to "computer",
                "anthropic.computer_20250124" to "computer",
                "anthropic.computer_20251124" to "computer",
                "anthropic.memory_20250818" to "memory",
                "anthropic.text_editor_20241022" to "str_replace_editor",
                "anthropic.text_editor_20250124" to "str_replace_editor",
                "anthropic.text_editor_20250429" to "str_replace_based_edit_tool",
                "anthropic.text_editor_20250728" to "str_replace_based_edit_tool",
                // The one family whose id and wire type differ: the name is the type minus its date.
                "anthropic.tool_search_regex_20251119" to "tool_search_tool_regex",
                "anthropic.tool_search_bm25_20251119" to "tool_search_tool_bm25",
                "anthropic.web_fetch_20250910" to "web_fetch",
                "anthropic.web_fetch_20260209" to "web_fetch",
                "anthropic.web_fetch_20260309" to "web_fetch",
                "anthropic.web_fetch_20260318" to "web_fetch",
                "anthropic.web_search_20250305" to "web_search",
                "anthropic.web_search_20260209" to "web_search",
                "anthropic.web_search_20260318" to "web_search",
                // A toolset's "name" is what its members report as toolset_name.
                "anthropic.computer_toolset_20260801" to "computer",
                "anthropic.browser_toolset_20260801" to "browser",
                "anthropic.mcp_toolset" to "mcp_toolset",
            ),
            anthropicProviderToolNames,
        )
    }

    @Test
    fun `a renamed tool keeps its id, and goes out under the vendor's name`() = runTest {
        val renamed = AnthropicTools.webSearch_20250305(name = "search_the_web")
        assertEquals("search_the_web", renamed.name)
        assertEquals("anthropic.web_search_20250305", renamed.id)

        val sent = send(renamed)

        // The response half translates back through ToolNameMapping; a call naming `search_the_web`
        // would reach a client that never heard of it.
        assertEquals("web_search", sent.tool["name"].string())
    }

    // --- the reference's snapshots, byte for byte -----------------------------------------------

    @Test
    fun `computer_20241022 carries the display as the vendor spells it`() = runTest {
        val sent = send(AnthropicTools.computer_20241022(display(800, 600, 1)))

        assertTool(AnthropicToolsFixtures.COMPUTER_20241022, sent)
        assertEquals("computer-use-2024-10-22", sent.beta)
    }

    @Test
    fun `computer_20250124 carries the display as the vendor spells it`() = runTest {
        val sent = send(AnthropicTools.computer_20250124(display(1024, 768, 1)))

        assertTool(AnthropicToolsFixtures.COMPUTER_20250124, sent)
        assertEquals("computer-use-2025-01-24", sent.beta)
    }

    @Test
    fun `computer_20251124 sends enable_zoom only when the caller said, true or false`() = runTest {
        val plain = send(AnthropicTools.computer_20251124(display(1024, 768, 1)))
        assertTool(AnthropicToolsFixtures.COMPUTER_20251124, plain)
        assertEquals("computer-use-2025-11-24", plain.beta)

        assertTool(
            AnthropicToolsFixtures.COMPUTER_20251124_ZOOM,
            send(AnthropicTools.computer_20251124(display(1024, 768, 1, enableZoom = true))),
        )
        // `false` is meaningful on the wire — the default is off, but stating it is not the same as
        // omitting it — so it travels rather than being dropped as falsy.
        assertTool(
            AnthropicToolsFixtures.COMPUTER_20251124_NO_ZOOM,
            send(AnthropicTools.computer_20251124(display(1024, 768, 1, enableZoom = false))),
        )
    }

    @Test
    fun `the legacy text editor and bash are a type and the vendor's fixed name, under their beta`() = runTest {
        val editor = send(AnthropicTools.textEditor_20241022(name = "text_editor"))
        assertTool(AnthropicToolsFixtures.TEXT_EDITOR_20241022, editor)
        assertEquals("computer-use-2024-10-22", editor.beta)

        val bash = send(AnthropicTools.bash_20241022())
        assertTool(AnthropicToolsFixtures.BASH_20241022, bash)
        assertEquals("computer-use-2024-10-22", bash.beta)
    }

    @Test
    fun `text_editor_20250728 forwards max_characters when given and nothing when not`() = runTest {
        val capped = send(AnthropicTools.textEditor_20250728(buildJsonObject { put("maxCharacters", 10_000) }))
        assertTool(AnthropicToolsFixtures.TEXT_EDITOR_20250728_MAX, capped)
        assertNull(capped.beta)

        assertTool(AnthropicToolsFixtures.TEXT_EDITOR_20250728, send(AnthropicTools.textEditor_20250728()))
    }

    private val webSearchArgs = buildJsonObject {
        put("maxUses", 10)
        put("allowedDomains", buildJsonArray { add("https://www.google.com") })
        put(
            "userLocation",
            buildJsonObject {
                put("type", "approximate")
                put("city", "New York")
            },
        )
    }

    @Test
    fun `web_search_20250305 spells its arguments the vendor's way`() = runTest {
        val sent = send(AnthropicTools.webSearch_20250305(webSearchArgs))

        assertTool(AnthropicToolsFixtures.WEB_SEARCH_20250305, sent)
        assertNull(sent.beta)
    }

    @Test
    fun `web_search_20260209 is the same body under the newer type, and no beta`() = runTest {
        val sent = send(AnthropicTools.webSearch_20260209(webSearchArgs))

        assertTool(AnthropicToolsFixtures.WEB_SEARCH_20260209, sent)
        // The reference adds code-execution-web-tools-2026-02-09; Anthropic's tool reference marks the
        // tool "None", and an unrecognized beta is a 400 on every request carrying it.
        assertNull(sent.beta)
    }

    private val webFetchArgs = buildJsonObject {
        put("maxUses", 10)
        put("allowedDomains", buildJsonArray { add("https://www.google.com") })
        put("citations", buildJsonObject { put("enabled", true) })
        put("maxContentTokens", 1000)
    }

    @Test
    fun `web_fetch_20250910 spells its arguments the vendor's way`() = runTest {
        val sent = send(AnthropicTools.webFetch_20250910(webFetchArgs))

        assertTool(AnthropicToolsFixtures.WEB_FETCH_20250910, sent)
        // The reference adds web-fetch-2025-09-10; the vendor documents none.
        assertNull(sent.beta)
    }

    @Test
    fun `web_fetch_20260209 is the same body under the newer type, and no beta`() = runTest {
        val sent = send(AnthropicTools.webFetch_20260209(webFetchArgs))

        assertTool(AnthropicToolsFixtures.WEB_FETCH_20260209, sent)
        assertNull(sent.beta)
    }

    @Test
    fun `the tool-search pair go out under the type the vendor dates, not the id`() = runTest {
        val regex = send(AnthropicTools.toolSearchRegex_20251119(name = "tool_search"))
        assertTool(AnthropicToolsFixtures.TOOL_SEARCH_REGEX, regex)
        assertNull(regex.beta)

        val bm25 = send(AnthropicTools.toolSearchBm25_20251119(name = "tool_search"))
        assertTool(AnthropicToolsFixtures.TOOL_SEARCH_BM25, bm25)
        assertNull(bm25.beta)
    }

    @Test
    fun `code_execution_20260120 needs no beta`() = runTest {
        val sent = send(AnthropicTools.codeExecution_20260120())

        assertTool(AnthropicToolsFixtures.CODE_EXECUTION_20260120, sent)
        assertNull(sent.beta)
    }

    @Test
    fun `advisor sends the model it must have, and the optional arguments only when given`() = runTest {
        val required = send(AnthropicTools.advisor_20260301(buildJsonObject { put("model", "claude-opus-4-7") }))
        assertTool(AnthropicToolsFixtures.ADVISOR_REQUIRED, required)
        assertEquals("advisor-tool-2026-03-01", required.beta)

        val everything = send(
            AnthropicTools.advisor_20260301(
                buildJsonObject {
                    put("model", "claude-opus-4-7")
                    put("maxUses", 5)
                    put("maxTokens", 2048)
                    put(
                        "caching",
                        buildJsonObject {
                            put("type", "ephemeral")
                            put("ttl", "1h")
                        },
                    )
                },
            ),
        )
        assertTool(AnthropicToolsFixtures.ADVISOR_ALL, everything)
    }

    // --- the derived table ----------------------------------------------------------------------

    @Test
    fun `a factory-built tool is byte-identical to the hand-built id it replaces`() = runTest {
        val args = buildJsonObject { put("maxUses", 3) }

        val viaFactory = send(AnthropicTools.webSearch_20250305(args))
        val byHand = send(
            Tool.ProviderDefined(name = "search_the_web", id = "anthropic.web_search_20250305", args = args),
        )

        assertEquals(byHand.body, viaFactory.body)
        assertEquals(byHand.beta, viaFactory.beta)
    }

    @Test
    fun `every factory reaches the wire under its dated type, with the beta the table sends today`() = runTest {
        // Copied from the table as it stood before the factories existed, so a factory whose facts
        // drifted from it fails here rather than at the vendor.
        val expected: Map<String, Pair<String, String?>> = mapOf(
            "anthropic.code_execution_20250522" to ("code_execution_20250522" to "code-execution-2025-05-22"),
            "anthropic.code_execution_20250825" to ("code_execution_20250825" to null),
            "anthropic.code_execution_20260120" to ("code_execution_20260120" to null),
            "anthropic.code_execution_20260521" to ("code_execution_20260521" to null),
            "anthropic.computer_20241022" to ("computer_20241022" to "computer-use-2024-10-22"),
            "anthropic.computer_20250124" to ("computer_20250124" to "computer-use-2025-01-24"),
            "anthropic.computer_20251124" to ("computer_20251124" to "computer-use-2025-11-24"),
            "anthropic.text_editor_20241022" to ("text_editor_20241022" to "computer-use-2024-10-22"),
            "anthropic.text_editor_20250124" to ("text_editor_20250124" to null),
            "anthropic.text_editor_20250429" to ("text_editor_20250429" to null),
            "anthropic.text_editor_20250728" to ("text_editor_20250728" to null),
            "anthropic.bash_20241022" to ("bash_20241022" to "computer-use-2024-10-22"),
            "anthropic.bash_20250124" to ("bash_20250124" to null),
            "anthropic.memory_20250818" to ("memory_20250818" to null),
            "anthropic.web_fetch_20250910" to ("web_fetch_20250910" to null),
            "anthropic.web_fetch_20260209" to ("web_fetch_20260209" to null),
            "anthropic.web_fetch_20260309" to ("web_fetch_20260309" to null),
            "anthropic.web_fetch_20260318" to ("web_fetch_20260318" to null),
            "anthropic.web_search_20250305" to ("web_search_20250305" to null),
            "anthropic.web_search_20260209" to ("web_search_20260209" to null),
            "anthropic.web_search_20260318" to ("web_search_20260318" to null),
            "anthropic.tool_search_regex_20251119" to ("tool_search_tool_regex_20251119" to null),
            "anthropic.tool_search_bm25_20251119" to ("tool_search_tool_bm25_20251119" to null),
            "anthropic.advisor_20260301" to ("advisor_20260301" to "advisor-tool-2026-03-01"),
            "anthropic.computer_toolset_20260801" to ("computer_toolset_20260801" to null),
            "anthropic.browser_toolset_20260801" to ("browser_toolset_20260801" to null),
            "anthropic.mcp_toolset" to ("mcp_toolset" to "mcp-client-2025-11-20"),
        )
        assertEquals(expected.keys, AnthropicTools.all.map { it.id }.toSet())

        AnthropicTools.all.forEach { factory ->
            val (type, beta) = expected.getValue(factory.id)
            val sent = send(factory())

            assertEquals(type, sent.tool["type"].string(), "type of ${factory.id}")
            assertEquals(beta, sent.beta, "beta of ${factory.id}")
            sent.warnings.assertNoWarningAbout("provider-defined tool ${factory.id}")
        }
    }

    @Test
    fun `a toolset factory sends the dated type and no name`() = runTest {
        val computer = send(AnthropicTools.computerToolset_20260801())
        assertTool("""{"type":"computer_toolset_20260801"}""", computer)

        val browser = send(
            AnthropicTools.browserToolset_20260801(
                buildJsonObject {
                    put("configs", buildJsonObject { put("screenshot", buildJsonObject { put("enabled", true) }) })
                },
            ),
        )
        assertTool("""{"type":"browser_toolset_20260801","configs":{"screenshot":{"enabled":true}}}""", browser)
    }

    @Test
    fun `an id outside the table is refused with a warning, never sent under a guessed type`() = runTest {
        val sent = send(Tool.ProviderDefined("x", "anthropic.not_a_real_tool", buildJsonObject { }))

        assertTrue("tools" !in sent.body, "an unmappable tool must not be guessed at: ${sent.body}")
        sent.warnings.assertUnsupported("provider-defined tool anthropic.not_a_real_tool")
    }

    // --- the schemas ----------------------------------------------------------------------------

    private fun JsonObject.enumValues(property: String): List<String> =
        this["properties"]!!.jsonObject[property]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.arms(): List<JsonObject> = this["anyOf"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.discriminator(): String =
        this["properties"]!!.jsonObject["type"]!!.jsonObject["const"]!!.jsonPrimitive.content

    @Test
    fun `the versioned schemas differ where the vendor's versions differ`() {
        // Claude 4 dropped undo_edit.
        assertTrue("undo_edit" in AnthropicTools.textEditor_20250124.inputSchema.enumValues("command"))
        assertFalse("undo_edit" in AnthropicTools.textEditor_20250429.inputSchema.enumValues("command"))
        assertFalse("undo_edit" in AnthropicTools.textEditor_20250728.inputSchema.enumValues("command"))

        // 2025-11-24 added zoom, and the region it takes.
        assertFalse("zoom" in AnthropicTools.computer_20250124.inputSchema.enumValues("action"))
        assertTrue("zoom" in AnthropicTools.computer_20251124.inputSchema.enumValues("action"))
        assertTrue("region" in AnthropicTools.computer_20251124.inputSchema["properties"]!!.jsonObject)
        assertFalse("region" in AnthropicTools.computer_20250124.inputSchema["properties"]!!.jsonObject)

        // 2026-01-20 added the encrypted result arm.
        val arms20250825 = AnthropicTools.codeExecution_20250825.outputSchema!!.arms().map { it.discriminator() }
        val arms20260120 = AnthropicTools.codeExecution_20260120.outputSchema!!.arms().map { it.discriminator() }
        assertFalse("encrypted_code_execution_result" in arms20250825)
        assertTrue("encrypted_code_execution_result" in arms20260120)
        assertEquals(arms20250825.size + 1, arms20260120.size)

        // The two tool searches take different queries and give back the same references.
        assertEquals(
            JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("pattern"))),
            AnthropicTools.toolSearchRegex_20251119.inputSchema["required"],
        )
        assertEquals(
            JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("query"))),
            AnthropicTools.toolSearchBm25_20251119.inputSchema["required"],
        )
        assertEquals(
            AnthropicTools.toolSearchRegex_20251119.outputSchema,
            AnthropicTools.toolSearchBm25_20251119.outputSchema,
        )
    }

    @Test
    fun `a client tool declares an input and no output, a server tool declares both`() {
        assertNull(AnthropicTools.bash_20250124.outputSchema)
        assertEquals(
            listOf("command"),
            AnthropicTools.bash_20250124.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // web search answers with a list, not an object — the schema says so rather than wrapping it.
        assertEquals("array", AnthropicTools.webSearch_20250305.outputSchema!!["type"].string())
        assertEquals("web_fetch_result", AnthropicTools.webFetch_20250910.outputSchema!!.discriminator())
        // The advisor's input is an empty, closed object: the server builds its view from the transcript.
        assertEquals(
            parseJsonObject("""{"type":"object","properties":{},"additionalProperties":false}"""),
            AnthropicTools.advisor_20260301.inputSchema,
        )
    }
}
