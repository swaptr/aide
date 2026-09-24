package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.OutgoingContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The Converse path: the one Bedrock wire for every hosted vendor that is not Anthropic.
 *
 * The frames here are AWS binary event-stream messages whose `:event-type` header names the Converse
 * event and whose payload is that event's JSON — unlike the invoke path, nothing is base64-wrapped.
 */
class BedrockConverseTest {

    private var lastRequest: HttpRequestData? = null

    /** One event-stream frame carrying [json] under `:event-type` = [type]. */
    private fun event(type: String, json: String): ByteArray =
        frame(listOf(":event-type" to type, ":message-type" to "event"), json)

    /** An exception frame — the shape Bedrock uses for a mid-stream failure. */
    private fun exception(type: String, json: String): ByteArray =
        frame(listOf(":exception-type" to type, ":message-type" to "exception"), json)

    private fun frame(headers: List<Pair<String, String>>, json: String): ByteArray {
        val payload = json.encodeToByteArray()
        var headerBytes = ByteArray(0)
        headers.forEach { (k, v) ->
            headerBytes += byteArrayOf(k.length.toByte()) + k.encodeToByteArray() + byteArrayOf(7) +
                byteArrayOf((v.length shr 8).toByte(), v.length.toByte()) + v.encodeToByteArray()
        }
        val total = PRELUDE + headerBytes.size + payload.size + 4
        val prelude = int(total) + int(headerBytes.size)
        val message = prelude + int(crc32(prelude)) + headerBytes + payload
        return message + int(crc32(message))
    }

