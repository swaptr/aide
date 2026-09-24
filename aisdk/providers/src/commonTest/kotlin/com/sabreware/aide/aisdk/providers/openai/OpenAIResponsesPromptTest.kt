package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.ToolNameMapping
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The prompt converter's 2026-09 delta, against `convert-to-openai-responses-input.test.ts`:
 * explicit message item types, `async` round-tripping on calls, the refusal of a denied result for a
 * programmatic call, and — with the `open-responses` knobs — image detail and strict assistant history.
 */
class OpenAIResponsesPromptTest {

    private fun context(
        namespace: String = "openai",
        systemRole: String? = "system",
        store: Boolean = true,
        customToolNames: Set<String> = emptySet(),
        explicitMessageItemType: Boolean = false,
        strictResponseInput: Boolean = false,
        defaultImageDetail: String? = null,
    ) = OpenAIReplayContext(
        systemRole = systemRole,
        store = store,
        mapping = ToolNameMapping.from(null, OpenAIProviderToolNames),
        providerToolsPresent = emptySet(),
        customToolNames = customToolNames,
        namespace = namespace,
        explicitMessageItemType = explicitMessageItemType,
        strictResponseInput = strictResponseInput,
        defaultImageDetail = defaultImageDetail,
    )

    private suspend fun Prompt.items(context: OpenAIReplayContext) = toOpenAIResponsesInput(context).items

    private val programCaller = mapOf(
        "openai" to buildJsonObject {
            putJsonObject("caller") {
                put("type", "program")
                put("callerId", "program_call_123")
            }
        },
    )

