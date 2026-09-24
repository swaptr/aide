package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.BatchCancelResult
import com.sabreware.aide.aisdk.BatchError
import com.sabreware.aide.aisdk.BatchItemResult
import com.sabreware.aide.aisdk.BatchLanguageModel
import com.sabreware.aide.aisdk.BatchListItem
import com.sabreware.aide.aisdk.BatchListOptions
import com.sabreware.aide.aisdk.BatchListResult
import com.sabreware.aide.aisdk.BatchOperationOptions
import com.sabreware.aide.aisdk.BatchRequest
import com.sabreware.aide.aisdk.BatchRequestCounts
import com.sabreware.aide.aisdk.BatchRequestType
import com.sabreware.aide.aisdk.BatchStartOptions
import com.sabreware.aide.aisdk.BatchStartResult
import com.sabreware.aide.aisdk.BatchStatus
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.ImageUsage
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.MissingToolResultsError
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The runtime side of a batch, against a scripted [BatchLanguageModel].
 *
 * Shapes and values are the reference's `batch.test.ts`; what is asserted on the model side is the
 * request it was handed, because a wrapper that standardizes a prompt wrongly produces a batch that
 * fails a day later with an error naming nothing.
 */
class BatchTest {

    /** Records what it was asked and answers from a script — the batch twin of the loop tests' ScriptedModel. */
    private class ScriptedBatchModel(
        private val start: BatchStartResult = BatchStartResult("batch-123", BatchStatus(BatchStatus.State.Pending)),
        private val status: BatchStatus = BatchStatus(BatchStatus.State.Pending),
        private val results: List<BatchItemResult> = emptyList(),
        /** Null keeps the spec's default — a model that offers no cancellation. */
        private val cancel: BatchCancelResult? = null,
        /** Null keeps the spec's default — a model that offers no listing. */
        private val list: BatchListResult? = null,
    ) : BatchLanguageModel {
        override val provider: String = "mock-provider"
        override val modelId: String = "mock-model-id"
        val startCalls = mutableListOf<BatchStartOptions>()
        val statusCalls = mutableListOf<BatchOperationOptions>()
        val resultCalls = mutableListOf<BatchOperationOptions>()
        val cancelCalls = mutableListOf<BatchOperationOptions>()
        val listCalls = mutableListOf<BatchListOptions>()

        override suspend fun doStartBatch(options: BatchStartOptions): BatchStartResult {
            startCalls += options
            return start
        }

        override suspend fun doGetBatchStatus(options: BatchOperationOptions): BatchStatus {
            statusCalls += options
            return status
        }

        override fun doGetBatchResults(options: BatchOperationOptions): Flow<BatchItemResult> {
            resultCalls += options
            return results.asFlow()
        }

        override suspend fun doCancelBatch(options: BatchOperationOptions): BatchCancelResult? {
            cancelCalls += options
            return cancel
        }

        override suspend fun doListBatches(options: BatchListOptions): BatchListResult? {
            listCalls += options
            return list
        }
    }

    private val reference = BatchReference(id = "batch-123", provider = "mock-provider")

    private val testUsage = Usage(
        inputTokens = Usage.InputTokens(total = 3, noCache = 2, cacheRead = 1),
        outputTokens = Usage.OutputTokens(total = 5, text = 4, reasoning = 1),
    )

