package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openai.OpenResponsesExtensionFixtures.LMSTUDIO_BASIC_1
import com.sabreware.aide.aisdk.providers.openai.OpenResponsesExtensionFixtures.RECEIPT
import com.sabreware.aide.aisdk.providers.openai.OpenResponsesExtensionFixtures.SOURCE_ITEM
import com.sabreware.aide.aisdk.providers.openai.OpenResponsesExtensionFixtures.STREAM_CHUNKS
import com.sabreware.aide.aisdk.providers.openai.OpenResponsesExtensionFixtures.STREAM_RECEIPT
import com.sabreware.aide.aisdk.providers.openai.OpenResponsesExtensionFixtures.outputResponse
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The Open Responses extension mechanism: the registry's rules, from
 * `open-responses-extension.test.ts`, and the model's use of it, from
 * `open-responses-language-model.test.ts` — the `acme.document_search` extension those tests build,
 * ported codec for codec.
 */
class OpenResponsesExtensionTest {

    // --- the registry (open-responses-extension.test.ts) -------------------------------------------

    private fun extension(
        id: String = "acme.search",
        toolType: String? = "acme:search",
        itemTypes: List<String>? = listOf("acme:search_call"),
        eventTypes: List<String>? = null,
        encodeTool: (suspend (String, JsonObject) -> JsonObject?)? = { _, _ -> JsonObject(emptyMap()) },
        decodeItem: (suspend (OpenResponsesExtensionItem, OpenResponsesDecodeMode) -> List<Content>?)? =
            { _, _ -> null },
        decodeEvent: (suspend (OpenResponsesExtensionEvent, MutableMap<String, Any?>) -> List<StreamPart>?)? = null,
    ) = OpenResponsesExtension(
        id = id,
        toolType = toolType,
        itemTypes = itemTypes,
        eventTypes = eventTypes,
        encodeTool = encodeTool,
        decodeItem = decodeItem,
        decodeEvent = decodeEvent,
    )

    @Test
    fun `indexes registered extension semantics`() {
        val extension = extension(eventTypes = listOf("acme:search_delta"), decodeEvent = { _, _ -> null })
        val registry = OpenResponsesExtensionRegistry(listOf(extension))

        assertSame(extension, registry.byExtensionId["acme.search"])
        assertSame(extension, registry.byProviderToolId["acme.search"])
        assertSame(extension, registry.byToolType["acme:search"])
        assertSame(extension, registry.byItemType["acme:search_call"])
        assertSame(extension, registry.byEventType["acme:search_delta"])
    }

    @Test
    fun `registers tool, item, and event capabilities independently`() {
        val toolExtension = extension(itemTypes = null, decodeItem = null)
        val itemExtension = OpenResponsesExtension(
            id = "acme.receipts",
            itemTypes = listOf("acme:receipt"),
            decodeItem = { _, _ -> null },
        )
        val eventExtension = OpenResponsesExtension(
            id = "acme.progress",
            eventTypes = listOf("acme:progress_delta"),
            decodeEvent = { _, _ -> null },
        )

        val registry = OpenResponsesExtensionRegistry(listOf(toolExtension, itemExtension, eventExtension))

        assertSame(toolExtension, registry.byProviderToolId["acme.search"])
        assertSame(itemExtension, registry.byItemType["acme:receipt"])
        assertSame(eventExtension, registry.byEventType["acme:progress_delta"])
    }