    @Test
    fun `explicit item types go on system, user and assistant messages`() = runTest {
        val items = listOf(
            ModelMessage.System("You are helpful."),
            ModelMessage.User(listOf(UserPart.Text("Hello"))),
            ModelMessage.Assistant(listOf(AssistantPart.Text("Hi!"))),
        ).items(context(namespace = "azure", explicitMessageItemType = true))

        assertEquals(
            listOf(
                parseJsonObject("""{"type":"message","role":"system","content":"You are helpful."}"""),
                parseJsonObject("""{"type":"message","role":"user","content":[{"type":"input_text","text":"Hello"}]}"""),
                parseJsonObject("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hi!"}]}"""),
            ),
            items,
        )
    }

    @Test
    fun `explicit item types go on developer messages too`() = runTest {
        val items = listOf(ModelMessage.System("You are helpful."))
            .items(context(namespace = "azure", systemRole = "developer", explicitMessageItemType = true))

        assertEquals(
            listOf(parseJsonObject("""{"type":"message","role":"developer","content":"You are helpful."}""")),
            items,
        )
    }

    @Test
    fun `async mode round-trips on a function tool call`() = runTest {
        val items = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall(
                        toolCallId = "call_async",
                        toolName = "get_weather",
                        input = """{"location":"Berlin"}""",
                        providerOptions = mapOf(
                            "openai" to buildJsonObject {
                                put(OPENAI_ITEM_ID_KEY, "fc_async")
                                put("async", true)
                            },
                        ),
                    ),
                ),
            ),
        ).items(context(store = false))

        assertEquals(
            listOf(
                parseJsonObject(
                    """{"type":"function_call","call_id":"call_async","name":"get_weather","arguments":"{\"location\":\"Berlin\"}","async":true}""",
                ),
            ),
            items,
        )
    }

    @Test
    fun `async mode round-trips on a custom tool call`() = runTest {
        val items = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall(
                        toolCallId = "call_custom_async",
                        toolName = "write_sql",
                        input = "SELECT 1",
                        providerOptions = mapOf(
                            "openai" to buildJsonObject {
                                put(OPENAI_ITEM_ID_KEY, "ctc_async")
                                put("async", true)
                            },
                        ),
                    ),
                ),
            ),
        ).items(context(store = false, customToolNames = setOf("write_sql")))

        assertEquals(
            listOf(
                parseJsonObject(
                    """{"type":"custom_tool_call","call_id":"call_custom_async","name":"write_sql","input":"SELECT 1","async":true,"id":"ctc_async"}""",
                ),
            ),
            items,
        )
    }

    @Test
    fun `a denied result for a programmatic call is refused, not sent as text`() = runTest {
        val prompt = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall(
                        toolCallId = "call_denied_123",
                        toolName = "search",
                        input = """{"query":"test"}""",
                        providerOptions = programCaller,
                    ),
                ),
            ),
            ModelMessage.Tool(
                listOf(
                    ToolPart.Result(
                        toolCallId = "call_denied_123",
                        toolName = "search",
                        output = ToolOutput.ExecutionDenied("User denied the tool execution"),
                    ),
                ),
            ),
        )

        assertFailsWith<UnsupportedFunctionalityError> { prompt.items(context()) }
    }

    @Test
    fun `a caller filed on a call is sent back on the call and on its result`() = runTest {
        val items = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall(
                        toolCallId = "call_1",
                        toolName = "search",
                        input = """{"query":"test"}""",
                        providerOptions = programCaller,
                    ),
                ),
            ),
            ModelMessage.Tool(
                listOf(
                    ToolPart.Result(
                        toolCallId = "call_1",
                        toolName = "search",
                        output = ToolOutput.Text("ok"),
                        providerOptions = programCaller,
                    ),
                ),
            ),
        ).items(context(store = false))

        // The wire spells it `caller_id`; the metadata it was read from spells it `callerId`.
        assertEquals(
            parseJsonObject("""{"type":"program","caller_id":"program_call_123"}"""),
            items[0]["caller"],
        )
        assertEquals(
            parseJsonObject(
                """{"type":"function_call_output","call_id":"call_1","output":"ok","caller":{"type":"program","caller_id":"program_call_123"}}""",
            ),
            items[1],
        )
    }

    @Test
    fun `an image part's detail is sent, and defaulted only where the endpoint wants one`() = runTest {
        val prompt = listOf(
            ModelMessage.User(
                listOf(
                    UserPart.File(
                        FileData.Bytes("fake-data".encodeToByteArray()),
                        "image/png",
                        providerOptions = mapOf("openai" to buildJsonObject { put("imageDetail", "low") }),
                    ),
                    UserPart.File(
                        FileData.Url("https://example.com/image.png"),
                        "image/png",
                        providerOptions = mapOf("openai" to buildJsonObject { put("imageDetail", "high") }),
                    ),
                    UserPart.File(FileData.Url("https://example.com/other.png"), "image/png"),
                ),
            ),
        )

        val plain = prompt.items(context()).single()["content"]!!.jsonArray.map { it.jsonObject["detail"] }
        assertEquals(listOf("low", "high"), plain.take(2).map { it!!.jsonPrimitive.content })
        assertNull(plain[2])

        val defaulted = prompt.items(context(defaultImageDetail = "auto")).single()["content"]!!.jsonArray
        assertEquals("auto", defaulted[2].jsonObject["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `strict input sends an assistant text without an id as an easy message`() = runTest {
        val items = listOf(ModelMessage.Assistant(listOf(AssistantPart.Text("Hello from assistant"))))
            .items(context(strictResponseInput = true))

        assertEquals(
            listOf(parseJsonObject("""{"type":"message","role":"assistant","content":"Hello from assistant"}""")),
            items,
        )
    }

    @Test
    fun `strict input sends an assistant text with an id as the complete output item`() = runTest {
        val items = listOf(
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Text(
                        "Hello from assistant",
                        providerOptions = mapOf("test-provider" to buildJsonObject { put(OPENAI_ITEM_ID_KEY, "msg_123") }),
                    ),
                ),
            ),
        ).items(context(namespace = "test-provider", strictResponseInput = true))

        assertEquals(
            listOf(
                parseJsonObject(
                    """{"id":"msg_123","type":"message","status":"completed","role":"assistant",""" +
                        """"content":[{"type":"output_text","text":"Hello from assistant","annotations":[],"logprobs":[]}]}""",
                ),
            ),
            items,
        )
    }
}
