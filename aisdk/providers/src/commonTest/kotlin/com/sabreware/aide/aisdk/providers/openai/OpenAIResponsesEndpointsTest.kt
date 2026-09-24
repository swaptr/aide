package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openaicompatible.Vendors
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import com.sabreware.aide.aisdk.providers.xai.XaiTools
import com.sabreware.aide.aisdk.providers.xai.XaiProvider
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The Responses wire served by vendors that are not OpenAI.
 *
 * One model serves them all — see `OpenAIResponsesLanguageModel`'s `provider`/`namespace`/`endpointUrl`
 * parameters — so what these tests pin is the part that differs per vendor: the URL each one actually
 * answers on, which namespace its options are read from and its metadata filed under, and the dialect
 * quirks that would otherwise be silently wrong (xAI's finish spellings and cache accounting, the
 * Hugging Face router's function-only tool surface).
 */
class OpenAIResponsesEndpointsTest {

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))))

    private val emptyResponse = TestServer.json("""{"id":"resp_1","output":[]}""")

    // --- OpenAI's newer tools ------------------------------------------------------------------------

    @Test
    fun `apply_patch and programmatic_tool_calling go out as bare types`() = runTest {
        val server = TestServer(emptyResponse)
        val model = OpenAIProvider(HttpClient(server.engine()), "k").languageModel("gpt-5")!!

        model.doGenerate(
            call.copy(
                tools = listOf(
                    Tool.ProviderDefined("patch", "openai.apply_patch", buildJsonObject { put("ignored", "x") }),
                    Tool.ProviderDefined("ptc", "openai.programmatic_tool_calling", buildJsonObject { }),
                ),
            ),
        )

        val tools = server.request().bodyJson()["tools"]!!.jsonArray.map { it.jsonObject }
        // Both are configured by being present; the reference emits `{type}` alone and ignores args.
        assertEquals(listOf("apply_patch", "programmatic_tool_calling"), tools.map { it["type"].string() })
        assertEquals(listOf(1, 1), tools.map { it.size })
    }

    @Test
    fun `the shell tool's container environment is renamed arm by arm`() = runTest {
        val server = TestServer(emptyResponse)
        val model = OpenAIProvider(HttpClient(server.engine()), "k").languageModel("gpt-5")!!

        model.doGenerate(
            call.copy(
                tools = listOf(
                    Tool.ProviderDefined(
                        name = "shell",
                        id = "openai.shell",
                        args = buildJsonObject {
                            put(
                                "environment",
                                buildJsonObject {
                                    put("type", "containerAuto")
                                    put("memoryLimit", "4g")
                                    put(
                                        "networkPolicy",
                                        buildJsonObject {
                                            put("type", "allowlist")
                                            put("allowedDomains", buildJsonArray { add("example.com") })
                                        },
                                    )
                                },
                            )
                        },
                    ),
                ),
            ),
        )

        val environment = server.request().bodyJson()["tools"]!!.jsonArray.single()
            .jsonObject["environment"]!!.jsonObject
        // The arm's own discriminator is renamed too — a generic camelCase pass would leave
        // `containerAuto` on the wire, which the endpoint does not know.
        assertEquals("container_auto", environment["type"].string())
        assertEquals("4g", environment["memory_limit"].string())
        assertEquals("allowlist", environment["network_policy"]!!.jsonObject["type"].string())
        assertEquals(
            "example.com",
            environment["network_policy"]!!.jsonObject["allowed_domains"]!!.jsonArray.single().string(),
        )
    }

    // --- Vendor tool dialects ------------------------------------------------------------------------

    @Test
    fun `an xAI agent tool reaches the wire under xAI's own type`() = runTest {
        val server = TestServer(emptyResponse)
        val model = XaiProvider(HttpClient(server.engine()), "k").languageModel("grok-4")!!

        model.doGenerate(
            call.copy(
                tools = listOf(
                    XaiTools.codeExecution(),
                    XaiTools.webSearch(),
                ),
            ),
        )

        val sent = server.request().bodyJson()["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.string() }
        // Without the vendor table these were dropped with "xai.web_search is not a Responses API
        // tool" — eight declared tools that never reached a request. And code_execution must go out
        // under xAI's OWN spelling: the id is what keeps the two ends joined.
        assertEquals(listOf("code_interpreter", "web_search"), sent)
    }

    @Test
    fun `an OpenAI tool id is still unknown to xAI, and says so`() = runTest {
        val server = TestServer(emptyResponse)
        val model = XaiProvider(HttpClient(server.engine()), "k").languageModel("grok-4")!!

        val result = model.doGenerate(
            call.copy(
                tools = listOf(
                    Tool.ProviderDefined(name = "shell", id = "openai.local_shell", args = buildJsonObject { }),
                ),
            ),
        )

        // The vendor table is consulted FIRST but does not inherit OpenAI's: a tool xAI does not serve
        // must warn rather than go out under a type that endpoint has never heard of.
        assertTrue(result.warnings.any { it is Warning.Unsupported && it.feature == "providerTool:shell" })
    }

    // --- Azure ---------------------------------------------------------------------------------------

    @Test
    fun `azure's default language model is the Responses API on the v1 surface`() = runTest {
        val server = TestServer(emptyResponse)
        val model = Vendors.azure(HttpClient(server.engine()), "k", resourceName = "my-resource")
            .languageModel("my-deployment")!!

        model.doGenerate(call)

        val request = server.request()
        // No deployment segment: the v1 surface takes the deployment as the body's model field. And no
        // `api-version` — Microsoft documents it as no longer required there, and `v1` was never one of
        // its values, so sending it claimed a version that does not exist.
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1/responses",
            request.url,
        )
        assertEquals("my-deployment", request.bodyJson()["model"].string())
        assertEquals("k", request.header("api-key"))
        assertNull(request.header("Authorization"), "Azure authenticates with api-key, not a bearer")
        assertEquals("azure.responses", model.provider)
    }

    @Test
    fun `azure reads canonical openai options underneath its own, custom key winning`() = runTest {
        val server = TestServer(emptyResponse)
        Vendors.azure(HttpClient(server.engine()), "k", resourceName = "r")
            .languageModel("d")!!
            .doGenerate(
                call.copy(
                    providerOptions = mapOf(
                        // The moved-from-OpenAI prompt keeps working: `openai`-filed options are read…
                        "openai" to buildJsonObject {
                            put("store", false)
                            put("serviceTier", "default")
                        },
                        // …and the vendor key overrides exactly the fields it names.
                        "azure" to buildJsonObject { put("serviceTier", "flex") },
                    ),
                ),
            )

        val body = server.request().bodyJson()
        assertEquals(false, body["store"]!!.toString().toBoolean())
        assertEquals("flex", body["service_tier"].string())
    }

    @Test
    fun `azure's Chat Completions path stays reachable beside the Responses default`() = runTest {
        val server = TestServer(
            TestServer.sse(
                "data: {\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}\n\n",
                "data: [DONE]\n\n",
            ),
        )
        val provider = Vendors.azure(HttpClient(server.engine()), "k", resourceName = "my-resource")

        provider.chatLanguageModel("my-deployment")!!.doStream(call).stream.toList()

        // The deployment-path URL scheme, unchanged: gateways and older deployments still speak it.
        assertEquals(
            "https://my-resource.openai.azure.com/openai/deployments/my-deployment" +
                "/chat/completions?api-version=2024-10-21",
            server.request().url,
        )
    }

    // --- xAI -----------------------------------------------------------------------------------------

    private fun xaiModel(server: TestServer) =
        XaiProvider(HttpClient(server.engine()), apiKey = "k").languageModel("grok-4")

    @Test
    fun `xai serves the Responses wire at its own endpoint under its own namespace`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"resp_1","output":[
                    {"type":"reasoning","id":"rs_1","encrypted_content":"xai-payload",
                     "summary":[{"type":"summary_text","text":"Think."}]}
                ]}""",
            ),
        )
        val model = xaiModel(server)

        val result = model.doGenerate(
            call.copy(providerOptions = mapOf("xai" to buildJsonObject { put("store", false) })),
        )

        val request = server.request()
        assertEquals("https://api.x.ai/v1/responses", request.url)
        assertEquals("Bearer k", request.header("Authorization"))
        assertEquals("xai", model.provider)
        // The option was filed under `xai` and consumed as if it were `openai`'s.
        assertEquals(false, request.bodyJson()["store"]!!.toString().toBoolean())

        // Everything the model EMITS files under `xai`: a conversation held against xAI replays its
        // reasoning as xAI's, not as OpenAI's.
        val reasoning = assertIs<Content.Reasoning>(result.content.first())
        assertEquals(
            "xai-payload",
            reasoning.providerMetadata?.get("xai")?.get(OPENAI_ENCRYPTED_REASONING_KEY).string(),
        )
        assertNull(reasoning.providerMetadata?.get("openai"))
    }

    @Test
    fun `xai spells truncation length, which the OpenAI mapping would read as other`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"resp_1","output":[],"incomplete_details":{"reason":"length"}}"""),
        )

        val result = xaiModel(server).doGenerate(call)

        assertEquals(FinishReason.Unified.Length, result.finishReason.unified)
        assertEquals("length", result.finishReason.raw)
    }

    @Test
    fun `xai's input_tokens may exclude the cached tokens, and the correction is arithmetic`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"resp_1","output":[],"usage":{"input_tokens":5,"output_tokens":7,
                    "input_tokens_details":{"cached_tokens":30}}}""",
            ),
        )

        val usage = xaiModel(server).doGenerate(call).usage

        // 30 cached out of a reported 5 can only mean the 5 EXCLUDES them; without the correction the
        // no-cache share goes to -25 on exactly the calls where caching worked best.
        assertEquals(35, usage.inputTokens.total)
        assertEquals(5, usage.inputTokens.noCache)
        assertEquals(30, usage.inputTokens.cacheRead)
    }

    // --- Hugging Face --------------------------------------------------------------------------------

    @Test
    fun `the hugging face router serves Responses with a function-only tool surface`() = runTest {
        val server = TestServer(emptyResponse)
        val model = Vendors.huggingFace(HttpClient(server.engine()), "k")
            .languageModel("meta-llama/Llama-3.3-70B-Instruct")!!

        val result = model.doGenerate(
            call.copy(
                tools = listOf(
                    Tool.Function("lookup", buildJsonObject { put("type", "object") }),
                    Tool.ProviderDefined(
                        name = "web_search",
                        id = "openai.web_search",
                        args = buildJsonObject { },
                    ),
                ),
                toolChoice = ToolChoice.Specific("lookup"),
            ),
        )

        val request = server.request()
        assertEquals("https://router.huggingface.co/v1/responses", request.url)
        assertEquals("huggingface.responses", model.provider)

        // The provider tool was refused with a warning, not forwarded to a router that rejects it.
        val tools = request.bodyJson().arr("tools")!!
        assertEquals(listOf("lookup"), tools.map { it.jsonObject["name"].string() })
        assertTrue(result.warnings.any { it is Warning.Unsupported && it.feature.startsWith("providerTool") })

        // The router nests the pinned name; OpenAI's flat shape silently never engages there.
        val choice = request.bodyJson().obj("tool_choice")!!
        assertEquals("function", choice["type"].string())
        assertEquals("lookup", choice.obj("function")?.get("name").string())
    }

    @Test
    fun `the router cannot express tool_choice none, and says so instead of dropping it`() = runTest {
        val server = TestServer(emptyResponse)

        val result = Vendors.huggingFace(HttpClient(server.engine()), "k")
            .languageModel("m")!!
            .doGenerate(
                call.copy(
                    tools = listOf(Tool.Function("lookup", buildJsonObject { })),
                    toolChoice = ToolChoice.None,
                ),
            )

        assertNull(server.request().bodyJson()["tool_choice"])
        assertTrue(result.warnings.any { it is Warning.Unsupported && it.feature == "toolChoice" })
    }

    // --- open-responses ------------------------------------------------------------------------------

    @Test
    fun `an open-responses server is addressed by its complete URL and its own namespace`() = runTest {
        val server = TestServer(emptyResponse)
        val model = OpenResponsesProvider(
            client = HttpClient(server.engine()),
            url = "http://localhost:8080/openai/responses",
            name = "local",
            apiKey = "k",
        ).languageModel("some-model")

        model.doGenerate(
            call.copy(providerOptions = mapOf("local" to buildJsonObject { put("store", false) })),
        )

        val request = server.request()
        // The complete URL, verbatim: these servers do not agree on a path, so nothing may be appended.
        assertEquals("http://localhost:8080/openai/responses", request.url)
        assertEquals("Bearer k", request.header("Authorization"))
        assertEquals("local.responses", model.provider)
        assertEquals(false, request.bodyJson()["store"]!!.toString().toBoolean())
    }

    // --- xAI keeps its schemas whole ------------------------------------------------------------------

    @Test
    fun `xAI keeps additionalProperties false on a function tool's schema`() = runTest {
        // The reference used to strip `additionalProperties: false` for xAI; it no longer does, and
        // this port never did. Pinned so the strip is not reintroduced by a helpful hand.
        val server = TestServer(emptyResponse)
        XaiProvider(HttpClient(server.engine()), "k").languageModel("grok-4")!!.doGenerate(
            call.copy(
                tools = listOf(
                    Tool.Function(
                        "weather",
                        parseJsonObject(
                            """{"type":"object","properties":{"location":{"type":"string"}},"required":["location"],"additionalProperties":false}""",
                        ),
                        description = "get weather information",
                    ),
                ),
            ),
        )

        assertEquals(
            parseJsonObject(
                """{"type":"function","name":"weather","description":"get weather information","parameters":""" +
                    """{"type":"object","properties":{"location":{"type":"string"}},"required":["location"],"additionalProperties":false}}""",
            ),
            server.request().bodyJson()["tools"]!!.jsonArray.single().jsonObject,
        )
    }
}
