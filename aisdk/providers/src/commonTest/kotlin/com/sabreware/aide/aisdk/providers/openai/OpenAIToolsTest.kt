package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertCompatibility
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `OpenAITools` — the factories a caller builds OpenAI's tools with, and the one table the request
 * builder sends them by.
 *
 * Two things are pinned. The wire bodies are the reference's own inline snapshots from
 * `openai-responses-prepare-tools.test.ts` (with its `undefined` fields absent, which is what
 * `undefined` means on the wire). And a tool built through a factory produces the SAME body as one
 * built by hand from its id — the factories are a surface over the existing table, not a second one.
 */
class OpenAIToolsTest {

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))))

    private fun server() = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))

    private suspend fun requestBody(tools: List<Tool>, toolChoice: ToolChoice? = null): JsonObject {
        val server = server()
        OpenAIResponsesLanguageModel(modelId = "gpt-5", http = server.http())
            .doGenerate(call.copy(tools = tools, toolChoice = toolChoice))
        return server.request().bodyJson()
    }

    /**
     * The factory-built tool goes out as [expected], and byte-identically to the hand-built one: the
     * factory adds a surface, never a second table.
     */
    private suspend fun assertWire(tool: Tool.ProviderDefined, expected: String) {
        val viaFactory = requestBody(listOf(tool))["tools"]!!.jsonArray
        val byHand = requestBody(listOf(Tool.ProviderDefined(name = tool.name, id = tool.id, args = tool.args)))["tools"]
        assertEquals(parseJsonObject(expected), viaFactory.single().jsonObject, "wire body of ${tool.id}")
        assertEquals(byHand, viaFactory, "a factory-built ${tool.id} must match the hand-built one")
    }

    // --- the surface ---------------------------------------------------------------------------------

    @Test
    fun `every reference tool has a factory, with the reference's kind and flags`() {
        val expectedKinds = mapOf(
            "openai.apply_patch" to false,
            "openai.custom" to false,
            "openai.code_interpreter" to true,
            "openai.computer" to false,
            "openai.file_search" to true,
            "openai.image_generation" to true,
            "openai.local_shell" to false,
            "openai.shell" to false,
            "openai.web_search_preview" to true,
            "openai.web_search" to true,
            "openai.mcp" to true,
            "openai.programmatic_tool_calling" to true,
            "openai.tool_search" to false,
        )
        assertEquals(expectedKinds.keys, OpenAITools.all.map { it.id }.toSet())
        OpenAITools.all.forEach { factory ->
            assertEquals(expectedKinds.getValue(factory.id), factory.providerExecuted, "${factory.id} providerExecuted")
            assertEquals(factory.id.removePrefix("openai."), factory.wireName, "${factory.id} wire name")
            // Only programmatic tool calling hands off to a client tool mid-flight and answers later.
            assertEquals(
                factory.id == "openai.programmatic_tool_calling",
                factory.supportsDeferredResults,
                "${factory.id} supportsDeferredResults",
            )
        }
        // The custom tool's input is a grammar-constrained string, and it declares no output.
        assertEquals(buildJsonObject { put("type", "string") }, OpenAITools.customTool.inputSchema)
        assertNull(OpenAITools.customTool.outputSchema)
        OpenAITools.all.filter { it.providerExecuted }.forEach {
            assertTrue(it.outputSchema != null, "${it.id} is executed by OpenAI and must declare what comes back")
        }
    }

    @Test
    fun `the request builder's table is derived from the factories, not kept beside them`() {
        assertEquals(OpenAITools.all.associate { it.id to it.wireName }, OpenAIProviderToolNames)
    }

    @Test
    fun `a factory instantiates a tool that carries its flags and the caller's name`() {
        val tool = OpenAITools.programmaticToolCalling(name = "orchestrate")
        assertEquals("openai.programmatic_tool_calling", tool.id)
        assertEquals("orchestrate", tool.name)
        assertTrue(tool.providerExecuted)
        assertTrue(tool.supportsDeferredResults)
        assertEquals(JsonObject(emptyMap()), tool.args)
    }

    // --- wire bodies, from the reference's prepare-tools snapshots --------------------------------

    @Test
    fun `web_search with no options`() = runTest {
        assertWire(OpenAITools.webSearch(), """{"type":"web_search"}""")
    }

    @Test
    fun `web_search with all options including externalWebAccess`() = runTest {
        assertWire(
            OpenAITools.webSearch(
                buildJsonObject {
                    put("externalWebAccess", true)
                    putJsonObject("filters") {
                        putJsonArray("allowedDomains") { add("example.com"); add("test.org") }
                        putJsonArray("blockedDomains") { add("blocked.example"); add("blocked.test") }
                    }
                    put("searchContextSize", "high")
                    putJsonObject("userLocation") {
                        put("type", "approximate")
                        put("country", "US")
                        put("city", "San Francisco")
                        put("region", "California")
                        put("timezone", "America/Los_Angeles")
                    }
                },
            ),
            """{"external_web_access":true,
                "filters":{"allowed_domains":["example.com","test.org"],"blocked_domains":["blocked.example","blocked.test"]},
                "search_context_size":"high","type":"web_search",
                "user_location":{"city":"San Francisco","country":"US","region":"California",
                "timezone":"America/Los_Angeles","type":"approximate"}}""",
        )
    }

    @Test
    fun `web_search with blocked domains only`() = runTest {
        assertWire(
            OpenAITools.webSearch(
                buildJsonObject {
                    putJsonObject("filters") { putJsonArray("blockedDomains") { add("example.com") } }
                },
            ),
            """{"filters":{"blocked_domains":["example.com"]},"type":"web_search"}""",
        )
    }

    @Test
    fun `web_search_preview renames its two options`() = runTest {
        assertWire(
            OpenAITools.webSearchPreview(
                buildJsonObject {
                    put("searchContextSize", "low")
                    putJsonObject("userLocation") {
                        put("type", "approximate")
                        put("city", "Minneapolis")
                    }
                },
            ),
            """{"type":"web_search_preview","search_context_size":"low",
                "user_location":{"type":"approximate","city":"Minneapolis"}}""",
        )
    }

    @Test
    fun `file_search renames the store ids, the cap and the ranking block`() = runTest {
        assertWire(
            OpenAITools.fileSearch(
                buildJsonObject {
                    putJsonArray("vectorStoreIds") { add("vs_123") }
                    put("maxNumResults", 5)
                    putJsonObject("ranking") {
                        put("ranker", "auto")
                        put("scoreThreshold", 0.5)
                    }
                },
            ),
            """{"type":"file_search","vector_store_ids":["vs_123"],"max_num_results":5,
                "ranking_options":{"ranker":"auto","score_threshold":0.5}}""",
        )
    }

    @Test
    fun `code_interpreter with no container is an auto container, because OpenAI requires the field`() = runTest {
        assertWire(OpenAITools.codeInterpreter(), """{"container":{"type":"auto"},"type":"code_interpreter"}""")
    }

    @Test
    fun `code_interpreter with a string container passes the id through`() = runTest {
        assertWire(
            OpenAITools.codeInterpreter(buildJsonObject { put("container", "container-123") }),
            """{"container":"container-123","type":"code_interpreter"}""",
        )
    }

    @Test
    fun `code_interpreter with file ids seeds an auto container`() = runTest {
        assertWire(
            OpenAITools.codeInterpreter(
                buildJsonObject {
                    putJsonObject("container") {
                        putJsonArray("fileIds") { add("file-1"); add("file-2"); add("file-3") }
                    }
                },
            ),
            """{"container":{"file_ids":["file-1","file-2","file-3"],"type":"auto"},"type":"code_interpreter"}""",
        )
        assertWire(
            OpenAITools.codeInterpreter(buildJsonObject { putJsonObject("container") { putJsonArray("fileIds") { } } }),
            """{"container":{"file_ids":[],"type":"auto"},"type":"code_interpreter"}""",
        )
    }

    @Test
    fun `image_generation with all options`() = runTest {
        assertWire(
            OpenAITools.imageGeneration(
                buildJsonObject {
                    put("background", "opaque")
                    put("size", "1536x1024")
                    put("quality", "high")
                    put("moderation", "auto")
                    put("outputFormat", "png")
                    put("outputCompression", 100)
                },
            ),
            """{"background":"opaque","moderation":"auto","output_compression":100,"output_format":"png",
                "quality":"high","size":"1536x1024","type":"image_generation"}""",
        )
    }

    @Test
    fun `mcp renames its documented keys and leaves the headers alone`() = runTest {
        assertWire(
            OpenAITools.mcp(
                buildJsonObject {
                    put("serverLabel", "deepwiki")
                    put("serverUrl", "https://mcp.deepwiki.com/mcp")
                    putJsonObject("allowedTools") {
                        put("readOnly", true)
                        putJsonArray("toolNames") { add("ask_question") }
                    }
                    putJsonObject("headers") { put("X-Custom-Header", "v") }
                    putJsonObject("requireApproval") {
                        putJsonObject("never") { putJsonArray("toolNames") { add("ask_question") } }
                    }
                },
            ),
            """{"type":"mcp","server_label":"deepwiki","server_url":"https://mcp.deepwiki.com/mcp",
                "allowed_tools":{"read_only":true,"tool_names":["ask_question"]},
                "headers":{"X-Custom-Header":"v"},
                "require_approval":{"never":{"tool_names":["ask_question"]}}}""",
        )
    }

    @Test
    fun `a custom tool carries its name, description and grammar`() = runTest {
        assertWire(
            OpenAITools.customTool(
                args = buildJsonObject {
                    put("description", "Write a SQL SELECT query.")
                    putJsonObject("format") {
                        put("type", "grammar")
                        put("syntax", "regex")
                        put("definition", "SELECT .+")
                    }
                },
                name = "write_sql",
            ),
            """{"description":"Write a SQL SELECT query.",
                "format":{"definition":"SELECT .+","syntax":"regex","type":"grammar"},
                "name":"write_sql","type":"custom"}""",
        )
    }

    @Test
    fun `the shell tool without an environment is its bare type`() = runTest {
        assertWire(OpenAITools.shell(), """{"type":"shell"}""")
    }

    @Test
    fun `the shell tool's containerAuto environment with a referenced skill`() = runTest {
        assertWire(
            OpenAITools.shell(
                buildJsonObject {
                    putJsonObject("environment") {
                        put("type", "containerAuto")
                        putJsonArray("skills") {
                            add(
                                buildJsonObject {
                                    put("type", "skillReference")
                                    putJsonObject("providerReference") { put("openai", "skill_abc") }
                                    put("version", "1.0.0")
                                },
                            )
                        }
                    }
                },
            ),
            """{"environment":{"skills":[{"skill_id":"skill_abc","type":"skill_reference","version":"1.0.0"}],
                "type":"container_auto"},"type":"shell"}""",
        )
    }

    @Test
    fun `the shell tool's allowlist network policy with domain secrets`() = runTest {
        assertWire(
            OpenAITools.shell(
                buildJsonObject {
                    putJsonObject("environment") {
                        put("type", "containerAuto")
                        putJsonObject("networkPolicy") {
                            put("type", "allowlist")
                            putJsonArray("allowedDomains") { add("example.com"); add("api.test.org") }
                            putJsonArray("domainSecrets") {
                                add(
                                    buildJsonObject {
                                        put("domain", "api.test.org")
                                        put("name", "API_KEY")
                                        put("value", "secret123")
                                    },
                                )
                            }
                        }
                    }
                },
            ),
            """{"environment":{"network_policy":{"allowed_domains":["example.com","api.test.org"],
                "domain_secrets":[{"domain":"api.test.org","name":"API_KEY","value":"secret123"}],
                "type":"allowlist"},"type":"container_auto"},"type":"shell"}""",
        )
    }

    @Test
    fun `the tools configured by presence alone go out as bare types`() = runTest {
        assertWire(OpenAITools.localShell(), """{"type":"local_shell"}""")
        assertWire(OpenAITools.computer(), """{"type":"computer"}""")
        assertWire(OpenAITools.applyPatch(), """{"type":"apply_patch"}""")
        assertWire(OpenAITools.toolSearch(), """{"type":"tool_search"}""")
        assertWire(OpenAITools.programmaticToolCalling(), """{"type":"programmatic_tool_calling"}""")
    }

    // --- tool choice ---------------------------------------------------------------------------------

    @Test
    fun `a built-in selected by name is its bare type, a custom tool carries its name`() = runTest {
        val builtIn = requestBody(listOf(OpenAITools.imageGeneration()), ToolChoice.Specific("image_generation"))
        assertEquals(buildJsonObject { put("type", "image_generation") }, builtIn["tool_choice"])

        val custom = requestBody(listOf(OpenAITools.customTool(name = "write_sql")), ToolChoice.Specific("write_sql"))
        assertEquals(
            buildJsonObject {
                put("type", "custom")
                put("name", "write_sql")
            },
            custom["tool_choice"],
        )
    }

    // --- the foreign-id rule -------------------------------------------------------------------------

    @Test
    fun `a foreign tool id is refused with a warning, never sent under an unknown type`() = runTest {
        val server = server()
        val result = OpenAIResponsesLanguageModel(modelId = "gpt-5", http = server.http()).doGenerate(
            call.copy(
                tools = listOf(
                    Tool.ProviderDefined(name = "search", id = "anthropic.web_search_20250305", args = buildJsonObject { }),
                    OpenAITools.webSearch(),
                ),
            ),
        )

        // Only OpenAI's own tool reached the wire; Anthropic's was refused rather than sent as a type
        // the endpoint has never heard of — which would be a 400 naming neither the tool nor the cause.
        val sent = server.request().bodyJson()["tools"]!!.jsonArray
        assertEquals(buildJsonArray { add(buildJsonObject { put("type", "web_search") }) }, sent)
        result.warnings.assertUnsupported(
            "providerTool:search",
            "anthropic.web_search_20250305 is not a Responses API tool.",
        )
    }

    // --- the 2026-09 delta: async tools, propertyNames, image generation options -----------------------

    @Test
    fun `propertyNames is removed from a function tool's parameters and the caller is told`() = runTest {
        val server = server()
        val result = OpenAIResponsesLanguageModel(modelId = "gpt-5", http = server.http()).doGenerate(
            call.copy(
                tools = listOf(
                    Tool.Function(
                        "get_weather",
                        parseJsonObject(
                            """{"type":"object","properties":{"values":{"type":"object","propertyNames":{"type":"string","format":"uuid"}}}}""",
                        ),
                    ),
                ),
            ),
        )

        val tool = server.request().bodyJson()["tools"]!!.jsonArray.single().jsonObject
        assertEquals(
            parseJsonObject("""{"type":"object","properties":{"values":{"type":"object"}}}"""),
            tool["parameters"],
        )
        result.warnings.assertCompatibility(
            "JSON Schema propertyNames",
            "OpenAI does not support JSON Schema propertyNames. It was removed before sending the schema, " +
                "so OpenAI will not enforce property-name constraints.",
        )
    }

    @Test
    fun `a custom tool's async flag passes through where the model supports it`() = runTest {
        val prepared = prepareTools(
            listOf(
                OpenAITools.customTool(
                    args = buildJsonObject {
                        put("description", "Write a SQL query")
                        put("async", true)
                        putJsonObject("format") { put("type", "text") }
                    },
                    name = "write_sql",
                ),
            ),
            toolChoice = null,
        )

        assertEquals(
            parseJsonObject("""{"type":"custom","name":"write_sql","description":"Write a SQL query","async":true,"format":{"type":"text"}}"""),
            prepared.tools!!.single().jsonObject,
        )
        assertTrue(prepared.warnings.isEmpty())
    }

    @Test
    fun `async is dropped with a warning on a model without async tool calling`() = runTest {
        val prepared = prepareTools(
            listOf(
                Tool.Function(
                    "get_weather",
                    parseJsonObject("""{"type":"object","properties":{}}"""),
                    providerOptions = mapOf("openai" to buildJsonObject { put("async", true) }),
                ),
                OpenAITools.customTool(args = buildJsonObject { put("async", true) }, name = "write_sql"),
            ),
            toolChoice = null,
            supportsAsyncToolCalling = false,
        )

        assertEquals(
            listOf(
                parseJsonObject("""{"type":"function","name":"get_weather","parameters":{"type":"object","properties":{}}}"""),
                parseJsonObject("""{"type":"custom","name":"write_sql"}"""),
            ),
            prepared.tools!!.map { it.jsonObject },
        )
        assertEquals(
            listOf(
                Warning.Unsupported(
                    feature = "async tool calling for \"get_weather\"",
                    details = "Async tool calling is only supported by GPT-6 and later models.",
                ),
                Warning.Unsupported(
                    feature = "async tool calling for \"write_sql\"",
                    details = "Async tool calling is only supported by GPT-6 and later models.",
                ),
            ),
            prepared.warnings,
        )
    }

    @Test
    fun `image generation passes action, low moderation and a gpt-image-2 size`() = runTest {
        assertWire(
            OpenAITools.imageGeneration(
                args = buildJsonObject {
                    put("action", "edit")
                    put("model", "gpt-image-2")
                    put("moderation", "low")
                    put("size", "1536x864")
                },
            ),
            """{"type":"image_generation","action":"edit","model":"gpt-image-2","moderation":"low","size":"1536x864"}""",
        )
    }

    @Test
    fun `GPT Image 2 point 5 takes xhigh and max quality`() = runTest {
        listOf("gpt-image-2.5-flare", "gpt-image-2.5-sunburst").forEach { model ->
            listOf("xhigh", "max").forEach { quality ->
                val server = server()
                val result = OpenAIResponsesLanguageModel(modelId = "gpt-5", http = server.http()).doGenerate(
                    call.copy(
                        tools = listOf(
                            OpenAITools.imageGeneration(
                                args = buildJsonObject {
                                    put("model", model)
                                    put("quality", quality)
                                },
                            ),
                        ),
                    ),
                )

                val tool = server.request().bodyJson()["tools"]!!.jsonArray.single().jsonObject
                assertNotNull(tool)
                assertEquals(
                    parseJsonObject("""{"type":"image_generation","model":"$model","quality":"$quality"}"""),
                    tool,
                    "$model / $quality",
                )
                assertTrue(result.warnings.isEmpty())
            }
        }
    }
}