    private val citySchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
        put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("city"))))
        put("additionalProperties", false)
    }

    private fun user(text: String) = listOf(ModelMessage.User(listOf(UserPart.Text(text))))

    // ---- startBatch --------------------------------------------------------------------------------

    @Test
    fun `normalizes requests and returns the acknowledged batch`() = runTest {
        val model = ScriptedBatchModel(
            start = BatchStartResult(
                batchId = "batch-456",
                status = BatchStatus(
                    state = BatchStatus.State.Pending,
                    rawStatus = "validating",
                    requestCounts = BatchRequestCounts(total = 1, pending = 1, completed = 0, failed = 0),
                    createdAt = "2026-08-03T12:00:00.000Z",
                ),
            ),
        )
        val prompt = user("What is the capital of France?")

        val result = startBatch(
            model = model,
            requests = listOf(
                TextBatchRequest(
                    id = "request-1",
                    prompt = prompt,
                    options = CallOptions(
                        prompt = prompt,
                        maxOutputTokens = 100,
                        temperature = 0.0,
                        topP = 0.9,
                        topK = 10,
                        presencePenalty = 0.1,
                        frequencyPenalty = 0.2,
                        stopSequences = listOf("STOP"),
                        seed = 42,
                        reasoning = ReasoningEffort.Low,
                        providerOptions = mapOf("mock" to buildJsonObject { put("perRequest", true) }),
                    ),
                ),
            ),
            providerOptions = mapOf("mock" to buildJsonObject { put("batch", true) }),
            headers = mapOf("x-test" to "test-value"),
            logWarnings = WarningLogger.None,
        )

        assertEquals("batch-456", result.id)
        assertEquals("mock-provider", result.provider)
        assertEquals("validating", result.status.rawStatus)
        assertEquals(BatchRequestCounts(1, 1, 0, 0), result.status.requestCounts)
        assertEquals("2026-08-03T12:00:00.000Z", result.status.createdAt)
        assertEquals(emptyList(), result.warnings)
        assertEquals(reference.copy(id = "batch-456"), result.reference)

        val sent = model.startCalls.single()
        val request = assertIs<BatchRequest.Text>(sent.requests.single())
        assertEquals("request-1", request.id)
        assertEquals(null, request.modelId)
        assertEquals(prompt, request.options.prompt)
        assertEquals(100, request.options.maxOutputTokens)
        assertEquals(0.0, request.options.temperature)
        assertEquals(0.9, request.options.topP)
        assertEquals(10, request.options.topK)
        assertEquals(0.1, request.options.presencePenalty)
        assertEquals(0.2, request.options.frequencyPenalty)
        assertEquals(listOf("STOP"), request.options.stopSequences)
        assertEquals(42, request.options.seed)
        assertEquals(ReasoningEffort.Low, request.options.reasoning)
        assertEquals(mapOf("mock" to buildJsonObject { put("perRequest", true) }), request.options.providerOptions)
        assertEquals(mapOf("mock" to buildJsonObject { put("batch", true) }), sent.providerOptions)
        // The reference appends its user agent here; ours is the transport's job (ProviderHttp), so the
        // headers reach the model exactly as the caller wrote them.
        assertEquals(mapOf("x-test" to "test-value"), sent.headers)
    }

    @Test
    fun `each request goes through the same prompt standardization a live call does`() = runTest {
        val model = ScriptedBatchModel()

        startBatch(
            model = model,
            requests = listOf(TextBatchRequest("r1", user("hi"), instructions = "be terse")),
            logWarnings = WarningLogger.None,
        )

        val sent = model.startCalls.single().requests.single().options.prompt
        assertEquals(ModelMessage.System("be terse"), sent.first())
        assertEquals(2, sent.size)
    }

    @Test
    fun `a prompt that would be a 400 live is refused at submission`() = runTest {
        val model = ScriptedBatchModel()
        val unanswered = listOf(
            ModelMessage.User(listOf(UserPart.Text("go"))),
            ModelMessage.Assistant(listOf(AssistantPart.ToolCall("c1", "search", "{}"))),
        )

        // A day later, a failed item names neither the message nor the call; here the error does.
        assertFailsWith<MissingToolResultsError> {
            startBatch(model, listOf(TextBatchRequest("r1", unanswered)), logWarnings = WarningLogger.None)
        }
        assertFailsWith<InvalidArgumentError> {
            startBatch(
                model,
                listOf(TextBatchRequest("r1", user("hi"), options = CallOptions(user("hi"), maxOutputTokens = 0))),
                logWarnings = WarningLogger.None,
            )
        }
        assertEquals(0, model.startCalls.size)
    }

    @Test
    fun `rejects empty, blank and duplicate request IDs`() = runTest {
        val model = ScriptedBatchModel()

        val empty = assertFailsWith<InvalidArgumentError> {
            startBatch(model, emptyList(), logWarnings = WarningLogger.None)
        }
        assertEquals("requests", empty.argument)

        val blank = assertFailsWith<InvalidArgumentError> {
            startBatch(model, listOf(TextBatchRequest("  ", user("one"))), logWarnings = WarningLogger.None)
        }
        assertEquals("request IDs must not be empty", blank.message)

        val duplicate = assertFailsWith<InvalidArgumentError> {
            startBatch(
                model,
                listOf(TextBatchRequest("duplicate", user("one")), TextBatchRequest("duplicate", user("two"))),
                logWarnings = WarningLogger.None,
            )
        }
        assertTrue(duplicate.message!!.contains("request IDs must be unique"), duplicate.message)
    }

    @Test
    fun `forwards the webhook URL to the batch model`() = runTest {
        val model = ScriptedBatchModel()

        val result = startBatch(
            model = model,
            requests = listOf(TextBatchRequest("request-1", user("hello"))),
            webhookUrl = "https://example.com/batch-webhook",
            logWarnings = WarningLogger.None,
        )

        assertEquals("https://example.com/batch-webhook", model.startCalls.single().webhookUrl)
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun `the provider's warnings reach the logger, attributed to the model`() = runTest {
        val warning = BatchStartResult.RequestWarning(Warning.Unsupported("webhookUrl", "no webhooks"), requestId = null)
        val model = ScriptedBatchModel(
            start = BatchStartResult("batch-123", BatchStatus(BatchStatus.State.Pending), warnings = listOf(warning)),
        )
        val logged = mutableListOf<Triple<List<Warning>, String?, String?>>()

        val result = startBatch(
            model = model,
            requests = listOf(TextBatchRequest("r1", user("hello"))),
            logWarnings = { warnings, provider, modelId -> logged += Triple(warnings, provider, modelId) },
        )

        assertEquals(listOf(warning), result.warnings)
        val expected: Triple<List<Warning>, String?, String?> =
            Triple(listOf(warning.warning), "mock-provider", "mock-model-id")
        assertEquals(listOf(expected), logged)
    }

    @Test
    fun `a request's own model is forwarded and its warnings are attributed to it`() = runTest {
        val warning = BatchStartResult.RequestWarning(Warning.Other("request warning"), requestId = "request-2")
        val model = ScriptedBatchModel(
            start = BatchStartResult("batch-123", BatchStatus(BatchStatus.State.Pending), warnings = listOf(warning)),
        )
        val logged = mutableListOf<Triple<List<Warning>, String?, String?>>()

        startBatch(
            model = model,
            requests = listOf(
                TextBatchRequest("request-1", user("hello"), model = "default-model"),
                TextBatchRequest("request-2", user("hello"), model = "override-model"),
            ),
            logWarnings = { warnings, provider, modelId -> logged += Triple(warnings, provider, modelId) },
        )

        assertEquals(listOf("default-model", "override-model"), model.startCalls.single().requests.map { it.modelId })
        val expected: Triple<List<Warning>, String?, String?> =
            Triple(listOf(Warning.Other("request warning")), "mock-provider", "override-model")
        assertEquals(listOf(expected), logged)
    }

    @Test
    fun `forwards definition-only tools without executing them`() = runTest {
        val model = ScriptedBatchModel()
        val prompt = user("What is the weather in Paris?")
        val weather = Tool.Function(name = "weather", inputSchema = citySchema, description = "Get the weather for a city.")

        startBatch(
            model = model,
            requests = listOf(
                TextBatchRequest(
                    id = "request-1",
                    prompt = prompt,
                    options = CallOptions(prompt = prompt, tools = listOf(weather), toolChoice = ToolChoice.Required),
                ),
            ),
            logWarnings = WarningLogger.None,
        )

        val sent = model.startCalls.single().requests.single().options
        assertEquals(listOf(weather), sent.tools)
        assertEquals(ToolChoice.Required, sent.toolChoice)
    }

    @Test
    fun `rejects incompatible definitions for the same tool name`() = runTest {
        val model = ScriptedBatchModel()
        val asString = Tool.Function("lookup", buildJsonObject { put("type", "string") })
        val asNumber = Tool.Function("lookup", buildJsonObject { put("type", "number") })

        val error = assertFailsWith<InvalidArgumentError> {
            startBatch(
                model = model,
                requests = listOf(
                    TextBatchRequest("request-1", user("hello"), CallOptions(user("hello"), tools = listOf(asString))),
                    TextBatchRequest("request-2", user("hello"), CallOptions(user("hello"), tools = listOf(asNumber))),
                ),
                logWarnings = WarningLogger.None,
            )
        }

        assertTrue(
            error.message!!.contains("tool \"lookup\" must have the same definition in every batch request"),
            error.message,
        )
        assertEquals(0, model.startCalls.size)
    }

    @Test
    fun `normalizes image requests before starting a batch`() = runTest {
        val model = ScriptedBatchModel()
        val options = ImageCallOptions(prompt = "A red panda", n = 2, aspectRatio = "16:9")

        startBatch(
            model = model,
            requests = listOf(ImageBatchRequest("request-1", options, model = "image-model")),
            logWarnings = WarningLogger.None,
        )

        val sent = assertIs<BatchRequest.Image>(model.startCalls.single().requests.single())
        assertEquals("request-1", sent.id)
        assertEquals("image-model", sent.modelId)
        assertEquals(BatchRequestType.Image, sent.type)
        assertEquals(options, sent.imageOptions)
    }

    @Test
    fun `an image request for no images is refused at submission`() = runTest {
        val model = ScriptedBatchModel()

        val error = assertFailsWith<InvalidArgumentError> {
            startBatch(model, listOf(ImageBatchRequest("i1", ImageCallOptions(prompt = "x", n = 0))), logWarnings = WarningLogger.None)
        }

        assertEquals("n", error.argument)
        assertEquals(0, model.startCalls.size)
    }

    @Test
    fun `a text-only model rejects an image request before sending anything`() = runTest {
        // What every text-only provider does with the request list: map each call, THEN send.
        val sent = mutableListOf<CallOptions>()
        val model = object : BatchLanguageModel {
            override val provider = "mock-provider"
            override val modelId = "mock-model-id"
            override suspend fun doStartBatch(options: BatchStartOptions): BatchStartResult {
                val calls = options.requests.map { it.options }
                sent += calls
                return BatchStartResult("batch-123", BatchStatus(BatchStatus.State.Pending))
            }
            override suspend fun doGetBatchStatus(options: BatchOperationOptions) = BatchStatus(BatchStatus.State.Pending)
            override fun doGetBatchResults(options: BatchOperationOptions): Flow<BatchItemResult> = emptyList<BatchItemResult>().asFlow()
        }

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            startBatch(
                model,
                listOf(
                    TextBatchRequest("t1", user("hello")),
                    ImageBatchRequest("i1", ImageCallOptions(prompt = "a red panda")),
                ),
                logWarnings = WarningLogger.None,
            )
        }

        assertEquals("batch request type: image", error.functionality)
        assertEquals(emptyList(), sent)
    }

    // ---- getBatchStatus ----------------------------------------------------------------------------

    @Test
    fun `returns the latest status and names the batch to the model`() = runTest {
        val model = ScriptedBatchModel(
            status = BatchStatus(
                state = BatchStatus.State.Completed,
                rawStatus = "ended",
                requestCounts = BatchRequestCounts(total = 2, pending = 0, completed = 1, failed = 1),
            ),
        )

        val status = getBatchStatus(model, reference)

        assertEquals(BatchStatus.State.Completed, status.state)
        assertEquals("ended", status.rawStatus)
        assertEquals(BatchRequestCounts(2, 0, 1, 1), status.requestCounts)
        assertEquals(listOf(BatchOperationOptions("batch-123")), model.statusCalls)
    }

    @Test
    fun `a status read against a provider that did not produce the batch is refused`() = runTest {
        val model = ScriptedBatchModel()

        val error = assertFailsWith<InvalidArgumentError> {
            getBatchStatus(model, reference.copy(provider = "different-provider"))
        }
        assertEquals("provider", error.argument)
        assertEquals(0, model.statusCalls.size)
    }

    // ---- cancelBatch / listBatches -----------------------------------------------------------------

    @Test
    fun `requests cancellation and returns provider metadata`() = runTest {
        val metadata = mapOf("mock" to buildJsonObject { put("cancellation", "requested") })
        val model = ScriptedBatchModel(cancel = BatchCancelResult(metadata))

        val result = cancelBatch(model, reference)

        assertEquals(metadata, result.providerMetadata)
        assertEquals(listOf(BatchOperationOptions("batch-123")), model.cancelCalls)
    }

    @Test
    fun `throws when cancellation is unsupported`() = runTest {
        val error = assertFailsWith<UnsupportedFunctionalityError> { cancelBatch(ScriptedBatchModel(), reference) }

        assertEquals("batch cancellation", error.functionality)
        assertEquals("The provider does not support batch cancellation.", error.message)
    }

    @Test
    fun `returns normalized batch references and the next cursor`() = runTest {
        val model = ScriptedBatchModel(
            list = BatchListResult(
                batches = listOf(BatchListItem("batch-456", BatchStatus(BatchStatus.State.Completed, rawStatus = "done"))),
                nextCursor = "cursor-2",
                providerMetadata = mapOf("mock" to buildJsonObject { put("page", 1) }),
            ),
        )

        val page = listBatches(model, limit = 20, cursor = "cursor-1")

        assertEquals(listOf(BatchListOptions(limit = 20, cursor = "cursor-1")), model.listCalls)
        assertEquals(
            listOf(Batch("batch-456", "mock-provider", BatchStatus(BatchStatus.State.Completed, rawStatus = "done"))),
            page.batches,
        )
        assertEquals(BatchReference("batch-456", "mock-provider"), page.batches.single().reference)
        assertEquals("cursor-2", page.nextCursor)
        assertEquals(mapOf("mock" to buildJsonObject { put("page", 1) }), page.providerMetadata)
    }

    @Test
    fun `throws when listing is unsupported`() = runTest {
        val error = assertFailsWith<UnsupportedFunctionalityError> { listBatches(ScriptedBatchModel()) }

        assertEquals("batch listing", error.functionality)
        assertEquals("The provider does not support listing batches.", error.message)
    }

    // ---- getBatchResults ---------------------------------------------------------------------------

    @Test
    fun `a succeeded item is a step, a failed one is passed through`() = runTest {
        val generated = GenerateResult(
            content = listOf(Content.Text("Paris")),
            finishReason = FinishReason(FinishReason.Unified.Stop, raw = "stop"),
            usage = testUsage,
            response = ResponseInfo(ResponseMetadata(id = "response-1", timestamp = 1_785_758_400_000, modelId = "provider-model-id")),
            providerMetadata = mapOf("mock" to buildJsonObject { put("result", true) }),
        )
        val model = ScriptedBatchModel(
            results = listOf(
                BatchItemResult.Succeeded("request-1", generated),
                BatchItemResult.Failed("request-2", BatchError("request failed", code = "bad_request")),
            ),
        )

        val items = getBatchResults(model, reference).toList()

        assertEquals(listOf(BatchOperationOptions("batch-123")), model.resultCalls)
        val succeeded = assertIs<TextBatchItem.Succeeded>(items[0])
        assertEquals("request-1", succeeded.id)
        assertEquals("Paris", succeeded.step.text)
        assertEquals(FinishReason(FinishReason.Unified.Stop, "stop"), succeeded.step.finishReason)
        assertEquals("stop", succeeded.step.rawFinishReason)
        assertEquals(testUsage, succeeded.step.usage)
        assertEquals("response-1", succeeded.step.response?.metadata?.id)
        assertEquals("provider-model-id", succeeded.step.response?.metadata?.modelId)
        assertEquals(mapOf("mock" to buildJsonObject { put("result", true) }), succeeded.step.providerMetadata)
        // A batch item is a run of one round, and its request id is the correlation key a callId is for.
        assertEquals("request-1", succeeded.step.callId)
        assertEquals(0, succeeded.step.stepNumber)

        val failed = assertIs<TextBatchItem.Failed>(items[1])
        assertEquals("request-2", failed.id)
        assertEquals(BatchError("request failed", code = "bad_request"), failed.error)
    }

    @Test
    fun `provider-executed tool content is normalized and usage preserved`() = runTest {
        val model = ScriptedBatchModel(
            results = listOf(
                BatchItemResult.Succeeded(
                    "request-1",
                    GenerateResult(
                        content = listOf(
                            Content.ToolCall(
                                toolCallId = "call-1",
                                toolName = "weather",
                                input = """{"city":"Paris"}""",
                                providerExecuted = true,
                                dynamic = true,
                            ),
                            Content.ToolResult(
                                toolCallId = "call-1",
                                toolName = "weather",
                                output = ToolOutput.Json(buildJsonObject { put("temperature", 20) }),
                                providerExecuted = true,
                                dynamic = true,
                            ),
                        ),
                        finishReason = FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_use"),
                        usage = testUsage,
                        providerMetadata = mapOf("mock" to buildJsonObject { put("result", true) }),
                    ),
                ),
            ),
        )

        val item = assertIs<TextBatchItem.Succeeded>(getBatchResults(model, reference).single())

        assertEquals("", item.step.text)
        val call = item.step.toolCalls.single()
        assertEquals("""{"city":"Paris"}""", call.input)
        assertTrue(!call.invalid)
        assertTrue(call.providerExecuted && call.dynamic)
        assertEquals(1, item.step.dynamicToolCalls.size)
        assertEquals("call-1", item.step.providerToolResults.single().toolCallId)
        assertEquals("tool_use", item.step.rawFinishReason)
        assertEquals(testUsage, item.step.usage)
    }

    @Test
    fun `normalizes client tool calls with their definitions without executing them`() = runTest {
        val model = ScriptedBatchModel(
            results = listOf(
                BatchItemResult.Succeeded(
                    "request-1",
                    GenerateResult(
                        content = listOf(Content.ToolCall("call-1", "weather", """{"city":"Paris"}""")),
                        finishReason = FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_use"),
                        usage = testUsage,
                    ),
                ),
                BatchItemResult.Succeeded(
                    "request-2",
                    GenerateResult(
                        content = listOf(Content.ToolCall("call-2", "unknown", """{"city":"Paris"}""")),
                        finishReason = FinishReason(FinishReason.Unified.ToolCalls, raw = "tool_use"),
                        usage = testUsage,
                    ),
                ),
            ),
        )
        val tools = listOf(Tool.Function("weather", citySchema))

        val items = getBatchResults(model, reference, tools = tools).toList()

        val known = assertIs<TextBatchItem.Succeeded>(items[0]).step.toolCalls.single()
        assertEquals("""{"city":"Paris"}""", known.input)
        assertTrue(!known.invalid)
        // With the definitions in hand, a call to a tool the batch never offered is flagged, as the loop
        // flags it — and still kept, so a caller replaying the step sees what the model did.
        val unknown = assertIs<TextBatchItem.Succeeded>(items[1]).step.toolCalls.single()
        assertTrue(unknown.invalid)
    }

    @Test
    fun `a tool call with unparseable input is flagged, never thrown on`() = runTest {
        val model = ScriptedBatchModel(
            results = listOf(
                BatchItemResult.Succeeded(
                    "request-1",
                    GenerateResult(
                        content = listOf(Content.ToolCall("call-1", "weather", "{not json")),
                        finishReason = FinishReason(FinishReason.Unified.ToolCalls),
                        usage = Usage(),
                    ),
                ),
            ),
        )

        val item = assertIs<TextBatchItem.Succeeded>(getBatchResults(model, reference).single())

        // The loop's own rule: the model produced the call, so it stays, marked — a caller replaying
        // the step needs it present and needs to know not to run it.
        assertTrue(item.step.toolCalls.single().invalid)
    }

    @Test
    fun `normalizes successful image results`() = runTest {
        val response = ModalityResponse(
            timestamp = 1_788_955_200_000,
            modelId = "image-model",
            headers = mapOf("x-request-id" to "request-1"),
        )
        val metadata = mapOf(
            "mock" to buildJsonObject {
                put("images", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("revisedPrompt", "A vivid red panda") })))
            },
        )
        val model = ScriptedBatchModel(
            results = listOf(
                BatchItemResult.ImageSucceeded(
                    "image-1",
                    ImageResult(
                        images = listOf(BinaryData.Base64("aGVsbG8=")),
                        usage = ImageUsage(inputTokens = 2, outputTokens = 3, totalTokens = 5),
                        providerMetadata = metadata,
                        response = response,
                    ),
                ),
                BatchItemResult.Failed("image-2", BatchError("no image"), type = BatchRequestType.Image),
            ),
        )

        val items = getBatchResults(model, reference).toList()

        val succeeded = assertIs<ImageBatchItem.Succeeded>(items[0])
        assertEquals("image-1", succeeded.id)
        assertEquals(listOf(BinaryData.Base64("aGVsbG8=")), succeeded.images.images)
        assertEquals(emptyList(), succeeded.images.warnings)
        assertEquals(ImageUsage(inputTokens = 2, outputTokens = 3, totalTokens = 5), succeeded.images.usage)
        assertEquals(metadata, succeeded.images.providerMetadata)
        assertEquals(listOf(response), succeeded.images.responses)
        // A failure keeps its modality, so an image request's failure is read as one.
        val failed = assertIs<ImageBatchItem.Failed>(items[1])
        assertEquals(BatchError("no image"), failed.error)
    }

    @Test
    fun `results are read against the provider that produced them`() = runTest {
        val model = ScriptedBatchModel()

        assertFailsWith<InvalidArgumentError> {
            getBatchResults(model, reference.copy(provider = "other")).toList()
        }
        assertEquals(0, model.resultCalls.size)
    }

    @Test
    fun `the reference survives a round trip through JSON`() {
        val encoded = Json.encodeToString(BatchReference.serializer(), reference)
        assertEquals(reference, Json.decodeFromString(BatchReference.serializer(), encoded))
    }
}
