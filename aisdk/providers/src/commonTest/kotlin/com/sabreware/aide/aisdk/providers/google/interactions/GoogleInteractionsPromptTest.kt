package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.NoSuchProviderReferenceError
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.parseJsonElement
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The neutral prompt as Interactions `input` steps, ported case for case from the reference's
 * `convert-to-google-interactions-input.test.ts`. Assertions compare the ENCODED steps, because the
 * wire is what the API validates and a Kotlin default that never reaches it is invisible on the object.
 */
class GoogleInteractionsPromptTest {

    private fun convert(
        prompt: Prompt,
        previousInteractionId: String? = null,
        store: Boolean? = null,
        mediaResolution: String? = null,
    ): ConvertedInteractionsInput = prompt.toGoogleInteractionsInput(previousInteractionId, store, mediaResolution)

    private fun ConvertedInteractionsInput.inputJson(): JsonElement =
        ProviderJson.encodeToJsonElement(ListSerializer(InteractionsStep.serializer()), input)

    private fun user(vararg parts: UserPart) = ModelMessage.User(parts.toList())

    private fun userText(text: String) = user(UserPart.Text(text))

    private fun bytesFile(mediaType: String, vararg bytes: Byte) =
        UserPart.File(FileData.Bytes(bytes), mediaType)

    // --- text and system -------------------------------------------------------------------------

    @Test
    fun `a text-only prompt is one user_input step`() {
        val result = convert(listOf(userText("Hello, how are you?")))
        assertEquals(
            parseJsonElement("""[{"type":"user_input","content":[{"type":"text","text":"Hello, how are you?"}]}]"""),
            result.inputJson(),
        )
        assertNull(result.systemInstruction)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `system messages are hoisted and joined`() {
        val result = convert(
            listOf(ModelMessage.System("You are a helpful assistant."), userText("Hi"), ModelMessage.System("Be brief.")),
        )
        assertEquals("You are a helpful assistant.\n\nBe brief.", result.systemInstruction)
    }

    // --- file parts ------------------------------------------------------------------------------

    @Test
    fun `inline image bytes are a base64 image block with the full media type`() {
        val result = convert(listOf(user(UserPart.Text("Describe this"), bytesFile("image/png", 1, 2, 3, 4))))
        assertEquals(
            parseJsonElement(
                """[{"type":"user_input","content":[{"type":"text","text":"Describe this"},""" +
                    """{"type":"image","data":"AQIDBA==","mime_type":"image/png"}]}]""",
            ),
            result.inputJson(),
        )
    }

    @Test
    fun `a wildcard media type is resolved from the bytes, and refused when it cannot be`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        val resolved = convert(listOf(user(UserPart.File(FileData.Bytes(png), "image/*"))))
        assertEquals("image/png", (resolved.inputJson().stepContent(0, 0)["mime_type"] as JsonPrimitive).content)
        assertFailsWith<UnsupportedFunctionalityError> {
            convert(listOf(user(bytesFile("image/*", 0, 0, 0))))
        }
    }

    @Test
    fun `a URL is passed through as uri, with the media type only when it is a full one`() {
        val full = convert(listOf(user(UserPart.File(FileData.Url("https://example.com/cat.png"), "image/png"))))
        assertEquals(
            parseJsonElement("""{"type":"image","uri":"https://example.com/cat.png","mime_type":"image/png"}"""),
            full.inputJson().stepContent(0, 0),
        )
        val wildcard = convert(listOf(user(UserPart.File(FileData.Url("https://example.com/cat"), "image/*"))))
        assertEquals(
            parseJsonElement("""{"type":"image","uri":"https://example.com/cat"}"""),
            wildcard.inputJson().stepContent(0, 0),
        )
    }

    @Test
    fun `a Files API reference is its google URI, and a reference without one is an error`() {
        val result = convert(
            listOf(
                user(
                    UserPart.File(
                        FileData.Reference(mapOf("google" to "https://generativelanguage.googleapis.com/v1beta/files/abc")),
                        "image/png",
                    ),
                ),
            ),
        )
        assertEquals(
            parseJsonElement(
                """{"type":"image","uri":"https://generativelanguage.googleapis.com/v1beta/files/abc","mime_type":"image/png"}""",
            ),
            result.inputJson().stepContent(0, 0),
        )
        assertFailsWith<NoSuchProviderReferenceError> {
            convert(listOf(user(UserPart.File(FileData.Reference(mapOf("openai" to "file-abc")), "image/png"))))
        }
    }

