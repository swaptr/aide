package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonElement
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `doStream` against every recorded `.chunks.txt` fixture.
 *
 * The `basic` case pins the WHOLE part sequence — ids, order, where the signature and the interaction
 * id land — because that sequence is the contract a runtime replays a turn from. The rest assert what
 * the reference's own stream tests assert.
 */
@OptIn(ExperimentalEncodingApi::class)
class GoogleInteractionsStreamTest {

    private suspend fun stream(
        server: TestServer,
        options: CallOptions,
        target: GoogleInteractionsTarget = GoogleInteractionsTarget.Model(TEST_MODEL),
    ): List<StreamPart> = interactionsModel(server, target).doStream(options).stream.toList()

    private fun completedUsage(chunks: List<String>): JsonObject =
        parseJsonObject(chunks.last())["interaction"]!!.jsonObject["usage"]!!.jsonObject

    private fun List<StreamPart>.text(): String = filterIsInstance<StreamPart.TextDelta>().joinToString("") { it.delta }

    private fun List<StreamPart>.finish(): StreamPart.Finish = filterIsInstance<StreamPart.Finish>().single()

    @Test
    fun `agentic video processing steps stream as custom parts`() = runTest {
        // "emits processing steps as custom parts".
        val server = TestServer(
            sseOf(
                listOf(
                    """{"event_type":"interaction.created","interaction":{"id":"interaction-1","status":"in_progress"}}""",
                    """{"event_type":"step.start","index":0,"step":{"type":"processing_call","id":"processing-1","signature":"call-signature"}}""",
                    """{"event_type":"step.stop","index":0}""",
                    """{"event_type":"step.start","index":1,"step":{"type":"processing_result","call_id":"processing-1","signature":"result-signature"}}""",
                    """{"event_type":"step.stop","index":1}""",
                    """{"event_type":"interaction.completed","interaction":{"id":"interaction-1","status":"completed"}}""",
                ),
            ),
        )

        val parts = stream(server, CallOptions(TEST_PROMPT))

        assertEquals(
            listOf(
                Content.Custom(
                    "google.processing_call",
                    googleMeta("signature" to "call-signature", "interactionId" to "interaction-1", "processingId" to "processing-1"),
                ),
                Content.Custom(
                    "google.processing_result",
                    googleMeta("signature" to "result-signature", "interactionId" to "interaction-1", "processingCallId" to "processing-1"),
                ),
            ),
            parts.filterIsInstance<StreamPart.CustomPart>().map { it.custom },
        )
    }

