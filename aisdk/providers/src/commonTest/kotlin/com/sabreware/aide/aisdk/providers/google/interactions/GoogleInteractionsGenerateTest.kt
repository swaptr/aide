package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `doGenerate` against every recorded `.json` fixture.
 *
 * Each case asserts what the reference's inline snapshot asserts — the mapped content, the finish
 * reason, the usage, the provider metadata — and the request body that produced it, so a mapping that
 * drifts from Google's recorded wire fails here rather than against the live service.
 */
class GoogleInteractionsGenerateTest {

    private fun jsonServer(body: String) = TestServer(TestServer.json(body))

    private fun usageOf(body: String): JsonObject = parseJsonObject(body)["usage"]!!.jsonObject

    @Test
    fun `basic - content, finish reason, usage, warnings and provider metadata`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.BASIC_JSON)
        val result = interactionsModel(server).doGenerate(CallOptions(TEST_PROMPT))

        val id = "v1_ChdTbXNIYXFyUEV0ZUttdGtQNXVqVHdRRRIXU21zSGFxclBFdGVLbXRrUDV1alR3UUU"
        assertEquals(
            listOf(
                Content.Reasoning("", googleMeta("signature" to BASIC_SIGNATURE, "interactionId" to id)),
                Content.Text(
                    "Hello! I'm doing well, thank you for asking.\n\nHow are you today?",
                    googleMeta("interactionId" to id),
                ),
            ),
            result.content,
        )
        assertEquals(FinishReason(FinishReason.Unified.Stop, "completed"), result.finishReason)
        assertEquals(
            Usage(
                inputTokens = Usage.InputTokens(total = 7, noCache = 7, cacheRead = 0),
                outputTokens = Usage.OutputTokens(total = 51, text = 19, reasoning = 32),
                raw = usageOf(GoogleInteractionsFixtures.BASIC_JSON),
            ),
            result.usage,
        )
        result.warnings.assertNoWarnings()
        assertEquals(googleMeta("interactionId" to id, "serviceTier" to "standard"), result.providerMetadata)

        assertEquals(id, result.response?.metadata?.id)
        assertEquals("gemini-2.5-flash", result.response?.metadata?.modelId)
        assertEquals(1_778_871_115_000, result.response?.metadata?.timestamp)
        assertTrue(result.response?.body!!.contains("\"id\":\"v1_"))
    }

    @Test
    fun `basic - the request body, URL, method and headers`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.BASIC_JSON)
        val result = interactionsModel(server).doGenerate(CallOptions(TEST_PROMPT))

        val request = server.request()
        assertEquals("POST", request.method)
        assertEquals(INTERACTIONS_URL, request.url)
        request.assertHeader("x-goog-api-key", "test-api-key")
        request.assertNoHeader("api-revision")
        request.assertBodyEquals(
            """{"input":[{"content":[{"text":"Hello, how are you?","type":"text"}],"type":"user_input"}],""" +
                """"model":"gemini-2.5-flash"}""",
        )
        assertEquals(request.bodyJson(), parseJsonObject(result.request!!.body!!))
    }

    @Test
    fun `an uploaded text document rides as a document block with its Files API URI`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.BASIC_JSON)
        val result = interactionsModel(server, GoogleInteractionsTarget.Model("gemini-3.5-flash")).doGenerate(
            CallOptions(
                listOf(
                    ModelMessage.User(
                        listOf(
                            UserPart.Text("Return only the secret verification code from the attached text document."),
                            UserPart.File(
                                data = FileData.Reference(
                                    mapOf("google" to "https://generativelanguage.googleapis.com/v1beta/files/gzed1s6hqcsn"),
                                ),
                                mediaType = "text/plain",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val input = server.request().bodyJson().arr("input")!!.single().jsonObject
        assertEquals("user_input", input["type"].string())
        val blocks = input.arr("content")!!.map { it.jsonObject }
        assertEquals("text", blocks[0]["type"].string())
        assertEquals(
            buildJsonObject {
                put("type", "document")
                put("uri", "https://generativelanguage.googleapis.com/v1beta/files/gzed1s6hqcsn")
                put("mime_type", "text/plain")
            },
            blocks[1],
        )
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `a multi-turn prompt is a step per turn with the system message hoisted`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.BASIC_JSON)
        interactionsModel(server).doGenerate(
            CallOptions(
                listOf(
                    ModelMessage.System("You are a helpful assistant."),
                    ModelMessage.User(listOf(UserPart.Text("Hi"))),
                    ModelMessage.Assistant(listOf(AssistantPart.Text("Hello!"))),
                    ModelMessage.User(listOf(UserPart.Text("How are you?"))),
                ),
            ),
        )
        server.request().assertBodyEquals(
            """{"input":[""" +
                """{"content":[{"text":"Hi","type":"text"}],"type":"user_input"},""" +
                """{"content":[{"text":"Hello!","type":"text"}],"type":"model_output"},""" +
                """{"content":[{"text":"How are you?","type":"text"}],"type":"user_input"}],""" +
                """"model":"gemini-2.5-flash","system_instruction":"You are a helpful assistant."}""",
        )
    }

    // --- structured output -----------------------------------------------------------------------

    @Test
    fun `structured output - a JSON response format is a text entry with the schema, and the text parses`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.STRUCTURED_OUTPUT_JSON)
        val result = interactionsModel(server).doGenerate(
            CallOptions(TEST_PROMPT, responseFormat = ResponseFormat.Json(schema = PERSON_SCHEMA)),
        )

        val body = server.request().bodyJson()
        assertNull(body["response_mime_type"])
        assertEquals(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("mime_type", "application/json")
                        put("schema", PERSON_SCHEMA)
                    },
                )
            },
            body["response_format"],
        )

        val id = "v1_ChdUV3NIYW9LTk9OYXlxdHNQb2RpcC1RRRIXVFdzSGFvS05PTmF5cXRzUG9kaXAtUUU"
        assertEquals(
            listOf(
                Content.Reasoning("", googleMeta("signature" to STRUCTURED_SIGNATURE, "interactionId" to id)),
                Content.Text("""{"name":"John Doe","age":30}""", googleMeta("interactionId" to id)),
            ),
            result.content,
        )
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `structured output - JSON without a schema is a bare application json entry, text sends none`() = runTest {
        val server = TestServer(
            TestServer.json(GoogleInteractionsFixtures.STRUCTURED_OUTPUT_JSON),
            TestServer.json(GoogleInteractionsFixtures.STRUCTURED_OUTPUT_JSON),
        )
        val model = interactionsModel(server)
        model.doGenerate(CallOptions(TEST_PROMPT, responseFormat = ResponseFormat.Json()))
        model.doGenerate(CallOptions(TEST_PROMPT, responseFormat = ResponseFormat.Text))

        assertEquals(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("mime_type", "application/json")
                    },
                )
            },
            server.request(0).bodyJson()["response_format"],
        )
        server.request(1).assertBodyMissing("response_format")
        server.request(1).assertBodyMissing("response_mime_type")
    }

    // --- tool calling ----------------------------------------------------------------------------

    @Test
    fun `tool call step 1 - the tools go out as function entries with the JSON Schema untouched`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.TOOL_CALL_STEP1_JSON)
        interactionsModel(server).doGenerate(CallOptions(TEST_PROMPT, tools = listOf(WEATHER_TOOL)))
        server.request().assertBodyEquals(
            """{"input":[{"content":[{"text":"Hello, how are you?","type":"text"}],"type":"user_input"}],""" +
                """"model":"gemini-2.5-flash","tools":[{"description":"Get the current weather in a location",""" +
                """"name":"getWeather","parameters":{"properties":{"location":{"description":""" +
                """"The location to get the weather for","type":"string"}},"required":["location"],""" +
                """"type":"object"},"type":"function"}]}""",
        )
    }

    @Test
    fun `tool call step 1 - a tool choice rides generation_config`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.TOOL_CALL_STEP1_JSON)
        interactionsModel(server).doGenerate(
            CallOptions(TEST_PROMPT, tools = listOf(WEATHER_TOOL), toolChoice = ToolChoice.Required),
        )
        assertEquals("any", server.request().bodyJson().obj("generation_config")!!["tool_choice"].string())
    }

    @Test
    fun `tool call step 1 - a function_call step is a tool call and the reason is tool-calls`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.TOOL_CALL_STEP1_JSON)
        val result = interactionsModel(server).doGenerate(CallOptions(TEST_PROMPT, tools = listOf(WEATHER_TOOL)))

        assertEquals(FinishReason(FinishReason.Unified.ToolCalls, "requires_action"), result.finishReason)
        assertEquals(
            listOf(
                Content.ToolCall(
                    toolCallId = "zggxzq8r",
                    toolName = "getWeather",
                    input = """{"location":"San Francisco"}""",
                    providerMetadata = googleMeta(
                        "interactionId" to "v1_ChdUMnNIYXVxU0lJX2lxdHNQX2FicXVBWRIXVDJzSGF1cVNJSV9pcXRzUF9hYnF1QVk",
                    ),
                ),
            ),
            result.content.filterIsInstance<Content.ToolCall>(),
        )
    }

    /** Round one to round two: the call, its signature and its result all go back on the wire. */
    @Test
    fun `tool call step 2 - the assistant call and the tool result replay as steps`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.TOOL_CALL_STEP2_JSON)
        val result = interactionsModel(server).doGenerate(
            CallOptions(
                prompt = listOf(
                    ModelMessage.User(listOf(UserPart.Text("What is the weather in San Francisco right now?"))),
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.Reasoning("", providerOptions = googleMeta("signature" to "sig-abc")),
                            AssistantPart.ToolCall("r7b1dyif", "getWeather", """{"location":"San Francisco"}"""),
                        ),
                    ),
                    ModelMessage.Tool(
                        listOf(
                            ToolPart.Result(
                                "r7b1dyif",
                                "getWeather",
                                ToolOutput.Json(
                                    parseJsonObject("""{"location":"San Francisco","condition":"sunny","temperature":16}"""),
                                ),
                            ),
                        ),
                    ),
                ),
                tools = listOf(WEATHER_TOOL),
            ),
        )

        server.request().assertBodyEquals(
            """{"input":[""" +
                """{"content":[{"text":"What is the weather in San Francisco right now?","type":"text"}],""" +
                """"type":"user_input"},""" +
                """{"signature":"sig-abc","type":"thought"},""" +
                """{"arguments":{"location":"San Francisco"},"id":"r7b1dyif","name":"getWeather","type":"function_call"},""" +
                """{"content":[{"call_id":"r7b1dyif","name":"getWeather",""" +
                """"result":"{\"location\":\"San Francisco\",\"condition\":\"sunny\",\"temperature\":16}",""" +
                """"type":"function_result"}],"type":"user_input"}],""" +
                """"model":"gemini-2.5-flash","tools":[{"description":"Get the current weather in a location",""" +
                """"name":"getWeather","parameters":{"properties":{"location":{"description":""" +
                """"The location to get the weather for","type":"string"}},"required":["location"],""" +
                """"type":"object"},"type":"function"}]}""",
        )
        assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)
        assertTrue(result.content.filterIsInstance<Content.Text>().joinToString("") { it.text }.contains("San Francisco"))
    }

    // --- built-in google_search ------------------------------------------------------------------

    @Test
    fun `google search - the built-in tool is one typed entry and the run reports its call, result and sources`() =
        runTest {
            val server = jsonServer(GoogleInteractionsFixtures.GOOGLE_SEARCH_JSON)
            val result = interactionsModel(server).doGenerate(CallOptions(TEST_PROMPT, tools = listOf(GOOGLE_SEARCH_TOOL)))

            assertEquals(buildJsonArray { add(buildJsonObject { put("type", "google_search") }) }, server.request().bodyJson()["tools"])

            val call = result.content.filterIsInstance<Content.ToolCall>().single()
            assertEquals("google_search", call.toolName)
            assertTrue(call.providerExecuted)
            assertEquals("3tz1p6wn", call.toolCallId)
            val toolResult = result.content.filterIsInstance<Content.ToolResult>().single()
            assertEquals("google_search", toolResult.toolName)
            assertEquals("3tz1p6wn", toolResult.toolCallId)

            // Eighteen annotations cite four pages; the citations are what surface, once each.
            val urls = result.content.filterIsInstance<Content.Source.Url>().map { it.url }
            assertEquals(4, urls.size)
            assertEquals(urls.toSet().size, urls.size)
            urls.forEach { assertTrue(it.startsWith("https://"), it) }
            assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)
        }

    // --- stateful chaining -----------------------------------------------------------------------

    @Test
    fun `stateful turn 1 - the interaction id comes back on the result metadata`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.MULTI_TURN_STATEFUL_TURN1_JSON)
        val result = interactionsModel(server).doGenerate(
            CallOptions(userPrompt("What are the three largest cities in Spain?")),
        )
        assertEquals(STATEFUL_TURN_1_ID, result.providerMetadata?.get("google")?.get("interactionId").string())
        val text = result.content.filterIsInstance<Content.Text>().joinToString("") { it.text }
        assertTrue(text.contains("Madrid") && text.contains("Barcelona") && text.contains("Valencia"), text)
    }

    /** The whole point of stateful mode: turn two sends only what the server does not already hold. */
    @Test
    fun `stateful turn 2 - the prior assistant turn is compacted out and the prior interaction named`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.MULTI_TURN_STATEFUL_TURN2_JSON)
        val result = interactionsModel(server).doGenerate(
            CallOptions(
                prompt = listOf(
                    ModelMessage.User(listOf(UserPart.Text("What are the three largest cities in Spain?"))),
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.Reasoning(
                                "",
                                providerOptions = googleMeta(
                                    "interactionId" to STATEFUL_TURN_1_ID,
                                    "signature" to "thought-sig-from-turn-1",
                                ),
                            ),
                            AssistantPart.Text(
                                "The three largest cities in Spain are Madrid, Barcelona, and Valencia.",
                                providerOptions = googleMeta("interactionId" to STATEFUL_TURN_1_ID),
                            ),
                        ),
                    ),
                    ModelMessage.User(listOf(UserPart.Text("What is the most famous landmark in the second one?"))),
                ),
                providerOptions = googleOptions { put("previousInteractionId", STATEFUL_TURN_1_ID) },
            ),
        )

        val body = server.request().bodyJson()
        assertEquals(STATEFUL_TURN_1_ID, body["previous_interaction_id"].string())
        assertEquals(
            buildJsonArray {
                add(userInputStep("What are the three largest cities in Spain?"))
                add(userInputStep("What is the most famous landmark in the second one?"))
            },
            body["input"],
        )
        val text = result.content.filterIsInstance<Content.Text>().joinToString("") { it.text }
        assertTrue(text.contains("Barcelona"), text)
        assertTrue(Regex("Sagrada Fam.lia").containsMatchIn(text), text)
    }

    // --- stateless multi-turn --------------------------------------------------------------------

    @Test
    fun `stateless turn 1 - store false, no previous id, and no interaction id on the result`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.MULTI_TURN_STATELESS_TURN1_JSON)
        val result = interactionsModel(server).doGenerate(
            CallOptions(
                userPrompt("What are the three largest cities in Spain?"),
                providerOptions = googleOptions { put("store", false) },
            ),
        )

        val body = server.request().bodyJson()
        assertEquals(false, body["store"].string()?.toBooleanStrictOrNull())
        assertNull(body["previous_interaction_id"])
        assertEquals(buildJsonArray { add(userInputStep("What are the three largest cities in Spain?")) }, body["input"])
        result.warnings.assertNoWarnings()
        // The API omits `id` on a stateless call, so nothing is surfaced as one.
        assertNull(result.providerMetadata?.get("google")?.get("interactionId"))
        assertEquals(mapOf("google" to buildJsonObject { put("serviceTier", "standard") }), result.providerMetadata)
        val text = result.content.filterIsInstance<Content.Text>().joinToString("") { it.text }
        assertTrue(text.contains("Madrid") && text.contains("Barcelona") && text.contains("Valencia"), text)
    }

    @Test
    fun `stateless turn 2 - the full history goes out verbatim, nothing compacted`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.MULTI_TURN_STATELESS_TURN2_JSON)
        val result = interactionsModel(server).doGenerate(
            CallOptions(
                prompt = listOf(
                    ModelMessage.User(listOf(UserPart.Text("What are the three largest cities in Spain?"))),
                    ModelMessage.Assistant(
                        listOf(AssistantPart.Text("The three largest cities in Spain are Madrid, Barcelona, and Valencia.")),
                    ),
                    ModelMessage.User(listOf(UserPart.Text("What is the most famous landmark in the second one?"))),
                ),
                providerOptions = googleOptions { put("store", false) },
            ),
        )

        val body = server.request().bodyJson()
        assertEquals("false", body["store"].string())
        assertNull(body["previous_interaction_id"])
        assertEquals(
            buildJsonArray {
                add(userInputStep("What are the three largest cities in Spain?"))
                add(
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("text", "The three largest cities in Spain are Madrid, Barcelona, and Valencia.")
                                        put("type", "text")
                                    },
                                )
                            },
                        )
                        put("type", "model_output")
                    },
                )
                add(userInputStep("What is the most famous landmark in the second one?"))
            },
            body["input"],
        )
        result.warnings.assertNoWarnings()
        val text = result.content.filterIsInstance<Content.Text>().joinToString("") { it.text }
        assertTrue(Regex("Sagrada Fam.lia").containsMatchIn(text), text)
    }

    // --- image output ----------------------------------------------------------------------------

    @Test
    fun `image output - an image block is a file part with decoded bytes and the interaction id`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.IMAGE_OUTPUT_JSON)
        val result = interactionsModel(server, GoogleInteractionsTarget.Model("gemini-3-pro-image-preview")).doGenerate(
            CallOptions(
                userPrompt("Generate an image of a comic cat in a spaceship."),
                providerOptions = googleOptions { put("responseModalities", buildJsonArray { add("image") }) },
            ),
        )

        val file = result.content.filterIsInstance<Content.File>().single()
        assertEquals("image/jpeg", file.mediaType)
        val bytes = assertIs<FileData.Bytes>(file.data)
        assertTrue(bytes.bytes.isNotEmpty())
        // The stand-in payload is the fixture's own first bytes: a JPEG start-of-image marker.
        assertEquals(0xFF.toByte(), bytes.bytes[0])
        assertEquals(0xD8.toByte(), bytes.bytes[1])
        assertTrue(file.providerMetadata?.get("google")?.get("interactionId").string()!!.startsWith("v1_"))

        assertEquals(buildJsonArray { add("image") }, server.request().bodyJson()["response_modalities"])
        assertEquals(mapOf("image" to 1120), result.providerMetadata.outputTokensByModality())
    }

    @Test
    fun `image output modify - turn 2 carries the previous interaction id and yields the new image`() = runTest {
        val server = jsonServer(GoogleInteractionsFixtures.IMAGE_OUTPUT_MODIFY_JSON)
        val result = interactionsModel(server, GoogleInteractionsTarget.Model("gemini-3-pro-image-preview")).doGenerate(
            CallOptions(
                userPrompt("now make the cat red"),
                providerOptions = googleOptions {
                    put("responseModalities", buildJsonArray { add("image") })
                    put("previousInteractionId", "v1_prev-turn")
                },
            ),
        )
        val file = result.content.filterIsInstance<Content.File>().single()
        assertEquals("image/jpeg", file.mediaType)
        assertIs<FileData.Bytes>(file.data)
        assertEquals("v1_prev-turn", server.request().bodyJson()["previous_interaction_id"].string())
    }

    // --- service tier and usage breakdown --------------------------------------------------------

    @Test
    fun `service tier - the body wins, the header is the fallback, and neither means none`() = runTest {
        val withoutTier = buildJsonObject {
            parseJsonObject(GoogleInteractionsFixtures.BASIC_JSON).forEach { (key, value) ->
                if (key != "service_tier") put(key, value)
            }
        }.toString()
        val server = TestServer(
            TestServer.json(GoogleInteractionsFixtures.BASIC_JSON).withHeaders("x-gemini-service-tier" to "priority"),
            TestServer.json(withoutTier).withHeaders("x-gemini-service-tier" to "priority"),
            TestServer.json(withoutTier),
        )
        val model = interactionsModel(server)

        val fromBody = model.doGenerate(CallOptions(TEST_PROMPT))
        val fromHeader = model.doGenerate(CallOptions(TEST_PROMPT))
        val absent = model.doGenerate(CallOptions(TEST_PROMPT))

        assertEquals("standard", fromBody.providerMetadata?.get("google")?.get("serviceTier").string())
        assertEquals("priority", fromHeader.providerMetadata?.get("google")?.get("serviceTier").string())
        assertNull(absent.providerMetadata?.get("google")?.get("serviceTier"))
    }

    @Test
    fun `output tokens by modality surface on the metadata only when reported`() = runTest {
        val fixture = parseJsonObject(GoogleInteractionsFixtures.BASIC_JSON)
        val withBreakdown = buildJsonObject {
            fixture.forEach { (key, value) -> if (key != "usage") put(key, value) }
            putJsonObject("usage") {
                fixture["usage"]!!.jsonObject.forEach { (key, value) -> put(key, value) }
                put(
                    "output_tokens_by_modality",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("modality", "video")
                                put("tokens", 57_920)
                            },
                        )
                        add(
                            buildJsonObject {
                                put("modality", "text")
                                put("tokens", 19)
                            },
                        )
                    },
                )
            }
        }.toString()
        val server = TestServer(TestServer.json(withBreakdown), TestServer.json(GoogleInteractionsFixtures.BASIC_JSON))
        val model = interactionsModel(server)

        val reported = model.doGenerate(CallOptions(TEST_PROMPT))
        val plain = model.doGenerate(CallOptions(TEST_PROMPT))

        assertEquals(mapOf("video" to 57_920, "text" to 19), reported.providerMetadata.outputTokensByModality())
        assertNull(plain.providerMetadata?.get("google")?.get("outputTokensByModality"))
    }

    private fun Map<String, JsonObject>?.outputTokensByModality(): Map<String, Int>? =
        this?.get("google")?.get("outputTokensByModality")?.jsonObject
            ?.mapValues { (_, value) -> assertNotNull(value.string()).toInt() }

    private fun userInputStep(text: String): JsonObject = buildJsonObject {
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("text", text)
                        put("type", "text")
                    },
                )
            },
        )
        put("type", "user_input")
    }

    private companion object {
        const val STATEFUL_TURN_1_ID = "v1_ChdWV3NIYXNYZEc5S19xdHNQcmVYeG1BRRIXVldzSGFzWGRHOUtfcXRzUHJlWHhtQUU"

        const val BASIC_SIGNATURE = "CqwBAQw51sfgKVnBHSz5praTe+uG0Anr7XQqpCF63u254O4l2U4+GL3n7WRshuMFMfTty31n/76lM81J" +
            "lqplsBd+YnEEPdyYqh4RpVrMnvUgDP7rkuWFPutrEgLUU/r3LuD3z1dc3qiMjtw3r3RkXtdHNF2Om28zmRoMT0/u8yNkPJA7S2IE" +
            "fzasBN5yobkpwgbPAQ73PDJZy8n0qjNQmSG/OCMCUDBZMH3C9A1rEg=="

        const val STRUCTURED_SIGNATURE = "CqACAQw51sc7wSan2PLLtHG+z0j4AgWR8MPPe76QUDZpwhGeQ1AGXBWGqSm7nhjwbkyXhJ7Jlf" +
            "Z/R3iLZAmqxGH9mLrdkzQooTu5YKptE0+D1jb+PLv7PM/pkCeI8E2IUDtamWiQP2/eG3Rgouxd7+kNxcaERwGTLFsGMdZttew0aP8+" +
            "xd0hKPAZVkkqKtjVVNYQaSVsnXpTxZrE+spikYx1T/lB9tl6tYkJrB/SmkGO/tsTqDVsaejBXheSM+pu/Nt7s9gAhiNxJnoTvFENez" +
            "mmiGwiho1lkkPrK8u5uODO5MldRXefJnv8mkZAw/3l6Cxd63hKPGP5za6sjFr/0Scsv0LcrOU65e+YEqZOkp7OXcCoqTCZZITx89Jj" +
            "kOA/pN1T"

        val PERSON_SCHEMA = parseJsonObject(
            """{"type":"object","properties":{"name":{"type":"string","description":"Full name of the person."},""" +
                """"age":{"type":"number","description":"Age of the person in years."}},"required":["name","age"],""" +
                """"additionalProperties":false,"${'$'}schema":"http://json-schema.org/draft-07/schema#"}""",
        )
    }
}