    @Test
    fun `rejects incomplete capabilities`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            OpenResponsesExtensionRegistry(listOf(OpenResponsesExtension(id = "acme.search", toolType = "acme:search")))
        }
        assertTrue("must provide toolType and encodeTool together" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `rejects wire types outside the provider-tool namespace`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            OpenResponsesExtensionRegistry(listOf(extension(toolType = "other:search")))
        }
        assertTrue("Extension wire types must use the acme: namespace." in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `rejects duplicate item registrations`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            OpenResponsesExtensionRegistry(
                listOf(extension(), extension(id = "acme.other_search", toolType = "acme:other_search")),
            )
        }
        assertTrue(
            "item type acme:search_call because it is already registered by acme.search" in failure.message.orEmpty(),
            failure.message,
        )
    }

    @Test
    fun `rejects an extension with nothing to register`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            OpenResponsesExtensionRegistry(listOf(OpenResponsesExtension(id = "acme.nothing")))
        }
        assertTrue("must register a tool, item, or event capability" in failure.message.orEmpty(), failure.message)
    }

    // --- the model (open-responses-language-model.test.ts) -----------------------------------------

    private val url = "https://localhost:1234/v1/responses"

    private val prompt = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello")))))

    private fun model(server: TestServer, vararg extensions: OpenResponsesExtension): LanguageModel =
        OpenResponsesProvider(
            client = HttpClient(server.engine()),
            url = url,
            name = "lmstudio",
            extensions = extensions.toList(),
        ).languageModel("gemma-7b-it")

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.executed(default: Boolean): Boolean =
        (this["provider_executed"] as? JsonPrimitive)?.booleanOrNull ?: default

    /** The reference's `createDocumentSearchExtension`, codec for codec. */
    private fun documentSearchExtension(
        providerExecuted: Boolean,
        decodeItem: suspend (OpenResponsesExtensionItem, OpenResponsesDecodeMode) -> List<Content>? =
            { item, _ -> decodeDocumentSearch(item, providerExecuted) },
    ) = OpenResponsesExtension(
        id = "acme.document_search",
        toolType = "acme:document_search",
        itemTypes = listOf(
            "acme:document_search_call",
            "acme:document_search_result",
            "acme:document_search_receipt",
        ),
        eventTypes = listOf("acme:document_search_input"),
        encodeTool = { name, args ->
            buildJsonObject {
                put("name", name)
                args.forEach { (key, value) -> put(key, value) }
            }
        },
        encodeToolChoice = { name, _ -> buildJsonObject { put("name", name) } },
        decodeItem = decodeItem,
        encodeInputItem = { part, _ ->
            when (part) {
                is OpenResponsesExtensionInputPart.Call -> listOf(
                    buildJsonObject {
                        put("type", "acme:document_search_call")
                        put("id", "call_item_${part.call.toolCallId}")
                        put("status", "completed")
                        put("call_id", part.call.toolCallId)
                        put("name", part.call.toolName)
                        put("query", ProviderJson.parseToJsonElement(part.call.input))
                    },
                )
                is OpenResponsesExtensionInputPart.Result -> listOf(
                    buildJsonObject {
                        put("type", "acme:document_search_result")
                        put("id", "result_item_${part.result.toolCallId}")
                        put("status", "completed")
                        put("call_id", part.result.toolCallId)
                        put("name", part.result.toolName)
                        // The reference hands the spec's output object through as-is.
                        put("result", ProviderJson.encodeToJsonElement(ToolOutput.serializer(), part.result.output))
                    },
                )
            }
        },
        decodeEvent = { event, _ ->
            val json = event.json
            listOf(
                StreamPart.ToolInputStart(
                    id = json.str("call_id"),
                    toolName = json.str("name"),
                    providerExecuted = json.executed(providerExecuted),
                ),
                StreamPart.ToolInputDelta(id = json.str("call_id"), delta = json.str("delta")),
                StreamPart.ToolInputEnd(id = json.str("call_id")),
            )
        },
    )

    private fun decodeDocumentSearch(item: OpenResponsesExtensionItem, providerExecuted: Boolean): List<Content> {
        val json = item.json
        val call = Content.ToolCall(
            toolCallId = json.str("call_id"),
            toolName = json.str("name"),
            input = json["query"].toString(),
            providerExecuted = json.executed(providerExecuted),
        )
        val result = Content.ToolResult(
            toolCallId = json.str("call_id"),
            toolName = json.str("name"),
            output = ToolOutput.Json(json["result"]!!),
        )
        return when (item.type) {
            "acme:document_search_call" -> listOf(call)
            "acme:document_search_result" -> listOf(result)
            else -> listOf(call, result)
        }
    }

    private fun reference(itemId: String) = mapOf(
        "lmstudio" to buildJsonObject {
            putJsonObject(OPEN_RESPONSES_EXTENSION_KEY) {
                put("id", "acme.document_search")
                put("itemId", itemId)
            }
        },
    )

    private fun carrier(item: String) = Content.Custom(
        kind = OPEN_RESPONSES_EXTENSION_REPLAY_KIND,
        providerMetadata = mapOf(
            "lmstudio" to buildJsonObject {
                putJsonObject(OPEN_RESPONSES_EXTENSION_KEY) {
                    put("id", "acme.document_search")
                    put("item", parseJsonObject(item))
                }
            },
        ),
    )

    @Test
    fun `decodes extension items and replays them through response history`() = runTest {
        val server = TestServer(TestServer.json(outputResponse(RECEIPT)), TestServer.json(outputResponse()))
        val model = model(server, documentSearchExtension(providerExecuted = true))

        val first = model.doGenerate(prompt)

        // The carrier first, then the parts — each stamped with the extension and the item it came from.
        assertEquals(
            listOf(
                carrier(RECEIPT),
                Content.ToolCall(
                    toolCallId = "call_1",
                    toolName = "documentSearch",
                    input = """{"text":"climate"}""",
                    providerExecuted = false,
                    providerMetadata = reference("search_1"),
                ),
                Content.ToolResult(
                    toolCallId = "call_1",
                    toolName = "documentSearch",
                    output = ToolOutput.Json(parseJsonObject("""{"documents":[{"id":"doc_1","score":0.9}]}""")),
                    providerMetadata = reference("search_1"),
                ),
            ),
            first.content,
        )

        model.doGenerate(
            prompt.copy(
                prompt = listOf(
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.Custom(
                                kind = OPEN_RESPONSES_EXTENSION_REPLAY_KIND,
                                providerOptions = first.content[0].providerMetadata,
                            ),
                            AssistantPart.ToolCall(
                                toolCallId = "call_1",
                                toolName = "documentSearch",
                                input = """{"text":"climate"}""",
                                providerExecuted = false,
                                providerOptions = first.content[1].providerMetadata,
                            ),
                            AssistantPart.ToolResult(
                                toolCallId = "call_1",
                                toolName = "documentSearch",
                                output = ToolOutput.Json(parseJsonObject("""{"documents":[{"id":"doc_1","score":0.9}]}""")),
                                providerOptions = first.content[2].providerMetadata,
                            ),
                        ),
                    ),
                ),
            ),
        )

        // The receipt goes back verbatim, ONCE; the two parts decoded from it are recognised as already
        // replayed rather than re-encoded as a function call the server never saw.
        assertEquals(parseJsonObject("""{"input":[$RECEIPT]}""")["input"], server.request(1).bodyJson()["input"])
    }

    @Test
    fun `replays a source-only extension item through response history`() = runTest {
        val server = TestServer(TestServer.json(outputResponse(SOURCE_ITEM)), TestServer.json(outputResponse()))
        val extension = documentSearchExtension(providerExecuted = true) { item, _ ->
            listOf(Content.Source.Url(id = item.id, url = item.json.str("url"), title = item.json.str("title")))
        }
        val model = model(server, extension)

        val first = model.doGenerate(prompt)

        assertEquals(
            listOf(
                carrier(SOURCE_ITEM),
                Content.Source.Url(
                    id = "source_1",
                    url = "https://example.com/documentation",
                    title = "Extension documentation",
                    providerMetadata = reference("source_1"),
                ),
            ),
            first.content,
        )

        model.doGenerate(
            prompt.copy(
                prompt = listOf(
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.Custom(
                                kind = OPEN_RESPONSES_EXTENSION_REPLAY_KIND,
                                providerOptions = first.content[0].providerMetadata,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(parseJsonObject("""{"input":[$SOURCE_ITEM]}""")["input"], server.request(1).bodyJson()["input"])
    }

    @Test
    fun `encodes client-executed extension calls and results without original wire metadata`() = runTest {
        val server = TestServer(TestServer.json(LMSTUDIO_BASIC_1))

        model(server, documentSearchExtension(providerExecuted = false)).doGenerate(
            prompt.copy(
                prompt = listOf(
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.ToolCall(
                                toolCallId = "call_client",
                                toolName = "documentSearch",
                                input = """{"text":"weather"}""",
                            ),
                        ),
                    ),
                    ModelMessage.Tool(
                        listOf(
                            ToolPart.Result(
                                toolCallId = "call_client",
                                toolName = "documentSearch",
                                output = ToolOutput.Json(parseJsonObject("""{"documents":["forecast"]}""")),
                            ),
                        ),
                    ),
                ),
                tools = listOf(
                    Tool.ProviderDefined(name = "documentSearch", id = "acme.document_search", args = buildJsonObject { }),
                ),
            ),
        )

        // No carrier to replay, so the extension is asked for the items — and the result is the
        // spec's own output object, exactly as the reference hands it through.
        val expected: JsonElement = parseJsonObject(
            """{"input":[
                {"type":"acme:document_search_call","id":"call_item_call_client","status":"completed",
                 "call_id":"call_client","name":"documentSearch","query":{"text":"weather"}},
                {"type":"acme:document_search_result","id":"result_item_call_client","status":"completed",
                 "call_id":"call_client","name":"documentSearch",
                 "result":{"type":"json","value":{"documents":["forecast"]}}}
            ]}""",
        )["input"]!!
        assertEquals(expected, server.request().bodyJson()["input"])
    }

    @Test
    fun `encodes registered provider tools and preserves warnings for unregistered tools`() = runTest {
        val server = TestServer(TestServer.json(LMSTUDIO_BASIC_1))

        val result = model(server, documentSearchExtension(providerExecuted = true)).doGenerate(
            prompt.copy(
                tools = listOf(
                    Tool.Function("lookup", buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) }),
                    Tool.ProviderDefined(
                        name = "documentSearch",
                        id = "acme.document_search",
                        args = buildJsonObject { put("index", "docs") },
                    ),
                    Tool.ProviderDefined(name = "unregistered", id = "acme.unregistered", args = buildJsonObject { }),
                ),
                toolChoice = ToolChoice.Specific("documentSearch"),
            ),
        )

        val body = server.request().bodyJson()
        assertEquals(
            parseJsonObject(
                """{"tools":[
                    {"type":"function","name":"lookup","parameters":{"type":"object","properties":{}}},
                    {"type":"acme:document_search","name":"documentSearch","index":"docs"}
                ]}""",
            )["tools"],
            body["tools"],
        )
        assertEquals(
            buildJsonObject {
                put("type", "acme:document_search")
                put("name", "documentSearch")
            },
            body["tool_choice"],
        )
        // The unregistered id fell through to OpenAI's table, which does not know it either.
        assertEquals(listOf("providerTool:unregistered"), result.warnings.filterIsInstance<Warning.Unsupported>().map { it.feature })
    }

    @Test
    fun `warns and omits a registered provider tool and its selected choice when it cannot be encoded`() = runTest {
        val server = TestServer(TestServer.json(LMSTUDIO_BASIC_1))
        val extension = OpenResponsesExtension(
            id = "acme.document_search",
            toolType = "acme:document_search",
            encodeTool = { _, _ -> null },
        )

        val result = model(server, extension).doGenerate(
            prompt.copy(
                tools = listOf(
                    Tool.ProviderDefined(name = "documentSearch", id = "acme.document_search", args = buildJsonObject { }),
                ),
                toolChoice = ToolChoice.Specific("documentSearch"),
            ),
        )

        val body = server.request().bodyJson()
        // Neither the tool nor a choice naming it: a `tool_choice` for a tool that was not sent is a 400.
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
        result.warnings.assertUnsupported("providerTool:documentSearch")
    }

    @Test
    fun `a throwing encoder declines its tool rather than failing the call`() = runTest {
        val server = TestServer(TestServer.json(LMSTUDIO_BASIC_1))
        val extension = OpenResponsesExtension(
            id = "acme.document_search",
            toolType = "acme:document_search",
            encodeTool = { _, _ -> error("codec bug") },
        )

        val result = model(server, extension).doGenerate(
            prompt.copy(
                tools = listOf(
                    Tool.ProviderDefined(name = "documentSearch", id = "acme.document_search", args = buildJsonObject { }),
                ),
            ),
        )

        assertNull(server.request().bodyJson()["tools"])
        result.warnings.assertUnsupported("providerTool:documentSearch")
        assertEquals("text content", assertIs<Content.Text>(result.content.last()).text)
    }

    @Test
    fun `an item nobody registered is ignored, however un-OpenAI its fields are`() = runTest {
        // `result` is an object here where OpenAI's image_generation_call carries a string; before
        // extension items were reduced to {type, id, status} this failed the whole response.
        val stranger = """{"type":"other:thing","id":"x_1","status":"completed","result":{"nested":true},"name":7}"""
        val message = """{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"hi"}]}"""
        val server = TestServer(TestServer.json(outputResponse(stranger, message)))

        val result = model(server, documentSearchExtension(providerExecuted = true)).doGenerate(prompt)

        assertEquals(listOf("hi"), result.content.map { assertIs<Content.Text>(it).text })
    }

    @Test
    fun `decodes registered extension events and completed items`() = runTest {
        val server = TestServer(
            TestServer.sse(*(STREAM_CHUNKS.map { "data: $it\n\n" } + "data: [DONE]\n\n").toTypedArray()),
        )

        val parts = model(server, documentSearchExtension(providerExecuted = true)).doStream(prompt).stream.toList()

        assertTrue(
            StreamPart.ToolInputStart(id = "call_stream_1", toolName = "documentSearch", providerExecuted = true) in parts,
            parts.toString(),
        )
        assertTrue(StreamPart.ToolInputDelta(id = "call_stream_1", delta = """{"text":"streamed query"}""") in parts)
        assertTrue(StreamPart.CustomPart(carrier(STREAM_RECEIPT)) in parts, parts.toString())
        assertTrue(
            StreamPart.ToolCallPart(
                Content.ToolCall(
                    toolCallId = "call_stream_1",
                    toolName = "documentSearch",
                    input = """{"text":"streamed query"}""",
                    providerExecuted = true,
                    providerMetadata = reference("search_stream_1"),
                ),
            ) in parts,
            parts.toString(),
        )
        assertTrue(
            StreamPart.ToolResultPart(
                Content.ToolResult(
                    toolCallId = "call_stream_1",
                    toolName = "documentSearch",
                    output = ToolOutput.Json(parseJsonObject("""{"documents":["doc_1"]}""")),
                    providerMetadata = reference("search_stream_1"),
                ),
            ) in parts,
            parts.toString(),
        )
        // An extension that surfaced a call — whoever executes it — has turned the finish into
        // tool-calls, as the reference reads it.
        assertEquals(FinishReason.Unified.ToolCalls, assertIs<StreamPart.Finish>(parts.last()).finishReason.unified)
    }

    @Test
    fun `a throwing event codec becomes an error part, not the end of the stream`() = runTest {
        val server = TestServer(
            TestServer.sse(*(STREAM_CHUNKS.map { "data: $it\n\n" }).toTypedArray()),
        )
        val extension = OpenResponsesExtension(
            id = "acme.document_search",
            eventTypes = listOf("acme:document_search_input"),
            decodeEvent = { _, _ -> error("codec bug") },
        )

        val parts = model(server, extension).doStream(prompt).stream.toList()

        assertEquals("codec bug", parts.filterIsInstance<StreamPart.Error>().single().error.message)
        assertIs<StreamPart.Finish>(parts.last())
    }
}
