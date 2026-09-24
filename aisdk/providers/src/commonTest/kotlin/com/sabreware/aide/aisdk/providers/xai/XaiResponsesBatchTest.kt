package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.BatchCancelResult
import com.sabreware.aide.aisdk.BatchItemResult
import com.sabreware.aide.aisdk.BatchListOptions
import com.sabreware.aide.aisdk.BatchListResult
import com.sabreware.aide.aisdk.BatchRequestType
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.BatchOperationOptions
import com.sabreware.aide.aisdk.BatchRequest
import com.sabreware.aide.aisdk.BatchRequestCounts
import com.sabreware.aide.aisdk.BatchStartOptions
import com.sabreware.aide.aisdk.BatchStatus
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.RecordedCall
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * xAI's Batch API over the Responses model, against `xai-responses-batch.test.ts`.
 *
 * Two assertions carry the file. The JSONL each request becomes is byte-comparable to the live
 * Responses body, because both come from one builder; and a stored result — a Chat Completions
 * document, whatever the request's endpoint — decodes to the text, reasoning, sources and usage a live
 * call would have produced.
 */
class XaiResponsesBatchTest {

    private fun provider(server: TestServer, headers: Map<String, String> = emptyMap()) = XaiProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        headers = headers,
    )

    private fun model(server: TestServer, headers: Map<String, String> = emptyMap()) =
        provider(server, headers).batchLanguageModel("grok-4.3")

    private fun request(id: String, text: String, topK: Int? = null) = BatchRequest(
        id = id,
        options = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text(text)))), topK = topK),
    )

    private val batchOptions = BatchOperationOptions("batch_123")

    // --- Start ---------------------------------------------------------------------------------------

    @Test
    fun `a start uploads the prepared JSONL and creates a file-backed batch`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.BATCH_FILE_UPLOADED),
            TestServer.json(XaiBatchFixtures.batchResponse(numPending = 2, numSuccess = 0)),
        )

        val result = model(server, headers = mapOf("Provider-Header" to "provider")).doStartBatch(
            BatchStartOptions(
                requests = listOf(
                    request("france", "What is the capital of France?"),
                    request("germany", "What is the capital of Germany?", topK = 10),
                ),
                headers = mapOf("Operation-Header" to "operation"),
                webhookUrl = "https://example.com/batch-webhook",
            ),
        )

        assertEquals("batch_123", result.batchId)
        assertEquals(BatchStatus.State.Pending, result.status.state)
        assertEquals(BatchRequestCounts(total = 2, pending = 2, completed = 0, failed = 0), result.status.requestCounts)
        assertEquals("2026-08-25T12:00:00Z", result.status.createdAt)
        assertEquals("2099-08-26T12:00:00Z", result.status.expiresAt)
        assertEquals(2, result.warnings.size)
        // Batch-wide first: xAI has no per-batch webhook, and the warning carries no request id.
        val webhook = assertIs<Warning.Unsupported>(result.warnings[0].warning)
        assertEquals("webhookUrl", webhook.feature)
        assertEquals("The xAI Batch API does not support per-batch webhook URLs.", webhook.details)
        assertNull(result.warnings[0].requestId)
        // Then the per-request one, attributed to the request that raised it.
        assertEquals("germany", result.warnings[1].requestId)
        assertEquals("topK", assertIs<Warning.Unsupported>(result.warnings[1].warning).feature)

        val upload = server.request(0)
        assertEquals("POST", upload.method)
        assertEquals("v1/files", upload.path)
        upload.assertMultipartFile("file", fileName = "batch.jsonl", contentType = "application/jsonl")
        val lines = upload.filePartBody().trim().lines().map(::parseJsonObject)
        assertEquals(
            listOf(
                batchLine("france", "What is the capital of France?"),
                batchLine("germany", "What is the capital of Germany?"),
            ),
            lines,
        )

        val create = server.request(1)
        assertEquals("v1/batches", create.path)
        create.assertBodyEquals("""{"name":"ai-sdk-text-batch","input_file_id":"file_123"}""")

        for (call in server.calls) {
            call.assertHeader("Authorization", "Bearer test-api-key")
            call.assertHeader("Provider-Header", "provider")
            call.assertHeader("Operation-Header", "operation")
        }
    }

    /** One JSONL line, exactly as the reference test expects it: the live body under `body`. */
    private fun batchLine(id: String, text: String) = buildJsonObject {
        put("custom_id", id)
        put("method", "POST")
        put("url", "/v1/responses")
        put(
            "body",
            buildJsonObject {
                put("model", "grok-4.3")
                put(
                    "input",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "input_text")
                                                put("text", text)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    // --- Status --------------------------------------------------------------------------------------

    @Test
    fun `xAI's counters and cancellation metadata map onto the neutral status`() = runTest {
        val server = TestServer(
            TestServer.json(
                XaiBatchFixtures.batchResponse(
                    numRequests = 4,
                    numPending = 0,
                    numSuccess = 2,
                    numError = 1,
                    numCancelled = 1,
                    cancelTime = "2026-08-25T12:30:00Z",
                    cancelByXaiMessage = "Cancelled by user.",
                ),
            ),
        )

        val status = model(server).doGetBatchStatus(batchOptions)

        assertEquals("v1/batches/batch_123", server.request().path)
        assertEquals(BatchStatus.State.Failed, status.state)
        // Errors and cancellations both count as failed in the neutral counts...
        assertEquals(BatchRequestCounts(total = 4, pending = 0, completed = 2, failed = 2), status.requestCounts)
        assertEquals("Cancelled by user.", status.error?.message)
        assertEquals("batch_cancelled", status.error?.code)
        assertEquals("2026-08-25T12:00:00Z", status.createdAt)
        assertEquals("2099-08-26T12:00:00Z", status.expiresAt)
        // ...and the split survives in the vendor's own namespace.
        val state = assertIs<JsonObject>(status.providerMetadata?.get(XAI_PROVIDER_ID)?.get("state"))
        assertEquals("1", state["numCancelled"].string())
        assertEquals(
            "2026-08-25T12:30:00Z",
            status.providerMetadata?.get(XAI_PROVIDER_ID)?.get("cancelTime").string(),
        )
    }

    @Test
    fun `inconsistent counters are dropped rather than reported`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.batchResponse(numPending = 1, numSuccess = 2)))

        val status = model(server).doGetBatchStatus(batchOptions)

        assertEquals(BatchStatus.State.Pending, status.state)
        assertNull(status.requestCounts)
    }

    @Test
    fun `an expire_time in the past is a failed batch, whatever the counters say`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.batchResponse(expireTime = "2026-08-26T12:00:00Z")))
        val model = XaiResponsesBatchModel(
            modelId = "grok-4.3",
            http = server.http(),
            baseUrl = "https://api.x.ai/v1",
            headers = emptyMap(),
            // One second after expiry.
            now = { 1_787_745_601_000 },
        )

        val status = model.doGetBatchStatus(batchOptions)

        assertEquals(BatchStatus.State.Failed, status.state)
        assertEquals("batch_expired", status.error?.code)
        assertEquals("xAI batch \"batch_123\" expired.", status.error?.message)
    }

    // --- Results -------------------------------------------------------------------------------------

    @Test
    fun `a pending batch has no results to fetch`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.batchResponse(numPending = 1, numSuccess = 1)))

        val error = assertFailsWith<InvalidArgumentError> {
            model(server).doGetBatchResults(batchOptions).toList()
        }

        assertEquals("batchId", error.argument)
        assertEquals("xAI batch \"batch_123\" is not complete.", error.message)
    }

    @Test
    fun `results paginate, and each item decodes on its own`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.batchResponse()),
            TestServer.json(XaiBatchFixtures.RESULTS_PAGE_1),
            TestServer.json(XaiBatchFixtures.RESULTS_PAGE_2),
        )

        val items = model(server).doGetBatchResults(batchOptions).toList()

        assertEquals(
            listOf(
                "https://api.x.ai/v1/batches/batch_123",
                "https://api.x.ai/v1/batches/batch_123/results?limit=1000",
                "https://api.x.ai/v1/batches/batch_123/results?limit=1000&pagination_token=next%2Fpage",
            ),
            server.calls.map { it.url },
        )
        assertEquals("next/page", server.request(2).query["pagination_token"])

        val france = assertIs<BatchItemResult.Succeeded>(items[0])
        assertEquals("france", france.id)
        assertEquals(
            listOf(
                Content.Text("Paris"),
                Content.Reasoning("Reasoning"),
                Content.Source.Url(id = "https://example.com/source", url = "https://example.com/source"),
            ),
            france.result.content,
        )
        assertEquals(FinishReason(FinishReason.Unified.Stop, "stop"), france.result.finishReason)
        // xAI's completion_tokens excludes reasoning, so the output total is the sum.
        assertEquals(Usage.InputTokens(total = 10, noCache = 8, cacheRead = 2), france.result.usage.inputTokens)
        assertEquals(Usage.OutputTokens(total = 4, text = 3, reasoning = 1), france.result.usage.outputTokens)
        assertEquals(
            buildJsonObject {
                put("costInUsdTicks", 123)
                put("serviceTier", "default")
            },
            france.result.providerMetadata?.get(XAI_PROVIDER_ID),
        )
        assertEquals("response_123", france.result.response?.metadata?.id)
        assertEquals("grok-4.3", france.result.response?.metadata?.modelId)
        assertEquals(1_700_000_000_000, france.result.response?.metadata?.timestamp)

        val failed = assertIs<BatchItemResult.Failed>(items[1])
        assertEquals("Invalid request.", failed.error.message)
        assertEquals("3", failed.error.code)

        // Code 1 is xAI's cancellation status: a distinct outcome, not a failure with a number.
        val cancelled = assertIs<BatchItemResult.Cancelled>(items[2])
        assertEquals("Cancelled.", cancelled.error?.message)
        assertEquals("1", cancelled.error?.code)
    }

    @Test
    fun `an invalid or unsupported item fails alone, without stopping the rest`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.batchResponse()),
            TestServer.json(XaiBatchFixtures.RESULTS_MIXED),
        )

        val items = model(server).doGetBatchResults(batchOptions).toList()

        val invalid = assertIs<BatchItemResult.Failed>(items[0])
        assertEquals("invalid", invalid.id)
        assertEquals("invalid_response", invalid.error.code)

        // A tool call is the model's answer, kept: the caller runs it, as the reference now has it.
        val toolCall = assertIs<BatchItemResult.Succeeded>(items[1])
        assertEquals("tool-call", toolCall.id)
        assertEquals(
            listOf(
                Content.ToolCall(toolCallId = "call_1", toolName = "weather", input = "{}"),
                Content.Source.Url(id = "https://example.com/source", url = "https://example.com/source"),
            ),
            toolCall.result.content,
        )
        assertEquals(FinishReason(FinishReason.Unified.ToolCalls, "tool_calls"), toolCall.result.finishReason)

        val valid = assertIs<BatchItemResult.Succeeded>(items[2])
        assertEquals("Berlin", assertIs<Content.Text>(valid.result.content[0]).text)
        assertEquals(
            "default",
            valid.result.providerMetadata?.get(XAI_PROVIDER_ID)?.get("serviceTier").string(),
        )
    }

    @Test
    fun `the batch model reports the provider id and the model`() = runTest {
        val model = model(TestServer(TestServer.json("{}")))

        assertEquals(XAI_PROVIDER_ID, model.provider)
        assertEquals("grok-4.3", model.modelId)
    }

    // --- The 2026-09 delta: image requests, per-request models, expiry, cancel, list, transcripts ---

    @Test
    fun `an image request is one line against the image endpoint, naming its own model`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.BATCH_FILE_UPLOADED),
            TestServer.json(XaiBatchFixtures.batchResponse()),
        )

        model(server).doStartBatch(
            BatchStartOptions(
                requests = listOf(
                    BatchRequest.Image(
                        id = "image-1",
                        imageOptions = ImageCallOptions(
                            prompt = "A red panda",
                            n = 2,
                            aspectRatio = "16:9",
                            providerOptions = mapOf(XAI_PROVIDER_ID to buildJsonObject { put("quality", "high") }),
                        ),
                        modelId = "grok-imagine-image",
                    ),
                ),
            ),
        )

        assertEquals(
            parseJsonObject(
                """{"custom_id":"image-1","method":"POST","url":"/v1/images/generations","body":{"model":"grok-imagine-image",""" +
                    """"prompt":"A red panda","n":2,"response_format":"b64_json","aspect_ratio":"16:9","quality":"high"}}""",
            ),
            parseJsonObject(server.request(0).filePartBody().trim()),
        )
    }

    @Test
    fun `inputFileExpiresAfter goes out as expires_after BEFORE the file part`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.BATCH_FILE_UPLOADED),
            TestServer.json(XaiBatchFixtures.batchResponse()),
        )

        val result = model(server).doStartBatch(
            BatchStartOptions(
                requests = listOf(request("france", "What is the capital of France?")),
                providerOptions = mapOf(XAI_PROVIDER_ID to buildJsonObject { put("inputFileExpiresAfter", 172_800) }),
            ),
        )

        val upload = server.request(0)
        upload.assertMultipartField("expires_after", "172800")
        // xAI reads the field only if it precedes the file; after it, the upload is stored without a TTL.
        assertTrue(upload.bodyText.indexOf("name=\"expires_after\"") < upload.bodyText.indexOf("name=\"file\""))
        server.request(1).assertBodyEquals("""{"name":"ai-sdk-text-batch","input_file_id":"file_123"}""")
        val metadata = result.status.providerMetadata?.get(XAI_PROVIDER_ID)!!
        assertEquals("file_123", metadata["inputFileId"].string())
        assertNull(metadata["inputFileExpiresAt"], "the upload response carried no expiry")
    }

    @Test
    fun `a cancel is a POST with an empty body`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.batchResponse(cancelTime = "2026-08-25T12:30:00Z")))

        val result = model(server, headers = mapOf("Provider-Header" to "provider")).doCancelBatch(
            BatchOperationOptions("batch_123", headers = mapOf("Operation-Header" to "operation")),
        )

        assertEquals(BatchCancelResult(), result)
        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("https://api.x.ai/v1/batches/batch_123:cancel", call.url)
        call.assertBodyEquals("{}")
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Provider-Header", "provider")
        call.assertHeader("Operation-Header", "operation")
    }

    @Test
    fun `a listing is one normalized page, cursored by the pagination token`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"batches":[""" +
                    XaiBatchFixtures.batchResponse(numRequests = 3, numPending = 2, numSuccess = 1) + "," +
                    XaiBatchFixtures.batchResponse(batchId = "batch_122") +
                    """],"pagination_token":"next/page"}""",
            ),
        )

        val result = model(server).doListBatches(BatchListOptions(limit = 2, cursor = "previous/page"))!!

        assertEquals(listOf("batch_123", "batch_122"), result.batches.map { it.batchId })
        assertEquals(BatchStatus.State.Pending, result.batches[0].status.state)
        assertEquals(BatchRequestCounts(total = 3, pending = 2, completed = 1, failed = 0), result.batches[0].status.requestCounts)
        assertEquals(BatchStatus.State.Completed, result.batches[1].status.state)
        assertEquals("2026-08-25T12:00:00Z", result.batches[1].status.createdAt)
        assertEquals("next/page", result.nextCursor)
        assertEquals(mapOf("limit" to "2", "pagination_token" to "previous/page"), server.request().query)

        val last = TestServer(TestServer.json("""{"batches":[],"pagination_token":null}"""))
        assertEquals(BatchListResult(batches = emptyList()), model(last).doListBatches(BatchListOptions()))
    }

    @Test
    fun `an image result is the image a live call would have returned`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.batchResponse()),
            TestServer.json(XaiBatchFixtures.RESULTS_IMAGE),
        )

        val item = assertIs<BatchItemResult.ImageSucceeded>(model(server).doGetBatchResults(batchOptions).single())

        assertEquals("image-1", item.id)
        assertEquals(BatchRequestType.Image, item.type)
        assertEquals(listOf<BinaryData>(BinaryData.Base64("aGVsbG8=")), item.result.images)
        assertEquals(
            parseJsonObject("""{"images":[{"revisedPrompt":"A vivid red panda"}],"costInUsdTicks":42}"""),
            item.result.providerMetadata?.get(XAI_PROVIDER_ID),
        )
    }

    @Test
    fun `a moderated image is a failed image item, not an exception`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.batchResponse()),
            TestServer.json(XaiBatchFixtures.RESULTS_IMAGE_MODERATED),
        )

        val item = assertIs<BatchItemResult.Failed>(model(server).doGetBatchResults(batchOptions).single())

        assertEquals(BatchRequestType.Image, item.type)
        assertEquals("Image generation was blocked due to a content policy violation.", item.error.message)
        assertNull(item.error.code)
    }

    @Test
    fun `a transcript's provider-run tools and final text decode as the live path would`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.batchResponse()),
            TestServer.json(XaiBatchFixtures.RESULTS_TRANSCRIPTS),
        )

        val items = model(server).doGetBatchResults(batchOptions).toList()

        val providerTool = assertIs<BatchItemResult.Succeeded>(items[0])
        assertEquals(
            listOf(
                Content.ToolCall(
                    toolCallId = "web-search-1",
                    toolName = "web_search",
                    input = """{"query":"Vercel"}""",
                    providerExecuted = true,
                    dynamic = true,
                ),
                Content.ToolResult(
                    toolCallId = "web-search-1",
                    toolName = "web_search",
                    output = ToolOutput.Text("Search results"),
                    dynamic = true,
                ),
                Content.Text("Final answer"),
                Content.Source.Url(id = "https://example.com/source", url = "https://example.com/source"),
            ),
            providerTool.result.content,
        )
        // The finish is the LAST assistant turn's, the one the transcript ended on.
        assertEquals(FinishReason(FinishReason.Unified.Stop, "stop"), providerTool.result.finishReason)

        // A call no tool row answers is the client's to run, whatever the tool is named.
        val clientTool = assertIs<BatchItemResult.Succeeded>(items[1])
        assertEquals(
            Content.ToolCall(toolCallId = "client-web-search-1", toolName = "web_search", input = "{}"),
            clientTool.result.content[0],
        )
        assertEquals(FinishReason(FinishReason.Unified.ToolCalls, "tool_calls"), clientTool.result.finishReason)
    }
}

/**
 * The body of the one file part in a multipart upload.
 *
 * The harness records a file part's name and type but not its bytes — every other upload in this
 * module is audio nobody asserts on — so the JSONL is read straight out of the raw body: from the blank
 * line that ends the part's headers to the boundary that closes it.
 */
private fun RecordedCall.filePartBody(): String {
    val start = bodyText.indexOf("filename=\"batch.jsonl\"")
    require(start >= 0) { "no batch.jsonl part in the upload" }
    val headersEnd = bodyText.indexOf("\r\n\r\n", start) + "\r\n\r\n".length
    val boundary = bodyText.indexOf("\r\n--", headersEnd)
    return bodyText.substring(headersEnd, if (boundary >= 0) boundary else bodyText.length)
}
