package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.InvalidPromptError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The legacy Completions endpoint, against `openai-completion-language-model.test.ts` and its
 * recorded fixtures.
 *
 * What these pin is the flattening — one prompt string, one stop sequence, one text block keyed `0` —
 * and the two places the wire is easy to get quietly wrong: the option set that rides only under
 * OpenAI's names, and a stream whose error frames arrive on an HTTP 200.
 */
class OpenAICompatibleCompletionTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello"))))
    private val call = CallOptions(prompt = prompt)

    private fun model(server: TestServer) =
        Vendors.openAI(HttpClient(server.engine()), "test-api-key").completionModel("gpt-3.5-turbo-instruct")!!

    private fun sse(chunks: List<String>) =
        TestServer(TestServer.sse(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"))

    // --- doGenerate -----------------------------------------------------------------------------------

    @Test
    fun `a document maps to one text part, its finish reason and its usage`() = runTest {
        val server = TestServer(TestServer.json(OpenAICompatibleCompletionFixtures.HELLO_WORLD))

        val result = model(server).doGenerate(call)

        assertEquals(listOf(Content.Text("Hello, World!")), result.content)
        assertEquals(FinishReason(FinishReason.Unified.Stop, "stop"), result.finishReason)
        assertEquals(4, result.usage.inputTokens.total)
        assertEquals(4, result.usage.inputTokens.noCache)
        assertEquals(30, result.usage.outputTokens.total)
        assertEquals(30, result.usage.outputTokens.text)
        assertNull(result.usage.outputTokens.reasoning)
        assertEquals(4, result.usage.raw?.get("prompt_tokens").int())
        assertEquals("openai.completion", model(server).provider)
    }

    @Test
    fun `the request carries the model, the flattened prompt and the stop sequence, and nothing else`() = runTest {
        val server = TestServer(TestServer.json(OpenAICompatibleCompletionFixtures.TEXT))

        model(server).doGenerate(call)

        val request = server.request()
        assertEquals("https://api.openai.com/v1/completions", request.url)
        request.assertHeader("Authorization", "Bearer test-api-key")
        // The reference's inline snapshot: every undefined option is absent, not null.
        request.assertBodyKeys("model", "prompt", "stop")
        request.assertBodyJson { body ->
            assertEquals("gpt-3.5-turbo-instruct", body["model"].string())
            assertEquals("user:\nHello\n\nassistant:\n", body["prompt"].string())
            assertEquals(listOf("\nuser:"), body["stop"]!!.jsonArray.map { it.string() })
        }
    }

    @Test
    fun `response metadata comes off the document, with created in seconds`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAICompatibleCompletionFixtures.METADATA).withHeaders("test-header" to "test-value"),
        )

        val response = model(server).doGenerate(call).response!!

        assertEquals("test-id", response.metadata.id)
        assertEquals(123_000L, response.metadata.timestamp)
        assertEquals("test-model", response.metadata.modelId)
        assertEquals("test-value", response.headers?.get("test-header"))
    }

    @Test
    fun `logprobs are asked for in the reference's spelling and filed under the provider's namespace`() = runTest {
        val server = TestServer(TestServer.json(OpenAICompatibleCompletionFixtures.WITH_LOGPROBS))
        val model = model(server)

        val result = model.doGenerate(
            call.copy(providerOptions = mapOf("openai" to buildJsonObject { put("logprobs", 1) })),
        )

        assertEquals(1, server.request(0).bodyJson()["logprobs"].int())
        assertEquals(
            parseJsonObject(OpenAICompatibleCompletionFixtures.LOGPROBS),
            result.providerMetadata?.get("openai")?.obj("logprobs"),
        )

        // `true` is OpenAI's `0` — the chosen token's probability alone; `false` is the same as silence.
        model.doGenerate(call.copy(providerOptions = mapOf("openai" to buildJsonObject { put("logprobs", true) })))
        assertEquals(0, server.request(1).bodyJson()["logprobs"].int())
        model.doGenerate(call.copy(providerOptions = mapOf("openai" to buildJsonObject { put("logprobs", false) })))
        server.request(2).assertBodyMissing("logprobs")
    }

    @Test
    fun `an unknown finish reason is kept verbatim beside other`() = runTest {
        val server = TestServer(TestServer.json(OpenAICompatibleCompletionFixtures.UNKNOWN_FINISH))

        assertEquals(FinishReason(FinishReason.Unified.Other, "eos"), model(server).doGenerate(call).finishReason)
    }

    @Test
    fun `the recorded document reads as the reference reads it`() = runTest {
        val server = TestServer(TestServer.json(OpenAICompatibleCompletionFixtures.TEXT))

        val result = model(server).doGenerate(call)

        assertEquals(
            "The new holiday is called \"Gratitude Day\" and it celebrates the importance of",
            (result.content.single() as Content.Text).text,
        )
        assertEquals(FinishReason(FinishReason.Unified.Length, "length"), result.finishReason)
        assertEquals(14, result.usage.inputTokens.total)
        assertEquals(16, result.usage.outputTokens.total)
        assertEquals("cmpl-D8ZFHlGItjM5Nghki1LmZIRscBz2P", result.response?.metadata?.id)
        assertEquals("gpt-3.5-turbo-instruct:20230824-v2", result.response?.metadata?.modelId)
        assertEquals(1_770_934_479_000L, result.response?.metadata?.timestamp)
    }

    // --- doStream -------------------------------------------------------------------------------------

    @Test
    fun `the recorded stream is one delimited text block with the usage on its tail`() = runTest {
        val server = sse(OpenAICompatibleCompletionFixtures.textChunks)

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(StreamPart.StreamStart(emptyList()), parts[0])
        val metadata = assertIs<StreamPart.ResponseMetadataPart>(parts[1]).metadata
        assertEquals("cmpl-D8ZFN477TMm6AoQohx2jSTOJMh60M", metadata.id)
        assertEquals("gpt-3.5-turbo-instruct:20230824-v2", metadata.modelId)
        assertEquals(1_770_934_485_000L, metadata.timestamp)
        assertEquals(StreamPart.TextStart("0"), parts[2])
        val deltas = parts.filterIsInstance<StreamPart.TextDelta>()
        assertEquals(16, deltas.size)
        assertTrue(deltas.all { it.id == "0" })
        assertEquals(
            "The holiday is called \"Gratitude Day\" and it is a day dedicated to",
            deltas.joinToString("") { it.delta },
        )
        assertEquals(StreamPart.TextEnd("0"), parts[parts.size - 2])
        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(FinishReason(FinishReason.Unified.Length, "length"), finish.finishReason)
        assertEquals(14, finish.usage.inputTokens.total)
        assertEquals(16, finish.usage.outputTokens.total)
        assertEquals(30, finish.usage.raw?.get("total_tokens").int())

        val request = server.request()
        request.assertBodyKeys("model", "prompt", "stop", "stream", "stream_options")
        assertEquals(true, request.bodyJson().obj("stream_options")?.get("include_usage").bool())
    }

    @Test
    fun `streamed logprobs ride on the finish part`() = runTest {
        val server = sse(OpenAICompatibleCompletionFixtures.streamedDeltas)

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(listOf("Hello", ", ", "World!"), parts.filterIsInstance<StreamPart.TextDelta>().map { it.delta })
        val finish = assertIs<StreamPart.Finish>(parts.last())
        assertEquals(FinishReason(FinishReason.Unified.Stop, "stop"), finish.finishReason)
        assertEquals(
            parseJsonObject(OpenAICompatibleCompletionFixtures.LOGPROBS),
            finish.providerMetadata?.get("openai")?.obj("logprobs"),
        )
        assertEquals(10, finish.usage.inputTokens.total)
        assertEquals(362, finish.usage.outputTokens.total)
    }

    @Test
    fun `an error before any output is a stream error, not a thrown one`() = runTest {
        // The reference throws here (`throwIfOpenAIStreamErrorBeforeOutput`). This port reports it as
        // the chat model does — an Error part and an Error finish — because the runtime, not the
        // transport, decides whether an error ends the turn; and no text block is opened for it.
        val server = sse(listOf(OpenAICompatibleCompletionFixtures.ERROR_FIRST))

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(3, parts.size)
        val error = assertIs<APICallError>(assertIs<StreamPart.Error>(parts[1]).error)
        assertTrue(error.message!!.startsWith("The server had an error processing your request."))
        assertTrue(error.isRetryable)
        assertEquals(FinishReason.Unified.Error, assertIs<StreamPart.Finish>(parts[2]).finishReason.unified)
    }

    @Test
    fun `an error after output closes the block and reports the failure`() = runTest {
        val server = sse(OpenAICompatibleCompletionFixtures.errorAfterOutput)

        val parts = model(server).doStream(call).stream.toList()

        assertEquals(
            listOf("StreamStart", "ResponseMetadataPart", "TextStart", "TextDelta", "Error", "TextEnd", "Finish"),
            parts.map { it::class.simpleName },
        )
        assertEquals("stream failed after output", (parts[4] as StreamPart.Error).error.message)
        val finish = parts.last() as StreamPart.Finish
        assertEquals(FinishReason(FinishReason.Unified.Error), finish.finishReason)
        assertEquals(Usage(), finish.usage)
    }

    @Test
    fun `an unparsable frame is an error part, and the turn finishes as an error`() = runTest {
        val server = sse(listOf("{unparsable}"))

        val parts = model(server).doStream(call).stream.toList()

        assertIs<JsonParseError>(assertIs<StreamPart.Error>(parts[1]).error)
        assertEquals(FinishReason.Unified.Error, assertIs<StreamPart.Finish>(parts.last()).finishReason.unified)
    }

    // --- options and vendors --------------------------------------------------------------------------

    @Test
    fun `the reference's options reach the wire under OpenAI's names, and an unlisted one verbatim`() = runTest {
        val server = TestServer(TestServer.json(OpenAICompatibleCompletionFixtures.TEXT))

        model(server).doGenerate(
            call.copy(
                maxOutputTokens = 32,
                temperature = 0.2,
                stopSequences = listOf("END"),
                providerOptions = mapOf(
                    "openai" to buildJsonObject {
                        put("echo", true)
                        put("suffix", " and so on")
                        put("user", "user-1")
                        put("logitBias", buildJsonObject { put("50256", -100) })
                        // Not in the reference's option schema; the compat rule sends it anyway.
                        put("best_of", 2)
                        // Reserved: a caller cannot swap the model out from under the call.
                        put("model", "someone-else")
                    },
                ),
            ),
        )

        val body = server.request().bodyJson()
        assertEquals(true, body["echo"].bool())
        assertEquals(" and so on", body["suffix"].string())
        assertEquals("user-1", body["user"].string())
        assertEquals(-100, body.obj("logit_bias")?.get("50256").int())
        assertEquals(2, body["best_of"].int())
        assertEquals("gpt-3.5-turbo-instruct", body["model"].string())
        assertEquals(32, body["max_tokens"].int())
        assertEquals(0.2, body["temperature"]!!.jsonPrimitive.content.toDouble())
        // The model's own stop sequence comes first, then the caller's.
        assertEquals(listOf("\nuser:", "END"), body["stop"]!!.jsonArray.map { it.string() })
        assertNull(body["logitBias"], "the camelCase spelling must not travel beside its translation")
    }

    @Test
    fun `azure builds the deployment URL and reads options under its own key first`() = runTest {
        val server = TestServer(TestServer.json(OpenAICompatibleCompletionFixtures.TEXT))
        val model = Vendors.azure(HttpClient(server.engine()), "k", resourceName = "r").completionModel("my-deployment")!!

        model.doGenerate(
            call.copy(
                providerOptions = mapOf(
                    "openai" to buildJsonObject { put("suffix", "from openai") },
                    "azure" to buildJsonObject { put("suffix", "from azure") },
                ),
            ),
        )

        val request = server.request()
        assertEquals(
            "https://r.openai.azure.com/openai/deployments/my-deployment/completions?api-version=2024-10-21",
            request.url,
        )
        request.assertHeader("api-key", "k")
        assertNull(request.header("Authorization"))
        assertEquals("azure.completion", model.provider)
        assertEquals("from azure", request.bodyJson()["suffix"].string())
    }

    @Test
    fun `the vendor seams reach the completion model too`() = runTest {
        val server = TestServer(TestServer.json(OpenAICompatibleCompletionFixtures.TEXT))
        val provider = OpenAICompatibleProvider(
            client = HttpClient(server.engine()),
            providerId = "vendor",
            baseUrl = "https://api.vendor.test/v1",
            apiKey = "k",
            modalities = setOf(OpenAICompatibleModality.Completion),
            convertUsage = { raw -> Usage(inputTokens = Usage.InputTokens(total = 99), raw = raw) },
            transformRequestBody = { body -> parseJsonObject(body.toString().replace("\"max_tokens\"", "\"max_completion_tokens\"")) },
        )

        val result = provider.completionModel("m")!!.doGenerate(call.copy(maxOutputTokens = 7))

        assertNull(provider.languageModel("m"), "chat was not declared, so it is not served")
        assertEquals("vendor.completion", provider.completionModel("m")!!.provider)
        assertEquals(99, result.usage.inputTokens.total)
        assertEquals(7, server.request().bodyJson()["max_completion_tokens"].int())
    }

    // --- what the wire cannot carry ---------------------------------------------------------------------

    @Test
    fun `everything the wire has no field for is warned about, never dropped in silence`() = runTest {
        val server = TestServer(TestServer.json(OpenAICompatibleCompletionFixtures.TEXT))

        val result = model(server).doGenerate(
            call.copy(
                prompt = listOf(
                    ModelMessage.User(
                        listOf(
                            UserPart.Text("Hello"),
                            UserPart.File(FileData.Url("https://img/cat.png"), "image/png"),
                        ),
                    ),
                ),
                topK = 3,
                tools = listOf(Tool.Function("lookup", buildJsonObject { put("type", "object") })),
                toolChoice = ToolChoice.Auto,
                responseFormat = ResponseFormat.Json(),
                reasoning = ReasoningEffort.High,
            ),
        )

        result.warnings.assertUnsupported("topK")
        result.warnings.assertUnsupported("tools")
        result.warnings.assertUnsupported("toolChoice")
        result.warnings.assertUnsupported("responseFormat", "JSON response format is not supported.")
        result.warnings.assertUnsupported("reasoningEffort")
        // The reference filters the image out and says nothing; here the caller is told.
        result.warnings.assertUnsupported(
            "non-text parts",
            "The legacy Completions API takes one prompt string; a image/png attachment was dropped.",
        )
        assertEquals(6, result.warnings.filterIsInstance<Warning.Unsupported>().size)
        assertEquals("user:\nHello\n\nassistant:\n", server.request().bodyJson()["prompt"].string())
    }

    @Test
    fun `the prompt flattens exactly as the reference flattens it`() {
        val converted = listOf(
            ModelMessage.System("Be terse."),
            ModelMessage.User(listOf(UserPart.Text("Hi"))),
            ModelMessage.Assistant(listOf(AssistantPart.Text("Hello"), AssistantPart.Reasoning("(thinking)"))),
            ModelMessage.User(listOf(UserPart.Text("More")))
        ).toCompletionPrompt()

        assertEquals("Be terse.\n\nuser:\nHi\n\nassistant:\nHello\n\nuser:\nMore\n\nassistant:\n", converted.prompt)
        assertEquals(listOf("\nuser:"), converted.stopSequences)
        assertTrue(converted.warnings.isEmpty(), "reasoning has nothing to replay here and is not a warning")
    }

    @Test
    fun `what has no textual form throws, as it does in the reference`() {
        assertFailsWith<InvalidPromptError> {
            listOf(ModelMessage.User(listOf(UserPart.Text("a"))), ModelMessage.System("late")).toCompletionPrompt()
        }
        assertEquals(
            "tool messages",
            assertFailsWith<UnsupportedFunctionalityError> {
                listOf(
                    ModelMessage.Tool(listOf(ToolPart.Result("c1", "lookup", ToolOutput.Text("x")))),
                ).toCompletionPrompt()
            }.functionality,
        )
        assertEquals(
            "tool-call messages",
            assertFailsWith<UnsupportedFunctionalityError> {
                listOf(
                    ModelMessage.Assistant(listOf(AssistantPart.ToolCall("c1", "lookup", "{}"))),
                ).toCompletionPrompt()
            }.functionality,
        )
    }

    @Test
    fun `usage keeps the reference's zero-for-absent asymmetry`() {
        val usage = completionUsage(parseJsonObject("""{"total_tokens":9}"""))

        // `total` says the vendor did not report it; `noCache` and `text` read as the reference's `?? 0`.
        assertNull(usage.inputTokens.total)
        assertEquals(0, usage.inputTokens.noCache)
        assertNull(usage.outputTokens.total)
        assertEquals(0, usage.outputTokens.text)
    }

    // --- ai@7.0.102 -----------------------------------------------------------------------------

    @Test
    fun `a document with no choices is a typed error, not a crash`() = runTest {
        // The chat model's fixture (`ccb8952`), on the Completions document shape.
        val server = TestServer(
            TestServer.json(
                """{"id":"cmpl-empty","object":"text_completion","created":1711115037,""" +
                    """"model":"gpt-3.5-turbo-instruct","choices":[],""" +
                    """"usage":{"prompt_tokens":4,"total_tokens":4,"completion_tokens":0}}""",
            ),
        )

        val error = assertFailsWith<InvalidResponseDataError> { model(server).doGenerate(call) }

        assertEquals("Response did not contain any choices.", error.message)
    }

    @Test
    fun `a stream that never carries a choice ends as that error, not as a missing finish reason`() = runTest {
        val server = sse(
            listOf(
                """{"id":"cmpl-empty","object":"text_completion","created":1711115037,""" +
                    """"model":"gpt-3.5-turbo-instruct","choices":[]}""",
            ),
        )

        val parts = model(server).doStream(call).stream.toList()

        val error = assertIs<InvalidResponseDataError>(parts.filterIsInstance<StreamPart.Error>().single().error)
        assertEquals("Response did not contain any choices.", error.message)
        assertEquals(FinishReason.Unified.Error, assertIs<StreamPart.Finish>(parts.last()).finishReason.unified)
    }
}