    @Test
    fun `video response format controls are re-spelled onto the wire and inline video streams back`() = runTest {
        // "serializes video response format controls and streams inline video data".
        val server = TestServer(
            sseOf(
                listOf(
                    """{"event_type":"interaction.created","interaction":{"id":"v1_video","status":"in_progress","model":"gemini-omni-flash-preview"}}""",
                    """{"event_type":"step.start","index":0,"step":{"type":"model_output"}}""",
                    """{"event_type":"step.delta","index":0,"delta":{"type":"video","mime_type":"video/mp4","data":"AAAAIGZ0eXBpc29t"}}""",
                    """{"event_type":"step.stop","index":0}""",
                    """{"event_type":"interaction.completed","interaction":{"id":"v1_video","status":"completed"}}""",
                ),
            ),
        )

        val parts = stream(
            server,
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    putJsonArray("responseModalities") { add("video") }
                    putJsonArray("responseFormat") {
                        add(
                            buildJsonObject {
                                put("type", "video")
                                put("aspectRatio", "9:16")
                                put("resolution", "4k")
                                put("duration", "8s")
                                put("delivery", "inline")
                            },
                        )
                    }
                },
            ),
        )

        assertEquals(
            parseJsonElement("""[{"type":"video","aspect_ratio":"9:16","resolution":"4k","duration":"8s","delivery":"inline"}]"""),
            server.request().bodyJson()["response_format"],
        )
        val file = parts.filterIsInstance<StreamPart.FilePart>().single().file
        assertEquals("video/mp4", file.mediaType)
        assertEquals(FileData.Bytes(Base64.decode("AAAAIGZ0eXBpc29t")), file.data)
    }

    @Test
    fun `basic - the whole part sequence`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.BASIC_CHUNKS))
        val parts = stream(server, CallOptions(TEST_PROMPT))

        val id = "v1_ChdUR3NIYXVyQkFlYVA2ZGtQajZERThBVRIXVEdzSGF1ckJBZWFQNmRrUGo2REU4QVU"
        assertEquals(
            listOf(
                StreamPart.StreamStart(emptyList()),
                StreamPart.ResponseMetadataPart(ResponseMetadata(id = id, modelId = "gemini-2.5-flash")),
                StreamPart.ReasoningStart("$id:0"),
                StreamPart.ReasoningEnd("$id:0", googleMeta("signature" to BASIC_STREAM_SIGNATURE, "interactionId" to id)),
                StreamPart.TextStart("$id:1"),
                StreamPart.TextDelta(
                    "$id:1",
                    "I'm doing great, thank you for asking!\n\nHow are you doing today? And what can I do for you?",
                ),
                StreamPart.TextEnd("$id:1", googleMeta("interactionId" to id)),
                StreamPart.Finish(
                    usage = Usage(
                        inputTokens = Usage.InputTokens(total = 7, noCache = 7, cacheRead = 0),
                        outputTokens = Usage.OutputTokens(total = 141, text = 26, reasoning = 115),
                        raw = completedUsage(GoogleInteractionsFixtures.BASIC_CHUNKS),
                    ),
                    finishReason = FinishReason(FinishReason.Unified.Stop, "completed"),
                    providerMetadata = googleMeta("interactionId" to id, "serviceTier" to "standard"),
                ),
            ),
            parts,
        )
        // The stream flag rides the body; everything else is the plain request.
        server.request().assertBodyEquals(
            """{"input":[{"content":[{"text":"Hello, how are you?","type":"text"}],"type":"user_input"}],""" +
                """"model":"gemini-2.5-flash","stream":true}""",
        )
        server.request().assertHeader("Accept", "text/event-stream")
    }

    @Test
    fun `basic - raw chunks are emitted on request, one per frame`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.BASIC_CHUNKS))
        val parts = stream(server, CallOptions(TEST_PROMPT, includeRawChunks = true))
        assertEquals(GoogleInteractionsFixtures.BASIC_CHUNKS.size, parts.filterIsInstance<StreamPart.Raw>().size)
        val quiet = stream(TestServer(sseOf(GoogleInteractionsFixtures.BASIC_CHUNKS)), CallOptions(TEST_PROMPT))
        assertTrue(quiet.none { it is StreamPart.Raw })
    }

    @Test
    fun `service tier - the completed event wins, the header is the fallback, neither means none`() = runTest {
        val fromBody = stream(
            TestServer(sseOf(withServiceTier(GoogleInteractionsFixtures.BASIC_CHUNKS, "priority"))),
            CallOptions(TEST_PROMPT),
        )
        assertEquals("priority", fromBody.finish().providerMetadata?.get("google")?.get("serviceTier").string())

        val fromHeader = stream(
            TestServer(
                sseOf(withServiceTier(GoogleInteractionsFixtures.BASIC_CHUNKS, null))
                    .withHeaders("x-gemini-service-tier" to "priority"),
            ),
            CallOptions(TEST_PROMPT),
        )
        assertEquals("priority", fromHeader.finish().providerMetadata?.get("google")?.get("serviceTier").string())

        val absent = stream(
            TestServer(sseOf(withServiceTier(GoogleInteractionsFixtures.BASIC_CHUNKS, null))),
            CallOptions(TEST_PROMPT),
        )
        assertNull(absent.finish().providerMetadata?.get("google")?.get("serviceTier"))

        val sent = TestServer(sseOf(GoogleInteractionsFixtures.BASIC_CHUNKS))
        stream(sent, CallOptions(TEST_PROMPT, providerOptions = googleOptions { put("serviceTier", "priority") }))
        assertEquals("priority", sent.request().bodyJson()["service_tier"].string())
    }

    // --- stateful chaining -----------------------------------------------------------------------

    @Test
    fun `stateful turn 1 - reasoning then text, and the interaction id on the finish`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.MULTI_TURN_STATEFUL_TURN1_CHUNKS))
        val parts = stream(server, CallOptions(userPrompt("What are the three largest cities in Spain?")))

        val id = "v1_ChdYR3NIYXB6dkJzTy1xdHNQcTR5RDZRVRIXWEdzSGFwenZCc08tcXRzUHE0eUQ2UVU"
        assertEquals(
            listOf("StreamStart", "ResponseMetadataPart", "ReasoningStart", "ReasoningEnd", "TextStart", "TextDelta", "TextDelta", "TextEnd", "Finish"),
            parts.map { it::class.simpleName },
        )
        assertEquals(id, parts.finish().providerMetadata?.get("google")?.get("interactionId").string())
        assertTrue(parts.text().contains("Valencia"))
    }

    @Test
    fun `stateful turn 2 - the compacted body and the chained answer`() = runTest {
        val turn1 = "v1_ChdYR3NIYXB6dkJzTy1xdHNQcTR5RDZRVRIXWEdzSGFwenZCc08tcXRzUHE0eUQ2UVU"
        val server = TestServer(sseOf(GoogleInteractionsFixtures.MULTI_TURN_STATEFUL_TURN2_CHUNKS))
        val parts = stream(
            server,
            CallOptions(
                prompt = listOf(
                    ModelMessage.User(listOf(UserPart.Text("What are the three largest cities in Spain?"))),
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.Text(
                                "The three largest cities in Spain are Madrid, Barcelona, and Valencia.",
                                providerOptions = googleMeta("interactionId" to turn1),
                            ),
                        ),
                    ),
                    ModelMessage.User(listOf(UserPart.Text("What is the most famous landmark in the second one?"))),
                ),
                providerOptions = googleOptions { put("previousInteractionId", turn1) },
            ),
        )
        val body = server.request().bodyJson()
        assertEquals(turn1, body["previous_interaction_id"].string())
        assertEquals(
            parseJsonElement(
                """[{"content":[{"text":"What are the three largest cities in Spain?","type":"text"}],"type":"user_input"},""" +
                    """{"content":[{"text":"What is the most famous landmark in the second one?","type":"text"}],""" +
                    """"type":"user_input"}]""",
            ),
            body["input"],
        )
        assertTrue(parts.text().contains("Sagrada Familia"))
    }

    // --- stateless multi-turn --------------------------------------------------------------------

    @Test
    fun `stateless turn 1 - an empty id on the wire is no id at all`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.MULTI_TURN_STATELESS_TURN1_CHUNKS))
        val parts = stream(
            server,
            CallOptions(
                userPrompt("What are the three largest cities in Spain?"),
                providerOptions = googleOptions { put("store", false) },
            ),
        )
        assertEquals("false", server.request().bodyJson()["store"].string())
        assertNull(server.request().bodyJson()["previous_interaction_id"])
        // `id: ""` throughout the stream: nothing to chain on, so nothing is claimed.
        assertNull(parts.finish().providerMetadata?.get("google")?.get("interactionId"))
        assertEquals(ResponseMetadata(id = null, modelId = "gemini-2.5-flash"), parts.filterIsInstance<StreamPart.ResponseMetadataPart>().single().metadata)
        assertEquals("interaction:1", parts.filterIsInstance<StreamPart.TextStart>().single().id)
        assertNull(parts.filterIsInstance<StreamPart.TextEnd>().single().providerMetadata)
        assertTrue(parts.text().contains("Madrid"))
    }

    @Test
    fun `stateless turn 2 - the full history goes out and the answer streams`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.MULTI_TURN_STATELESS_TURN2_CHUNKS))
        val parts = stream(
            server,
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
        assertEquals(3, server.request().bodyJson()["input"]!!.let { (it as JsonArray).size })
        assertTrue(parts.text().contains("Sagrada Familia"))
    }

    // --- tool calling ----------------------------------------------------------------------------

    @Test
    fun `tool call step 1 - the input block, the call with its signature, and a tool-calls finish`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.TOOL_CALL_STEP1_CHUNKS))
        val parts = stream(server, CallOptions(TEST_PROMPT, tools = listOf(WEATHER_TOOL)))

        val id = "v1_ChdVbXNIYXVEUkVacmpxdHNQb3JQeXlBRRIXVW1zSGF1RFJFWnJqcXRzUG9yUHl5QUU"
        val interesting = parts.filter {
            it is StreamPart.ToolInputStart || it is StreamPart.ToolInputDelta || it is StreamPart.ToolInputEnd ||
                it is StreamPart.ToolCallPart || it is StreamPart.Finish
        }
        assertEquals(
            listOf(
                StreamPart.ToolInputStart("61nzpsv4", "getWeather"),
                StreamPart.ToolInputDelta("61nzpsv4", """{"location":"San Francisco"}"""),
                StreamPart.ToolInputEnd("61nzpsv4"),
                StreamPart.ToolCallPart(
                    Content.ToolCall(
                        toolCallId = "61nzpsv4",
                        toolName = "getWeather",
                        input = """{"location":"San Francisco"}""",
                        // The wire sent an EMPTY signature on the step; empty is what goes back.
                        providerMetadata = googleMeta("interactionId" to id, "signature" to ""),
                    ),
                ),
                StreamPart.Finish(
                    usage = Usage(
                        inputTokens = Usage.InputTokens(total = 53, noCache = 53, cacheRead = 0),
                        outputTokens = Usage.OutputTokens(total = 80, text = 15, reasoning = 65),
                        raw = completedUsage(GoogleInteractionsFixtures.TOOL_CALL_STEP1_CHUNKS),
                    ),
                    finishReason = FinishReason(FinishReason.Unified.ToolCalls, "requires_action"),
                    providerMetadata = googleMeta("interactionId" to id, "serviceTier" to "standard"),
                ),
            ),
            interesting,
        )
    }

    @Test
    fun `tool call step 2 - the answer after the result streams with a stop`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.TOOL_CALL_STEP2_CHUNKS))
        val parts = stream(server, CallOptions(TEST_PROMPT, tools = listOf(WEATHER_TOOL)))
        assertEquals(FinishReason.Unified.Stop, parts.finish().finishReason.unified)
        assertTrue(parts.text().contains("San Francisco"))
    }

    // --- built-in google_search ------------------------------------------------------------------

    @Test
    fun `google search - provider-executed call and result, sources once each, and a stop`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.GOOGLE_SEARCH_CHUNKS))
        val parts = stream(server, CallOptions(TEST_PROMPT, tools = listOf(GOOGLE_SEARCH_TOOL)))

        val call = parts.filterIsInstance<StreamPart.ToolCallPart>().single().toolCall
        assertEquals("google_search", call.toolName)
        assertEquals("7xveqyd2", call.toolCallId)
        assertTrue(call.providerExecuted)
        assertTrue(call.input.contains("queries"))
        val result = parts.filterIsInstance<StreamPart.ToolResultPart>().single().toolResult
        assertEquals("google_search", result.toolName)
        assertEquals("7xveqyd2", result.toolCallId)

        // Fourteen annotations, eight distinct pages: each page once, however often it was cited.
        val urls = parts.filterIsInstance<StreamPart.SourcePart>().map { it.source }.filterIsInstance<Content.Source.Url>().map { it.url }
        assertEquals(8, urls.size)
        assertEquals(urls.size, urls.toSet().size)
        urls.forEach { assertTrue(it.startsWith("https://"), it) }
        assertEquals(FinishReason.Unified.Stop, parts.finish().finishReason.unified)
    }

    // --- image output ----------------------------------------------------------------------------

    @Test
    fun `image output - the image delta is a file part with decoded bytes and the interaction id`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.IMAGE_OUTPUT_CHUNKS))
        val parts = stream(
            server,
            CallOptions(
                userPrompt("Generate an image of a comic cat in a spaceship."),
                providerOptions = googleOptions { put("responseModalities", buildJsonArray { add("image") }) },
            ),
            GoogleInteractionsTarget.Model("gemini-3-pro-image-preview"),
        )
        val file = parts.filterIsInstance<StreamPart.FilePart>().single().file
        assertEquals("image/jpeg", file.mediaType)
        assertTrue(assertIs<FileData.Bytes>(file.data).bytes.isNotEmpty())
        assertTrue(file.providerMetadata?.get("google")?.get("interactionId").string()!!.startsWith("v1_"))
        // A bare model_output that only ever carried an image opens no text block.
        assertTrue(parts.none { it is StreamPart.TextStart })
        assertEquals(mapOf("image" to 1120), parts.finish().providerMetadata?.get("google")?.get("outputTokensByModality")
            ?.jsonObject?.mapValues { (_, value) -> value.string()!!.toInt() })
    }

    @Test
    fun `image output modify - the turn 2 stream yields the new image`() = runTest {
        val server = TestServer(sseOf(GoogleInteractionsFixtures.IMAGE_OUTPUT_MODIFY_CHUNKS))
        val parts = stream(
            server,
            CallOptions(
                userPrompt("now make the cat red"),
                providerOptions = googleOptions {
                    put("responseModalities", buildJsonArray { add("image") })
                    put("previousInteractionId", "v1_prev-turn")
                },
            ),
            GoogleInteractionsTarget.Model("gemini-3-pro-image-preview"),
        )
        val file = parts.filterIsInstance<StreamPart.FilePart>().single().file
        assertEquals("image/jpeg", file.mediaType)
        assertIs<FileData.Bytes>(file.data)
        assertEquals("v1_prev-turn", server.request().bodyJson()["previous_interaction_id"].string())
    }

    // --- errors ----------------------------------------------------------------------------------

    @Test
    fun `an error event is an error part carrying the code, and the finish reason is error`() = runTest {
        val errorEvent = """{"event_type":"error","event_id":"event-error","error":{"code":"429","message":"Rate limit reached"}}"""
        val server = TestServer(sseOf(listOf(GoogleInteractionsFixtures.BASIC_CHUNKS.first(), errorEvent)))
        val parts = stream(server, CallOptions(TEST_PROMPT))

        val error = assertIs<APICallError>(parts.filterIsInstance<StreamPart.Error>().single().error)
        assertEquals("Rate limit reached", error.message)
        assertEquals(429, error.statusCode)
        assertTrue(error.isRetryable)
        assertEquals(parseJsonElement(errorEvent), error.data)
        assertEquals(FinishReason(FinishReason.Unified.Error, "failed"), parts.finish().finishReason)
    }

    @Test
    fun `a frame that is not JSON is an error part, not a silently shorter answer`() = runTest {
        // The stream dies on the bad frame: nothing after it, so nothing to overrule the failure — a
        // later `interaction.completed` would, and does in the reference's transform too.
        val server = TestServer(TestServer.sse("data: ${GoogleInteractionsFixtures.BASIC_CHUNKS.first()}\n\n", "data: not json\n\n"))
        val parts = stream(server, CallOptions(TEST_PROMPT))
        assertIs<JsonParseError>(parts.filterIsInstance<StreamPart.Error>().single().error)
        assertEquals(FinishReason(FinishReason.Unified.Error, "failed"), parts.finish().finishReason)
    }

    /** The `basic` chunks with the completed event's `service_tier` replaced, or removed for null. */
    private fun withServiceTier(chunks: List<String>, tier: String?): List<String> = chunks.map { line ->
        val event = parseJsonObject(line)
        if (event["event_type"].string() != "interaction.completed") return@map line
        val interaction = event["interaction"]!!.jsonObject
        buildJsonObject {
            event.forEach { (key, value) -> if (key != "interaction") put(key, value) }
            put(
                "interaction",
                buildJsonObject {
                    interaction.forEach { (key, value) -> if (key != "service_tier") put(key, value) }
                    tier?.let { put("service_tier", it) }
                },
            )
        }.toString()
    }

    private companion object {
        const val BASIC_STREAM_SIGNATURE = "CiQBDDnWxzCnKBoG0/vIUQ9fHy3JYGvjTmIZFY4uKrzvEqSAJh8KdQEMOdbH472i8Wb3Z10/9wPWQyeSH2" +
            "KMnQfWxi4Z+jlD+igWd1veIZW9QWMqrhgPQqsabcZiwzNAyNaSOJFu0D2ulKtvde8IrVcZkeoG0IR7QVcZWFbKF/uuXxeAV2CYsqXF8Xhv" +
            "362V/Lc17nWUjzFwvkrftAq8AQEMOdbHZ41h6ebbkW11izLCzDhm/aW2Zh5LR60hYvYMFrL22tOZFEHoBAzZJR+NaPaGbCCd6YtSKUMW" +
            "XDckIRL42Ms6xISx5eW2xKiNk4Pf+6CDD09wP7kxx/jrvn17+oFEB86PDzUCjK79WXHTufzNZ2NFyXV9wqAX6VcrxSz6eA7BvXtZcHnP5m" +
            "IlSpBwNDkuWwqtWZq7ebtySPbjjUq4tZw8xB/I0guOpC+u+lCAoSoCOtf8Y+Zc7SG6Cs8BAQw51sel75qdPe/A19DGEaH4sRjNgXHSNzW6" +
            "zsOqn+nMZEQznZSP+EMitUNYJSCv9ceM5a2+hs9PhGJuPVKrSxnmVya1iHYdquju9DD0q+wSycGXsor1UI4TMic4jg2+5xAFSUir9qA6n" +
            "SZeuRDjMkCQ0fbJESf2if70CBFNWQe1Q2kKpfc1CrZR2dtc28YfS/c8iyarj3cts6sybBAxgAR834OcVIttdMt8eimPXfCPAP0qGu1cx" +
            "jXSk746D3XmMwQtpIfW1/xpIjVhJsGA"
    }
}