    @Test
    fun `media resolution is stamped on image and video blocks and nothing else`() {
        val result = convert(
            listOf(
                user(
                    bytesFile("image/png", 1),
                    UserPart.File(FileData.Url("https://youtu.be/abc"), "video/mp4"),
                    bytesFile("application/pdf", 1),
                ),
            ),
            mediaResolution = "high",
        )
        assertEquals(
            parseJsonElement(
                """[{"type":"image","data":"AQ==","mime_type":"image/png","resolution":"high"},""" +
                    """{"type":"video","uri":"https://youtu.be/abc","mime_type":"video/mp4","resolution":"high"},""" +
                    """{"type":"document","data":"AQ==","mime_type":"application/pdf"}]""",
            ),
            (result.inputJson() as JsonArray)[0].let { (it as JsonObject)["content"] },
        )
    }

    @Test
    fun `application and text media types are documents, audio is audio`() {
        val result = convert(
            listOf(user(bytesFile("application/pdf", 1, 2, 3), bytesFile("text/csv", 65), bytesFile("audio/wav", 1))),
        )
        val kinds = ((result.inputJson() as JsonArray)[0] as JsonObject)["content"]!!
            .let { it as JsonArray }
            .map { ((it as JsonObject)["type"] as JsonPrimitive).content }
        assertEquals(listOf("document", "document", "audio"), kinds)
    }

    @Test
    fun `a text file collapses into a text block and merges with its neighbours`() {
        val merged = convert(
            listOf(user(UserPart.Text("Please review:"), UserPart.File(FileData.Text("inline body"), "text/plain"))),
        )
        assertEquals(
            parseJsonElement("""[{"type":"user_input","content":[{"type":"text","text":"Please review:\n\ninline body"}]}]"""),
            merged.inputJson(),
        )
        val split = convert(
            listOf(
                user(
                    UserPart.Text("before"),
                    UserPart.File(FileData.Bytes(byteArrayOf(1, 2, 3)), "image/png"),
                    UserPart.File(FileData.Text("after"), "text/plain"),
                ),
            ),
        )
        assertEquals(
            parseJsonElement(
                """[{"type":"user_input","content":[{"type":"text","text":"before"},""" +
                    """{"type":"image","data":"AQID","mime_type":"image/png"},{"type":"text","text":"after"}]}]""",
            ),
            split.inputJson(),
        )
        val three = convert(
            listOf(user(UserPart.Text("a"), UserPart.Text("b"), UserPart.File(FileData.Text("c"), "text/markdown"))),
        )
        assertEquals(
            parseJsonElement("""[{"type":"user_input","content":[{"type":"text","text":"a\n\nb\n\nc"}]}]"""),
            three.inputJson(),
        )
    }

    @Test
    fun `an unrecognised media type is warned about and dropped`() {
        val result = convert(listOf(user(UserPart.Text("here"), bytesFile("font/woff2", 1))))
        assertEquals(
            parseJsonElement("""[{"type":"user_input","content":[{"type":"text","text":"here"}]}]"""),
            result.inputJson(),
        )
        assertEquals(
            listOf(Warning.Other("google.interactions: unsupported file media type \"font/woff2\"; part dropped.")),
            result.warnings,
        )
    }

    // --- assistant parts -------------------------------------------------------------------------

