package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What Gemini actually receives.
 *
 * Asserted against the serialized body: `ProviderJson` sets `encodeDefaults = false`, so a field left at
 * a Kotlin default never reaches the wire and an assertion on the request object cannot see its absence.
 */
class GoogleRequestTest {

    private val finished =
        TestServer.sse("""data: {"candidates":[{"finishReason":"STOP"}]}""" + "\n\n")

    private fun call(text: String = "hi") = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text(text)))),
        reasoning = ReasoningEffort.ProviderDefault,
    )

    private suspend fun send(
        options: CallOptions,
        modelId: String = "gemini-3-pro",
    ): Pair<JsonObject, List<Warning>> {
        val server = TestServer(finished)
        val parts = GoogleLanguageModel(modelId = modelId, http = server.http())
            .doStream(options).stream.toList()
        return server.request().bodyJson() to
            parts.filterIsInstance<StreamPart.StreamStart>().flatMap { it.warnings }
    }

    // --- tool schemas ---------------------------------------------------------------------------

    /** The reference's `format-date` tool: every keyword the old OpenAPI `parameters` field refused. */
    private val formatDateSchema = parseJsonObject(
        """{"type":"object","properties":{"locale":{"${'$'}ref":"#/${'$'}defs/Locale",""" +
            """"description":"Locale for formatting"}},"required":["locale"],"additionalProperties":false,""" +
            """"${'$'}defs":{"Locale":{"type":"string","enum":["de","en"]}}}""",
    )

    @Test
    fun `a tool schema is preserved verbatim under parametersJsonSchema`() = runTest {
        // "should preserve local JSON Schema references in Gemini Developer API tool requests". Gemini
        // reads JSON Schema here itself — the `$ref`, its `$defs` and `additionalProperties` all reach it
        // as written. The OpenAPI rewrite this replaced inlined the reference and dropped the rest.
        val (body, _) = send(
            call().copy(tools = listOf(Tool.Function("format-date", formatDateSchema, description = "Format a date"))),
        )

        val declaration = body.arr("tools")!!.single().jsonObject
            .arr("functionDeclarations")!!.single().jsonObject
        assertEquals(formatDateSchema, declaration.obj("parametersJsonSchema"))
        assertNull(declaration["parameters"], "one schema field now; the OpenAPI one is gone")
    }

    @Test
    fun `a recursive schema goes out verbatim too - there is no second path any more`() = runTest {
        val schema = parseJsonObject(
            """{"type":"object","properties":{"child":{"${'$'}ref":"#/${'$'}defs/Node"}},""" +
                """"${'$'}defs":{"Node":{"type":"object","properties":{"next":{"${'$'}ref":"#/${'$'}defs/Node"}}}}}""",
        )

        val (body, _) = send(call().copy(tools = listOf(Tool.Function("tree", schema))))

        val declaration = body.arr("tools")!!.single().jsonObject
            .arr("functionDeclarations")!!.single().jsonObject
        assertEquals(schema, declaration.obj("parametersJsonSchema"))
    }

    @Test
    fun `a response schema is preserved under responseJsonSchema`() = runTest {
        // "should preserve local JSON Schema references in response schemas".
        val schema = parseJsonObject(
            """{"type":"object","properties":{"locale":{"${'$'}ref":"#/${'$'}defs/Locale"}},"required":["locale"],""" +
                """"${'$'}defs":{"Locale":{"type":"string","enum":["de","en"]}}}""",
        )

        val (body, _) = send(call().copy(responseFormat = ResponseFormat.Json(schema)))

        val config = body.obj("generationConfig")!!
        assertEquals("application/json", config["responseMimeType"].string())
        assertEquals(schema, config.obj("responseJsonSchema"))
        assertNull(config["responseSchema"], "the OpenAPI field is gone")
    }

    @Test
    fun `array length constraints reach the response schema`() = runTest {
        // "should pass array length constraints in response schemas" — the OpenAPI rewrite dropped them.
        val schema = parseJsonObject(
            """{"type":"object","properties":{"elements":{"type":"array","items":{"type":"string"},""" +
                """"minItems":2,"maxItems":4}},"required":["elements"]}""",
        )

        val (body, _) = send(call().copy(responseFormat = ResponseFormat.Json(schema)))

        assertEquals(schema, body.obj("generationConfig", "responseJsonSchema"))
    }

    // --- built-in tools -------------------------------------------------------------------------

    @Test
    fun `a built-in tool is its own entry beside the function declarations`() = runTest {
        val (body, _) = send(
            call().copy(
                tools = listOf(
                    Tool.ProviderDefined("search", "google.google_search", buildJsonObject { }),
                    Tool.Function("echo", buildJsonObject { put("type", "object") }),
                ),
            ),
        )

        val tools = body.arr("tools")!!.map { it.jsonObject }
        // Google's built-ins are SIBLING KEYS of functionDeclarations, never entries in it.
        assertEquals(listOf("googleSearch", "functionDeclarations"), tools.map { it.keys.single() })
        assertEquals("VALIDATED", body.obj("toolConfig", "functionCallingConfig")!!["mode"].string())
        assertEquals(true, body.obj("toolConfig")!!["includeServerSideToolInvocations"].bool())
    }

    @Test
    fun `a built-in tool the model cannot serve is reported rather than sent`() = runTest {
        val (body, warnings) = send(
            call().copy(
                tools = listOf(Tool.ProviderDefined("s", "google.file_search", buildJsonObject { })),
            ),
            modelId = "gemini-2.0-flash",
        )

        assertNull(body["tools"])
        warnings.assertUnsupported(
            "provider-defined tool google.file_search",
            "The file search tool is only supported with Gemini 2.5 and Gemini 3 models.",
        )
    }

    @Test
    fun `mixing tool kinds is warned about on a model that cannot do both`() = runTest {
        val (_, warnings) = send(
            call().copy(
                tools = listOf(
                    Tool.ProviderDefined("s", "google.google_search", buildJsonObject { }),
                    Tool.Function("echo", buildJsonObject { put("type", "object") }),
                ),
            ),
            modelId = "gemini-2.0-flash",
        )

        warnings.assertUnsupported("combination of function and provider-defined tools")
    }

    // --- provider options and samplers ----------------------------------------------------------

    @Test
    fun `the penalties Gemini accepts are sent rather than dropped`() = runTest {
        val (body, warnings) = send(call().copy(presencePenalty = 0.4, frequencyPenalty = 0.2))

        val config = body.obj("generationConfig")!!
        assertEquals(0.4, config["presencePenalty"].double())
        assertEquals(0.2, config["frequencyPenalty"].double())
        assertTrue(warnings.isEmpty(), "nothing was unsupported here: $warnings")
    }

    @Test
    fun `call-level providerOptions reach the body`() = runTest {
        val options = call().copy(
            providerOptions = mapOf(
                GOOGLE_PROVIDER_ID to buildJsonObject {
                    put("cachedContent", "cachedContents/abc")
                    put("threshold", "BLOCK_ONLY_HIGH")
                    put("mediaResolution", "MEDIA_RESOLUTION_LOW")
                    putJsonArray("responseModalities") { add(JsonPrimitive("TEXT")); add(JsonPrimitive("IMAGE")) }
                    putJsonObject("labels") { put("team", "search") }
                    putJsonObject("imageConfig") { put("aspectRatio", "16:9") }
                },
            ),
        )

        val (body, _) = send(options)

        assertEquals("cachedContents/abc", body["cachedContent"].string())
        assertEquals("search", body.obj("labels")!!["team"].string())
        val config = body.obj("generationConfig")!!
        assertEquals("MEDIA_RESOLUTION_LOW", config["mediaResolution"].string())
        assertEquals(listOf("TEXT", "IMAGE"), config.arr("responseModalities")!!.map { it.string() })
        assertEquals("16:9", config.obj("imageConfig")!!["aspectRatio"].string())
        // The shorthand expands: a caller writing one threshold means it for every category, and
        // spelling out five objects is how one of them gets forgotten.
        val safety = body.arr("safetySettings")!!.map { it.jsonObject }
        assertEquals(5, safety.size)
        assertTrue(safety.all { it["threshold"].string() == "BLOCK_ONLY_HIGH" })
    }

    @Test
    fun `caller headers reach the vendor`() = runTest {
        val server = TestServer(finished)
        GoogleLanguageModel(
            modelId = "gemini-3-pro",
            http = server.http(),
            headers = { mapOf("x-goog-api-key" to "k") },
        ).doStream(call().copy(headers = mapOf("x-trace-id" to "t-1"))).stream.toList()

        server.request().assertHeader("x-goog-api-key", "k")
        server.request().assertHeader("x-trace-id", "t-1")
    }

    // --- finish reason --------------------------------------------------------------------------

    @Test
    fun `a turn that IS a tool call finishes as tool-calls, not stop`() = runTest {
        val server = TestServer(
            TestServer.sse(
                """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"f","args":{}}}]}}]}""" +
                    "\n\n" +
                    """data: {"candidates":[{"finishReason":"STOP"}]}""" + "\n\n",
            ),
        )

        val finish = GoogleLanguageModel(modelId = "gemini-3-pro", http = server.http())
            .doStream(call()).stream.toList()
            .filterIsInstance<StreamPart.Finish>().single()

        // Gemini has no `tool-calls` value on the wire. Reading `STOP` literally stopped every agent loop
        // one round before it ran the tool the model had just asked for.
        assertEquals(
            com.sabreware.aide.aisdk.FinishReason.Unified.ToolCalls,
            finish.finishReason.unified,
        )
        assertEquals("STOP", finish.finishReason.raw)
    }

    @Test
    fun `a forced tool still pins one name`() = runTest {
        val (body, _) = send(
            call().copy(
                tools = listOf(Tool.Function("pick_me", buildJsonObject { put("type", "object") })),
                toolChoice = ToolChoice.Specific("pick_me"),
            ),
        )

        val config = body.obj("toolConfig", "functionCallingConfig")!!
        assertEquals("ANY", config["mode"].string())
        assertEquals(listOf("pick_me"), config.arr("allowedFunctionNames")!!.map { it.string() })
    }
}