    private fun int(v: Int) =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun crc32(bytes: ByteArray): Int {
        val table = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
            c
        }
        var crc = -1
        bytes.forEach { crc = table[(crc xor it.toInt()) and 0xFF] xor (crc ushr 8) }
        return crc.inv()
    }

    private val stop = event("messageStop", """{"stopReason":"end_turn"}""")

    private fun model(
        frames: ByteArray,
        modelId: String = "us.amazon.nova-pro-v1:0",
        modelFamily: String? = null,
    ) = BedrockConverseLanguageModel(
        modelId = modelId,
        http = ProviderHttp(
            HttpClient(
                MockEngine { request ->
                    lastRequest = request
                    respond(content = frames)
                },
            ),
        ),
        credentials = { AwsCredentials("AKIAIOSFODNN7EXAMPLE", "secret") },
        region = "us-east-1",
        now = { 1_705_314_645_000L },
        modelFamily = modelFamily,
    )

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))))

    private fun sentBody() =
        parseJsonObject((lastRequest!!.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())

    // --- Request shape -----------------------------------------------------------------------------

    @Test
    fun `the url names converse-stream and an ARN survives encoding`() = runTest {
        assembleGenerateResult(model(stop).doStream(call).stream)
        assertTrue(
            lastRequest!!.url.toString().endsWith("/model/us.amazon.nova-pro-v1%3A0/converse-stream"),
            lastRequest!!.url.toString(),
        )

        val arn = "arn:aws:bedrock:us-east-1:123:application-inference-profile/abc"
        assembleGenerateResult(model(stop, modelId = arn).doStream(call).stream)
        // An un-encoded ARN's slashes and colons change the request PATH, which is a 404 on a healthy
        // model — and a different path breaks the SigV4 canonical request too.
        assertTrue(
            "application-inference-profile%2Fabc" in lastRequest!!.url.toString(),
            lastRequest!!.url.toString(),
        )
    }

    @Test
    fun `converse speaks camelCase, unlike the anthropic invoke body`() = runTest {
        val options = call.copy(maxOutputTokens = 100, temperature = 0.5, topP = 0.9, topK = 40, stopSequences = listOf("END"))
        assembleGenerateResult(model(stop).doStream(options).stream)

        val inference = sentBody()["inferenceConfig"]!!.jsonObject
        assertEquals(100, inference["maxTokens"]!!.jsonPrimitive.int)
        assertEquals(0.5, inference["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.9, inference["topP"]!!.jsonPrimitive.content.toDouble())
        assertEquals(40, inference["topK"]!!.jsonPrimitive.int)
        assertEquals("END", inference["stopSequences"]!!.jsonArray.single().jsonPrimitive.content)
        val message = sentBody()["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("hi", message["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `temperature is clamped to 1 and the knobs converse lacks warn instead of leaking`() = runTest {
        val options = call.copy(temperature = 1.5, frequencyPenalty = 0.5, presencePenalty = 0.5, seed = 42)
        val result = assembleGenerateResult(model(stop).doStream(options).stream)

        assertEquals(1.0, sentBody()["inferenceConfig"]!!.jsonObject["temperature"]!!.jsonPrimitive.content.toDouble())
        val features = result.warnings.map {
            when (it) {
                is Warning.Unsupported -> it.feature
                is Warning.Compatibility -> it.feature
                else -> ""
            }
        }
        assertTrue("temperature" in features, features.toString())
        assertTrue("frequencyPenalty" in features && "presencePenalty" in features && "seed" in features)
        // None of them reached the wire.
        assertNull(sentBody()["inferenceConfig"]!!.jsonObject["seed"])
    }

    @Test
    fun `tools become toolSpec entries and the choice maps to converse's vocabulary`() = runTest {
        val tools = listOf(
            Tool.Function("alpha", buildJsonObject { put("type", "object") }),
            Tool.Function("beta", buildJsonObject { put("type", "object") }),
        )
        assembleGenerateResult(
            model(stop).doStream(call.copy(tools = tools, toolChoice = ToolChoice.Required)).stream,
        )
        val config = sentBody()["toolConfig"]!!.jsonObject
        assertEquals(2, config["tools"]!!.jsonArray.size)
        assertEquals(
            "alpha",
            config["tools"]!!.jsonArray[0].jsonObject["toolSpec"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertTrue("any" in config["toolChoice"]!!.jsonObject)

        // A named tool is pinned by sending ONLY that tool plus the named choice.
        assembleGenerateResult(
            model(stop).doStream(call.copy(tools = tools, toolChoice = ToolChoice.Specific("beta"))).stream,
        )
        val pinned = sentBody()["toolConfig"]!!.jsonObject
        assertEquals(1, pinned["tools"]!!.jsonArray.size)
        assertEquals(
            "beta",
            pinned["toolChoice"]!!.jsonObject["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )

        // `none` means no tools at all, not an empty choice.
        assembleGenerateResult(
            model(stop).doStream(call.copy(tools = tools, toolChoice = ToolChoice.None)).stream,
        )
        assertNull(sentBody()["toolConfig"])
    }

    @Test
    fun `a json response format becomes a forced json tool whose input reads back as the answer`() = runTest {
        val frames =
            event(
                "contentBlockStart",
                """{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"t1","name":"json"}}}""",
            ) +
                event(
                    "contentBlockDelta",
                    """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"{\"answer\":42}"}}}""",
                ) +
                event("contentBlockStop", """{"contentBlockIndex":0}""") +
                event("messageStop", """{"stopReason":"tool_use"}""")

        val options = call.copy(
            responseFormat = ResponseFormat.Json(schema = buildJsonObject { put("type", "object") }),
        )
        val result = assembleGenerateResult(model(frames).doStream(options).stream)

        val config = sentBody()["toolConfig"]!!.jsonObject
        assertEquals(
            "json",
            config["tools"]!!.jsonArray.single().jsonObject["toolSpec"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertTrue("any" in config["toolChoice"]!!.jsonObject)
        // The tool call IS the answer: text out, and a Stop finish — `tool-calls` would send any loop
        // keyed on the finish reason off to look for results that do not exist.
        assertEquals("""{"answer":42}""", (result.content.single() as Content.Text).text)
        assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)
        assertEquals("tool_use", result.finishReason.raw)
    }

    // --- Reasoning ---------------------------------------------------------------------------------

    @Test
    fun `signed reasoning replays untrimmed and unsigned reasoning is dropped`() = runTest {
        val prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("hi"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning(
                        text = "signed thought ",
                        providerOptions = mapOf(
                            BEDROCK_PROVIDER_ID to buildJsonObject { put("signature", "sig123") },
                        ),
                    ),
                    AssistantPart.Reasoning(text = "unsigned thought"),
                    AssistantPart.Reasoning(
                        text = "",
                        providerOptions = mapOf(
                            "bedrock" to buildJsonObject { put("redactedData", "opaque") },
                        ),
                    ),
                    AssistantPart.Text("answer"),
                ),
            ),
            ModelMessage.User(listOf(UserPart.Text("again"))),
        )
        assembleGenerateResult(model(stop).doStream(call.copy(prompt = prompt)).stream)

        val assistant = sentBody()["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        val reasoningBlocks = assistant.mapNotNull { it.jsonObject["reasoningContent"]?.jsonObject }
        // Unsigned reasoning is deliberately NOT replayed — some hosted models return reasoning with no
        // signature, and replaying it can leak raw reasoning into the visible response.
        assertEquals(2, reasoningBlocks.size, assistant.toString())
        val signed = reasoningBlocks[0]["reasoningText"]!!.jsonObject
        // A signature validates the exact original bytes, so the text keeps its trailing space.
        assertEquals("signed thought ", signed["text"]!!.jsonPrimitive.content)
        assertEquals("sig123", signed["signature"]!!.jsonPrimitive.content)
        // The reference's legacy `bedrock` namespace is read too.
        assertEquals(
            "opaque",
            reasoningBlocks[1]["redactedReasoning"]!!.jsonObject["data"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a streamed signature lands in provider metadata and redaction accumulates onto the end`() = runTest {
        val frames =
            // A bare contentBlockStart does not reveal the block's kind; the reasoning deltas that
            // follow must still land in a reasoning block, not a text one.
            event("contentBlockStart", """{"contentBlockIndex":0}""") +
                event(
                    "contentBlockDelta",
                    """{"contentBlockIndex":0,"delta":{"reasoningContent":{"text":"thinking"}}}""",
                ) +
                event(
                    "contentBlockDelta",
                    """{"contentBlockIndex":0,"delta":{"reasoningContent":{"signature":"sig456"}}}""",
                ) +
                event("contentBlockStop", """{"contentBlockIndex":0}""") +
                event(
                    "contentBlockDelta",
                    """{"contentBlockIndex":1,"delta":{"reasoningContent":{"redactedContent":"abc"}}}""",
                ) +
                event(
                    "contentBlockDelta",
                    """{"contentBlockIndex":1,"delta":{"reasoningContent":{"redactedContent":"def"}}}""",
                ) +
                event("contentBlockStop", """{"contentBlockIndex":1}""") +
                stop

        val result = assembleGenerateResult(model(frames).doStream(call).stream)

        val signed = result.content[0] as Content.Reasoning
        assertEquals("thinking", signed.text)
        assertEquals(
            "sig456",
            signed.providerMetadata?.get(BEDROCK_PROVIDER_ID)?.get("signature")?.jsonPrimitive?.content,
        )
        // Redacted chunks accumulate and attach ONCE on the end part: merged reasoning metadata is
        // last-write-wins, so per-delta metadata would keep only the final chunk.
        val redacted = result.content[1] as Content.Reasoning
        assertEquals(
            "abcdef",
            redacted.providerMetadata?.get(BEDROCK_PROVIDER_ID)?.get("redactedContent")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `an ARN with a thinking budget is treated as Claude`() = runTest {
        val arn = "arn:aws:bedrock:us-east-1:123:application-inference-profile/abc"
        val options = call.copy(
            temperature = 0.7,
            providerOptions = mapOf(
                BEDROCK_PROVIDER_ID to buildJsonObject {
                    putJsonObject("reasoningConfig") {
                        put("type", "enabled")
                        put("budgetTokens", 2048)
                    }
                },
            ),
        )
        val result = assembleGenerateResult(model(stop, modelId = arn).doStream(options).stream)

        val additional = sentBody()["additionalModelRequestFields"]!!.jsonObject
        val thinking = additional["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(2048, thinking["budget_tokens"]!!.jsonPrimitive.int)
        // The budget comes out of maxTokens, so it is added on top of the (defaulted) ceiling…
        assertEquals(2048 + 4096, sentBody()["inferenceConfig"]!!.jsonObject["maxTokens"]!!.jsonPrimitive.int)
        // …and the samplers thinking forbids are dropped out loud.
        assertNull(sentBody()["inferenceConfig"]!!.jsonObject["temperature"])
        assertTrue(result.warnings.any { it is Warning.Unsupported && it.feature == "temperature" })
    }

    @Test
    fun `neutral reasoning finds each family's own effort field`() = runTest {
        val medium = call.copy(reasoning = ReasoningEffort.Medium)

        assembleGenerateResult(model(stop, modelId = "us.amazon.nova-2-v1:0").doStream(medium).stream)
        val nova = sentBody()["additionalModelRequestFields"]!!.jsonObject["reasoningConfig"]!!.jsonObject
        assertEquals("medium", nova["maxReasoningEffort"]!!.jsonPrimitive.content)

        assembleGenerateResult(model(stop, modelId = "openai.gpt-oss-120b-1:0").doStream(medium).stream)
        assertEquals(
            "medium",
            sentBody()["additionalModelRequestFields"]!!.jsonObject["reasoning_effort"]!!.jsonPrimitive.content,
        )

        assembleGenerateResult(model(stop, modelId = "us.openai.gpt-5.6-luna").doStream(medium).stream)
        assertEquals(
            "medium",
            sentBody()["additionalModelRequestFields"]!!.jsonObject["reasoning"]!!
                .jsonObject["effort"]!!.jsonPrimitive.content,
        )

        // A budget on a non-Anthropic model is a warned no-op, not a stray field.
        val result = assembleGenerateResult(
            model(stop).doStream(
                call.copy(
                    providerOptions = mapOf(
                        BEDROCK_PROVIDER_ID to buildJsonObject {
                            putJsonObject("reasoningConfig") { put("budgetTokens", 1024) }
                        },
                    ),
                ),
            ).stream,
        )
        assertNull(sentBody()["additionalModelRequestFields"])
        assertTrue(result.warnings.any { it is Warning.Unsupported && it.feature == "budgetTokens" })
    }

    // --- Stream mechanics --------------------------------------------------------------------------

    @Test
    fun `text and tool blocks stream and an empty tool input becomes an object`() = runTest {
        val frames =
            event("contentBlockStart", """{"contentBlockIndex":0}""") +
                event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"hello"}}""") +
                event("contentBlockStop", """{"contentBlockIndex":0}""") +
                event(
                    "contentBlockStart",
                    """{"contentBlockIndex":1,"start":{"toolUse":{"toolUseId":"tooluse_abc-123","name":"lookup"}}}""",
                ) +
                event("contentBlockStop", """{"contentBlockIndex":1}""") +
                event("messageStop", """{"stopReason":"tool_use"}""")

        val result = assembleGenerateResult(model(frames).doStream(call).stream)

        assertEquals("hello", (result.content[0] as Content.Text).text)
        val toolCall = result.content[1] as Content.ToolCall
        assertEquals("tooluse_abc-123", toolCall.toolCallId)
        // Converse streams no input at all for a no-argument call; the runtime still needs an object.
        assertEquals("{}", toolCall.input)
        assertEquals(FinishReason.Unified.ToolCalls, result.finishReason.unified)
    }

    @Test
    fun `mistral tool ids are squeezed to nine alphanumerics in both directions`() = runTest {
        val frames =
            event(
                "contentBlockStart",
                """{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"tooluse_bpe71yCfRu2b5i-nKGDr5g","name":"f"}}}""",
            ) +
                event("contentBlockStop", """{"contentBlockIndex":0}""") +
                event("messageStop", """{"stopReason":"tool_use"}""")

        val result = assembleGenerateResult(
            model(frames, modelId = "us.mistral.pixtral-large-2502-v1:0").doStream(call).stream,
        )
        val streamedId = (result.content.single() as Content.ToolCall).toolCallId
        assertEquals("toolusebp", streamedId)
        assertEquals(9, streamedId.length)

        // The replay squeezes identically, so the call and its result still correlate.
        val prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("hi"))),
            ModelMessage.Assistant(
                listOf(AssistantPart.ToolCall("tooluse_bpe71yCfRu2b5i-nKGDr5g", "f", "{}")),
            ),
            ModelMessage.Tool(
                listOf(
                    com.sabreware.aide.aisdk.ToolPart.Result(
                        toolCallId = "tooluse_bpe71yCfRu2b5i-nKGDr5g",
                        toolName = "f",
                        output = com.sabreware.aide.aisdk.ToolOutput.Text("done"),
                    ),
                ),
            ),
        )
        assembleGenerateResult(
            model(stop, modelId = "us.mistral.pixtral-large-2502-v1:0")
                .doStream(call.copy(prompt = prompt, tools = listOf(Tool.Function("f", buildJsonObject {}))))
                .stream,
        )
        val body = sentBody()
        val toolUse = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
            .single().jsonObject["toolUse"]!!.jsonObject
        assertEquals("toolusebp", toolUse["toolUseId"]!!.jsonPrimitive.content)
        val toolResult = body["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray
            .single().jsonObject["toolResult"]!!.jsonObject
        assertEquals("toolusebp", toolResult["toolUseId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `usage math re-adds the cache tokens and files the vendor extras under the provider id`() = runTest {
        val frames =
            event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"ok"}}""") +
                event("contentBlockStop", """{"contentBlockIndex":0}""") +
                stop +
                event(
                    "metadata",
                    """{"usage":{"inputTokens":10,"outputTokens":5,"cacheReadInputTokens":2,""" +
                        """"cacheWriteInputTokens":3},"serviceTier":{"type":"priority"}}""",
                )

        val result = assembleGenerateResult(model(frames).doStream(call).stream)

        // Converse reports cache tokens OUTSIDE inputTokens; the total re-adds them.
        assertEquals(15, result.usage.inputTokens.total)
        assertEquals(10, result.usage.inputTokens.noCache)
        assertEquals(2, result.usage.inputTokens.cacheRead)
        assertEquals(3, result.usage.inputTokens.cacheWrite)
        val metadata = result.providerMetadata?.get(BEDROCK_PROVIDER_ID)
        assertEquals(
            "priority",
            metadata?.get("serviceTier")?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
        assertEquals(
            3,
            metadata?.get("usage")?.jsonObject?.get("cacheWriteInputTokens")?.jsonPrimitive?.int,
        )
    }

    @Test
    fun `a validation exception fails doGenerate and is not retryable`() = runTest {
        val frames = exception("validationException", """{"message":"bad request shape"}""")

        val error = assertFailsWith<APICallError> {
            assembleGenerateResult(model(frames).doStream(call).stream)
        }
        assertEquals("bad request shape", error.message)
        assertEquals(400, error.statusCode)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `cache points, service tier and unknown vendor fields ride onto the command`() = runTest {
        val options = call.copy(
            prompt = listOf(
                ModelMessage.User(
                    listOf(
                        UserPart.Text(
                            "hi",
                            providerOptions = mapOf(
                                BEDROCK_PROVIDER_ID to buildJsonObject {
                                    putJsonObject("cachePoint") { put("type", "default") }
                                },
                            ),
                        ),
                    ),
                ),
            ),
            providerOptions = mapOf(
                BEDROCK_PROVIDER_ID to buildJsonObject {
                    put("serviceTier", "flex")
                    putJsonObject("guardrailConfig") { put("guardrailIdentifier", "g1") }
                },
            ),
        )
        assembleGenerateResult(model(stop).doStream(options).stream)

        val body = sentBody()
        assertEquals("flex", body["serviceTier"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // A Converse field this port has never heard of still reaches the wire.
        assertEquals(
            "g1",
            body["guardrailConfig"]!!.jsonObject["guardrailIdentifier"]!!.jsonPrimitive.content,
        )
        val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(
            "default",
            content[1].jsonObject["cachePoint"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    // --- Stop reasons ------------------------------------------------------------------------------

    @Test
    fun `every stop reason Converse documents maps to something a loop can branch on`() = runTest {
        // The full documented set, checked 2026-09-01 against
        // docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html. The last three
        // were added after this port was written and fell through to `Other` — nothing was lost,
        // since `raw` is always kept, but two failures and a ceiling all read as one unclassified stop.
        val expected = mapOf(
            "end_turn" to FinishReason.Unified.Stop,
            "stop_sequence" to FinishReason.Unified.Stop,
            "max_tokens" to FinishReason.Unified.Length,
            "model_context_window_exceeded" to FinishReason.Unified.Length,
            "content_filtered" to FinishReason.Unified.ContentFilter,
            "guardrail_intervened" to FinishReason.Unified.ContentFilter,
            "malformed_model_output" to FinishReason.Unified.Error,
            "malformed_tool_use" to FinishReason.Unified.Error,
            "tool_use" to FinishReason.Unified.ToolCalls,
        )

        for ((raw, unified) in expected) {
            val frames = event("messageStop", """{"stopReason":"$raw"}""")
            val result = assembleGenerateResult(model(frames).doStream(call).stream)
            assertEquals(unified, result.finishReason.unified, "unified for $raw")
            // The vendor's own word survives the mapping in every case.
            assertEquals(raw, result.finishReason.raw, "raw for $raw")
        }
    }

    @Test
    fun `a stop reason this port has never seen stays Other with its raw word intact`() = runTest {
        val frames = event("messageStop", """{"stopReason":"some_future_reason"}""")

        val result = assembleGenerateResult(model(frames).doStream(call).stream)

        // Deliberately not enumerated away: AWS has added three values already, and the next one
        // should reach the caller as an unknown rather than as a wrong guess.
        assertEquals(FinishReason.Unified.Other, result.finishReason.unified)
        assertEquals("some_future_reason", result.finishReason.raw)
    }

    // --- The 2026-09 upstream delta ------------------------------------------------------------

    private val nameSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("name") { put("type", "string") } }
        put("required", JsonArray(listOf(JsonPrimitive("name"))))
    }

    private val nativeFormat = ProviderJson.parseToJsonElement(
        """{"type":"json_schema","schema":{"type":"object","additionalProperties":false,
            "properties":{"name":{"type":"string"}},"required":["name"]}}""",
    )

    /** The json tool answering `{"name":"Test"}` — the reference's response for every JSON-tool case. */
    private val jsonToolAnswer =
        event("contentBlockStart", """{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"json-tool-id","name":"json"}}}""") +
            event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"{\"name\":\"Test\"}"}}}""") +
            event("contentBlockStop", """{"contentBlockIndex":0}""") +
            event("messageStop", """{"stopReason":"tool_use"}""")

    private fun JsonObject.outputFormat() =
        this["additionalModelRequestFields"]?.jsonObject?.get("output_config")?.jsonObject?.get("format")

    @Test
    fun `document filenames are sanitized to what Bedrock accepts`() = runTest {
        // Reference `770c214`: an apostrophe was enough for Bedrock to reject the whole request.
        val filenames = listOf(
            "John's report.txt", "invoice #123.txt", "a&b.txt", "report,2026.txt", "résumé.txt", "분기보고서.txt",
            "Report -  Final.txt", "a\tb.txt", "a".repeat(201) + ".txt", ".txt", "report (final) [v2]_draft.txt",
        )
        val prompt = listOf(
            ModelMessage.User(
                filenames.map { name ->
                    UserPart.File(data = FileData.Bytes("pdf".encodeToByteArray()), mediaType = "application/pdf", filename = name)
                },
            ),
        )
        assembleGenerateResult(model(stop).doStream(CallOptions(prompt = prompt)).stream)

        val names = sentBody()["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            .map { it.jsonObject["document"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                "Johns report", "invoice 123", "ab", "report2026", "rsum", "document-1", "Report - Final", "a b",
                "a".repeat(200), "document-2", "report (final) [v2]draft",
            ),
            names,
        )
    }

    @Test
    fun `text document filenames are sanitized too`() = runTest {
        val prompt = listOf(
            ModelMessage.User(
                listOf(UserPart.File(data = FileData.Text("Hello"), mediaType = "text/plain", filename = "John's  report.txt")),
            ),
        )
        assembleGenerateResult(model(stop).doStream(CallOptions(prompt = prompt)).stream)

        assertEquals(
            parseJsonObject("""{"document":{"format":"txt","name":"Johns report","source":{"bytes":"SGVsbG8="}}}"""),
            sentBody()["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single(),
        )
    }

    private fun add(id: String, a: Int, b: Int) = AssistantPart.ToolCall(
        toolCallId = id, toolName = "add", input = """{"a":$a,"b":$b}""", providerExecuted = true,
    )

    private fun sum(id: String, sum: Int) = AssistantPart.ToolResult(
        toolCallId = id, toolName = "add", output = ToolOutput.Json(buildJsonObject { put("sum", sum) }),
    )

    @Test
    fun `provider-executed tool calls and results keep their order`() = runTest {
        // Reference `d0b6d6d`: a result inside the assistant turn splits it, so the roles alternate and
        // the model sees the calls and answers in the order it produced them.
        val prompt = listOf(
            ModelMessage.User(listOf(UserPart.Text("Add 2 and 2, then add 3 and 3."))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Text("Running the additions."),
                    add("call-1", 2, 2), sum("call-1", 4),
                    add("call-2", 3, 3), sum("call-2", 6),
                    AssistantPart.Text("The sums are 4 and 6."),
                ),
            ),
            ModelMessage.User(listOf(UserPart.Text("Now add those sums together."))),
        )
        assembleGenerateResult(model(stop).doStream(CallOptions(prompt = prompt)).stream)

        assertEquals(
            ProviderJson.parseToJsonElement(
                """[
                  {"role":"user","content":[{"text":"Add 2 and 2, then add 3 and 3."}]},
                  {"role":"assistant","content":[{"text":"Running the additions."},
                    {"toolUse":{"toolUseId":"call-1","name":"add","input":{"a":2,"b":2}}}]},
                  {"role":"user","content":[{"toolResult":{"toolUseId":"call-1","content":[{"text":"{\"sum\":4}"}]}}]},
                  {"role":"assistant","content":[{"toolUse":{"toolUseId":"call-2","name":"add","input":{"a":3,"b":3}}}]},
                  {"role":"user","content":[{"toolResult":{"toolUseId":"call-2","content":[{"text":"{\"sum\":6}"}]}}]},
                  {"role":"assistant","content":[{"text":"The sums are 4 and 6."}]},
                  {"role":"user","content":[{"text":"Now add those sums together."}]}
                ]""",
            ),
            sentBody()["messages"],
        )
    }

    @Test
    fun `a trailing provider-executed tool result joins the next user message`() = runTest {
        val prompt = listOf(
            ModelMessage.Assistant(listOf(add("call-1", 2, 2), sum("call-1", 4))),
            ModelMessage.User(listOf(UserPart.Text("Now double that."))),
        )
        assembleGenerateResult(model(stop).doStream(CallOptions(prompt = prompt)).stream)

        assertEquals(
            ProviderJson.parseToJsonElement(
                """[
                  {"role":"assistant","content":[{"toolUse":{"toolUseId":"call-1","name":"add","input":{"a":2,"b":2}}}]},
                  {"role":"user","content":[{"toolResult":{"toolUseId":"call-1","content":[{"text":"{\"sum\":4}"}]}},
                    {"text":"Now double that."}]}
                ]""",
            ),
            sentBody()["messages"],
        )
    }

    @Test
    fun `the json tool is the default for sonnet 4_6 and haiku 4_5`() = runTest {
        // Reference `bd74b49`: both accept the native format, neither serves it reliably.
        listOf("anthropic.claude-sonnet-4-6-v1", "us.anthropic.claude-haiku-4-5-20251001-v1:0").forEach { id ->
            val result = assembleGenerateResult(
                model(jsonToolAnswer, modelId = id).doStream(call.copy(responseFormat = ResponseFormat.Json(nameSchema))).stream,
            )

            val body = sentBody()
            val config = body["toolConfig"]!!.jsonObject
            assertEquals("json", config["tools"]!!.jsonArray.single().jsonObject["toolSpec"]!!.jsonObject["name"]!!.jsonPrimitive.content, id)
            assertEquals(parseJsonObject("""{"any":{}}"""), config["toolChoice"], id)
            assertNull(body.outputFormat(), id)
            assertEquals("""{"name":"Test"}""", (result.content.single() as Content.Text).text, id)
            assertEquals(FinishReason.Unified.Stop, result.finishReason.unified, id)
            assertEquals(
                JsonPrimitive(true),
                result.providerMetadata!!.getValue(BEDROCK_PROVIDER_ID)["isJsonResponseFromTool"],
                id,
            )
        }
    }

    @Test
    fun `structuredOutputMode jsonTool from any namespace forces the json tool`() = runTest {
        listOf(BEDROCK_PROVIDER_ID, "bedrock", "anthropic").forEach { namespace ->
            val options = call.copy(
                responseFormat = ResponseFormat.Json(nameSchema),
                providerOptions = mapOf(namespace to buildJsonObject { put("structuredOutputMode", "jsonTool") }),
            )
            assembleGenerateResult(model(jsonToolAnswer, modelId = "anthropic.claude-sonnet-4-6-v1").doStream(options).stream)

            val body = sentBody()
            assertEquals(parseJsonObject("""{"any":{}}"""), body["toolConfig"]!!.jsonObject["toolChoice"], namespace)
            assertNull(body["structuredOutputMode"], namespace)
            assertNull(body.outputFormat(), namespace)
        }
    }

    @Test
    fun `jsonTool mode strips a manual output_config format but keeps its siblings`() = runTest {
        val options = call.copy(
            responseFormat = ResponseFormat.Json(nameSchema),
            providerOptions = mapOf(
                BEDROCK_PROVIDER_ID to buildJsonObject {
                    put("structuredOutputMode", "jsonTool")
                    putJsonObject("additionalModelRequestFields") {
                        putJsonObject("output_config") {
                            put("effort", "medium")
                            putJsonObject("format") { put("type", "manually-supplied-format") }
                        }
                    }
                },
            ),
        )
        assembleGenerateResult(model(jsonToolAnswer, modelId = "anthropic.claude-sonnet-4-6-v1").doStream(options).stream)

        assertEquals(
            parseJsonObject("""{"effort":"medium"}"""),
            sentBody()["additionalModelRequestFields"]!!.jsonObject["output_config"],
        )
    }

    @Test
    fun `outputFormat forces the native format on a model auto routes to the json tool`() = runTest {
        val options = call.copy(
            responseFormat = ResponseFormat.Json(nameSchema),
            providerOptions = mapOf(BEDROCK_PROVIDER_ID to buildJsonObject { put("structuredOutputMode", "outputFormat") }),
        )
        assembleGenerateResult(model(stop, modelId = "us.anthropic.claude-opus-5").doStream(options).stream)

        assertNull(sentBody()["toolConfig"])
        assertEquals(nativeFormat, sentBody().outputFormat())
    }

    @Test
    fun `amazon-bedrock's structuredOutputMode outranks anthropic's`() = runTest {
        val options = call.copy(
            responseFormat = ResponseFormat.Json(nameSchema),
            providerOptions = mapOf(
                BEDROCK_PROVIDER_ID to buildJsonObject { put("structuredOutputMode", "jsonTool") },
                "anthropic" to buildJsonObject { put("structuredOutputMode", "outputFormat") },
            ),
        )
        assembleGenerateResult(model(jsonToolAnswer, modelId = "anthropic.claude-sonnet-4-6-v1").doStream(options).stream)

        assertEquals(parseJsonObject("""{"any":{}}"""), sentBody()["toolConfig"]!!.jsonObject["toolChoice"])
        assertNull(sentBody().outputFormat())
    }

    @Test
    fun `a model with reliable native structured output uses the format without thinking`() = runTest {
        assembleGenerateResult(
            model(stop, modelId = "anthropic.claude-sonnet-4-5-20250929-v1:0")
                .doStream(call.copy(responseFormat = ResponseFormat.Json(nameSchema))).stream,
        )

        assertNull(sentBody()["toolConfig"])
        assertEquals(nativeFormat, sentBody().outputFormat())
    }

    @Test
    fun `a declared anthropic family gives an ARN native structured output`() = runTest {
        // Reference `d82eac28`: an ARN says nothing about its model, so the caller's word is the signal.
        val arn = "arn:aws:bedrock:us-east-1:123456789012:application-inference-profile/custom-profile"
        listOf("outputFormat", "auto").forEach { mode ->
            val options = call.copy(
                responseFormat = ResponseFormat.Json(nameSchema),
                providerOptions = mapOf(BEDROCK_PROVIDER_ID to buildJsonObject { put("structuredOutputMode", mode) }),
            )
            assembleGenerateResult(
                model(stop, modelId = arn, modelFamily = BEDROCK_MODEL_FAMILY_ANTHROPIC).doStream(options).stream,
            )

            assertNull(sentBody()["toolConfig"], mode)
            assertEquals(nativeFormat, sentBody().outputFormat(), mode)
        }
    }

    @Test
    fun `the 2026-03-18 web tools are refused with the reference's warning`() = runTest {
        listOf("anthropic.web_search_20260318" to "web_search", "anthropic.web_fetch_20260318" to "web_fetch").forEach { (id, name) ->
            val options = call.copy(tools = listOf(Tool.ProviderDefined(name = name, id = id, args = JsonObject(emptyMap()))))
            val result = assembleGenerateResult(model(stop, modelId = "anthropic.claude-sonnet-4-6-v1").doStream(options).stream)

            val toolType = id.removePrefix("anthropic.")
            assertNull(sentBody()["toolConfig"], id)
            assertEquals(
                listOf(Warning.Unsupported(feature = "$toolType tool", details = "The $toolType tool is not supported on Amazon Bedrock.")),
                result.warnings,
                id,
            )
        }
    }

    @Test
    fun `strict stays on for sonnet 4_6 and haiku 4_5 and is dropped with a warning for fable 5_1`() = runTest {
        val strictTool = Tool.Function("testFunction", buildJsonObject { put("type", "object") }, description = "A test function", strict = true)
        listOf("anthropic.claude-sonnet-4-6-v1", "us.anthropic.claude-haiku-4-5-20251001-v1:0").forEach { id ->
            val result = assembleGenerateResult(model(stop, modelId = id).doStream(call.copy(tools = listOf(strictTool))).stream)

            val spec = sentBody()["toolConfig"]!!.jsonObject["tools"]!!.jsonArray.single().jsonObject["toolSpec"]!!.jsonObject
            assertEquals(JsonPrimitive(true), spec["strict"], id)
            assertTrue(result.warnings.isEmpty(), "$id: ${result.warnings}")
        }

        val result = assembleGenerateResult(
            model(stop, modelId = "global.anthropic.claude-fable-5-1").doStream(call.copy(tools = listOf(strictTool))).stream,
        )
        val spec = sentBody()["toolConfig"]!!.jsonObject["tools"]!!.jsonArray.single().jsonObject["toolSpec"]!!.jsonObject
        assertNull(spec["strict"])
        assertTrue(result.warnings.any { it is Warning.Unsupported && it.feature == "strict" }, result.warnings.toString())
    }

    private companion object {
        const val PRELUDE = 12
    }
}