    @Test
    fun `an assistant tool call is a function_call step with parsed arguments and its signature`() {
        val result = convert(
            listOf(
                userText("What's the weather in NYC?"),
                ModelMessage.Assistant(
                    listOf(
                        AssistantPart.ToolCall(
                            "call_abc",
                            "getWeather",
                            """{"location":"New York"}""",
                            providerOptions = googleMeta("signature" to "sig-xyz"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            parseJsonElement(
                """[{"type":"user_input","content":[{"type":"text","text":"What's the weather in NYC?"}]},""" +
                    """{"type":"function_call","id":"call_abc","name":"getWeather","arguments":{"location":"New York"},""" +
                    """"signature":"sig-xyz"}]""",
            ),
            result.inputJson(),
        )
    }

    @Test
    fun `tool-call input that is not an object is wrapped rather than lost`() {
        fun arguments(input: String): JsonElement {
            val result = convert(listOf(ModelMessage.Assistant(listOf(AssistantPart.ToolCall("c", "t", input)))))
            return ((result.inputJson() as JsonArray)[0] as JsonObject)["arguments"]!!
        }
        assertEquals(parseJsonElement("""{"value":[1,2]}"""), arguments("[1,2]"))
        assertEquals(parseJsonElement("""{"value":"not json"}"""), arguments("not json"))
        assertEquals(parseJsonElement("""{"location":"Boston"}"""), arguments("""{"location":"Boston"}"""))
    }

    @Test
    fun `assistant text, files and reasoning become the steps the API would have emitted`() {
        val result = convert(
            listOf(
                userText("generate a cat"),
                ModelMessage.Assistant(
                    listOf(
                        AssistantPart.Reasoning("I will draw.", providerOptions = googleMeta("signature" to "sig-1")),
                        AssistantPart.Text("Here it is:"),
                        AssistantPart.File(FileData.Bytes(byteArrayOf(1, 2, 3)), "image/png"),
                        AssistantPart.Reasoning("", providerOptions = googleMeta("signature" to "sig-2")),
                        AssistantPart.File(FileData.Url("https://example.com/cat.png"), "image/png"),
                    ),
                ),
                userText("now make it red"),
            ),
        )
        assertEquals(
            parseJsonElement(
                """[{"type":"user_input","content":[{"type":"text","text":"generate a cat"}]},""" +
                    """{"type":"thought","signature":"sig-1","summary":[{"type":"text","text":"I will draw."}]},""" +
                    """{"type":"model_output","content":[{"type":"text","text":"Here it is:"},""" +
                    """{"type":"image","data":"AQID","mime_type":"image/png"}]},""" +
                    """{"type":"thought","signature":"sig-2"},""" +
                    """{"type":"model_output","content":[{"type":"image","uri":"https://example.com/cat.png","mime_type":"image/png"}]},""" +
                    """{"type":"user_input","content":[{"type":"text","text":"now make it red"}]}]""",
            ),
            result.inputJson(),
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `parts this surface cannot replay are warned about, and approval bookkeeping is silent`() {
        val result = convert(
            listOf(
                ModelMessage.Assistant(
                    listOf(
                        AssistantPart.Custom("openai.thing"),
                        AssistantPart.ApprovalRequest("a1", "c1"),
                        AssistantPart.Text("ok"),
                    ),
                ),
                ModelMessage.Tool(listOf(ToolPart.ApprovalResponse("a1", approved = true))),
            ),
        )
        assertEquals(
            parseJsonElement("""[{"type":"model_output","content":[{"type":"text","text":"ok"}]}]"""),
            result.inputJson(),
        )
        assertEquals(
            listOf(
                Warning.Other(
                    "google.interactions: unsupported or invalid custom assistant content part \"openai.thing\"; part dropped.",
                ),
            ),
            result.warnings,
        )
    }

    // --- agentic video -----------------------------------------------------------------------------

    @Test
    fun `per-file agentic processing rides the video block`() {
        // "maps per-file agentic processing onto a video block".
        val result = convert(
            listOf(
                user(
                    UserPart.File(
                        FileData.Url("https://www.youtube.com/watch?v=abc123"),
                        "video",
                        providerOptions = mapOf("google" to parseJsonObject("""{"processing":"agentic"}""")),
                    ),
                ),
            ),
        )

        assertEquals(
            parseJsonElement(
                """[{"type":"user_input","content":[{"type":"video","uri":"https://www.youtube.com/watch?v=abc123",""" +
                    """"processing":"agentic"}]}]""",
            ),
            result.inputJson(),
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `camel-case static processing options are re-spelled onto the wire`() {
        // "maps camel-case static processing options onto the wire format".
        val result = convert(
            listOf(
                user(
                    UserPart.File(
                        FileData.Bytes(byteArrayOf(0, 0, 0)),
                        "video/mp4",
                        providerOptions = mapOf(
                            "google" to parseJsonObject(
                                """{"processing":{"type":"static","startOffset":1200,"endOffset":1500,"fps":0.5}}""",
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            parseJsonElement(
                """[{"type":"user_input","content":[{"type":"video","data":"AAAA","mime_type":"video/mp4",""" +
                    """"processing":{"type":"static","start_offset":1200,"end_offset":1500,"fps":0.5}}]}]""",
            ),
            result.inputJson(),
        )
    }

    @Test
    fun `an invalid processing option is warned about and dropped`() {
        val result = convert(
            listOf(
                user(
                    UserPart.File(
                        FileData.Url("https://example.com/clip.mp4"),
                        "video/mp4",
                        providerOptions = mapOf("google" to parseJsonObject("""{"processing":"fast"}""")),
                    ),
                ),
            ),
        )

        assertEquals(
            parseJsonElement("""[{"type":"user_input","content":[{"type":"video","uri":"https://example.com/clip.mp4","mime_type":"video/mp4"}]}]"""),
            result.inputJson(),
        )
        assertEquals(
            listOf(
                Warning.Other(
                    "google.interactions: invalid providerOptions.google.processing on video file part; expected " +
                        "\"agentic\", \"static\", or a static processing configuration. Option dropped.",
                ),
            ),
            result.warnings,
        )
    }

    @Test
    fun `custom video processing parts round-trip as the steps they came from`() {
        // "round-trips custom video processing parts".
        val result = convert(
            listOf(
                ModelMessage.Assistant(
                    listOf(
                        AssistantPart.Custom(
                            "google.processing_call",
                            providerOptions = mapOf(
                                "google" to parseJsonObject("""{"processingId":"processing-1","signature":"call-signature"}"""),
                            ),
                        ),
                        AssistantPart.Custom(
                            "google.processing_result",
                            providerOptions = mapOf(
                                "google" to parseJsonObject("""{"processingCallId":"processing-1","signature":"result-signature"}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            parseJsonElement(
                """[{"type":"processing_call","id":"processing-1","signature":"call-signature"},""" +
                    """{"type":"processing_result","call_id":"processing-1","signature":"result-signature"}]""",
            ),
            result.inputJson(),
        )
        assertTrue(result.warnings.isEmpty())
    }

    // --- tool results ----------------------------------------------------------------------------

    private fun toolTurn(output: ToolOutput) = listOf(
        userText("q"),
        ModelMessage.Assistant(listOf(AssistantPart.ToolCall("call_x", "getWeather", "{}"))),
        ModelMessage.Tool(listOf(ToolPart.Result("call_x", "getWeather", output))),
    )

    @Test
    fun `a text result is a function_result block on a user turn`() {
        val result = convert(toolTurn(ToolOutput.Text("It is sunny.")))
        assertEquals(
            parseJsonElement(
                """[{"type":"user_input","content":[{"type":"text","text":"q"}]},""" +
                    """{"type":"function_call","id":"call_x","name":"getWeather","arguments":{}},""" +
                    """{"type":"user_input","content":[{"type":"function_result","call_id":"call_x","name":"getWeather",""" +
                    """"result":"It is sunny."}]}]""",
            ),
            result.inputJson(),
        )
    }

    @Test
    fun `a JSON result is sent as its JSON text, and errors carry is_error`() {
        val json = convert(toolTurn(ToolOutput.Json(parseJsonElement("""{"temperature":72,"condition":"sunny"}"""))))
        assertEquals(
            parseJsonElement(
                """{"type":"function_result","call_id":"call_x","name":"getWeather",""" +
                    """"result":"{\"temperature\":72,\"condition\":\"sunny\"}"}""",
            ),
            json.inputJson().stepContent(2, 0),
        )
        val errorText = convert(toolTurn(ToolOutput.ErrorText("API timeout")))
        assertEquals(
            parseJsonElement("""{"type":"function_result","call_id":"call_x","name":"getWeather","is_error":true,"result":"API timeout"}"""),
            errorText.inputJson().stepContent(2, 0),
        )
        val denied = convert(toolTurn(ToolOutput.ExecutionDenied()))
        assertEquals(
            parseJsonElement(
                """{"type":"function_result","call_id":"call_x","name":"getWeather","is_error":true,""" +
                    """"result":"Tool execution denied by user."}""",
            ),
            denied.inputJson().stepContent(2, 0),
        )
    }

    @Test
    fun `a multipart result keeps its text and images and warns about anything else`() {
        val result = convert(
            toolTurn(
                ToolOutput.Multipart(
                    listOf(
                        ToolOutput.Multipart.Item.Text("Here is the result:"),
                        ToolOutput.Multipart.Item.File(FileData.Bytes(byteArrayOf(1, 2, 3)), "image/png"),
                        ToolOutput.Multipart.Item.File(FileData.Bytes(byteArrayOf(1, 2, 3)), "application/pdf"),
                    ),
                ),
            ),
        )
        assertEquals(
            parseJsonElement(
                """{"type":"function_result","call_id":"call_x","name":"getWeather","result":[""" +
                    """{"type":"text","text":"Here is the result:"},{"type":"image","data":"AQID","mime_type":"image/png"}]}""",
            ),
            result.inputJson().stepContent(2, 0),
        )
        assertEquals(
            listOf(
                Warning.Other(
                    "google.interactions: tool-result file with mediaType \"application/pdf\" is not supported " +
                        "(Interactions `function_result.result` accepts only text and image content); part dropped.",
                ),
            ),
            result.warnings,
        )
    }

    // --- compaction ------------------------------------------------------------------------------

    private val previous = "v1_prev-interaction-abc"

    @Test
    fun `an assistant turn from the linked interaction is dropped`() {
        val result = convert(
            listOf(
                userText("first user input"),
                ModelMessage.Assistant(
                    listOf(AssistantPart.Text("old answer", providerOptions = googleMeta("interactionId" to previous))),
                ),
                userText("follow-up user input"),
            ),
            previousInteractionId = previous,
        )
        assertEquals(
            parseJsonElement(
                """[{"type":"user_input","content":[{"type":"text","text":"first user input"}]},""" +
                    """{"type":"user_input","content":[{"type":"text","text":"follow-up user input"}]}]""",
            ),
            result.inputJson(),
        )
    }

    @Test
    fun `an assistant turn from another interaction is kept`() {
        val result = convert(
            listOf(
                userText("first user input"),
                ModelMessage.Assistant(
                    listOf(AssistantPart.Text("unrelated", providerOptions = googleMeta("interactionId" to "some-other"))),
                ),
                userText("follow-up user input"),
            ),
            previousInteractionId = previous,
        )
        assertEquals(listOf("user_input", "model_output", "user_input"), result.input.map { it.type })
    }

    @Test
    fun `a dropped tool call takes its paired result with it`() {
        val result = convert(
            listOf(
                userText("q1"),
                ModelMessage.Assistant(
                    listOf(
                        AssistantPart.ToolCall(
                            "call_old",
                            "getWeather",
                            """{"location":"Boston"}""",
                            providerOptions = googleMeta("interactionId" to previous),
                        ),
                    ),
                ),
                ModelMessage.Tool(listOf(ToolPart.Result("call_old", "getWeather", ToolOutput.Text("sunny")))),
                userText("q2"),
            ),
            previousInteractionId = previous,
        )
        assertEquals(listOf("user_input", "user_input"), result.input.map { it.type })
        assertEquals("q1", result.input[0].content!!.single().text)
        assertEquals("q2", result.input[1].content!!.single().text)
    }

    @Test
    fun `store false with a previous id compacts nothing and warns`() {
        val result = convert(
            listOf(
                userText("q1"),
                ModelMessage.Assistant(
                    listOf(AssistantPart.Text("old answer", providerOptions = googleMeta("interactionId" to previous))),
                ),
                userText("q2"),
            ),
            previousInteractionId = previous,
            store = false,
        )
        assertEquals(3, result.input.size)
        assertEquals(
            listOf(
                Warning.Other(
                    "google.interactions: providerOptions.google.previousInteractionId was set together with store: " +
                        "false. These are incoherent (the prior interaction cannot be referenced when nothing was stored " +
                        "on the server); the full history will be sent and previous_interaction_id will still be emitted.",
                ),
            ),
            result.warnings,
        )
    }

    @Test
    fun `without a previous id a stale interaction id compacts nothing`() {
        val result = convert(
            listOf(
                userText("q1"),
                ModelMessage.Assistant(
                    listOf(AssistantPart.Text("old answer", providerOptions = googleMeta("interactionId" to previous))),
                ),
                userText("q2"),
            ),
            store = false,
        )
        assertEquals(3, result.input.size)
        assertTrue(result.warnings.isEmpty())
    }

    private fun JsonElement.stepContent(step: Int, block: Int): JsonObject {
        val steps = this as JsonArray
        val content = (steps[step] as JsonObject)["content"] as JsonArray
        return content[block] as JsonObject
    }

}
