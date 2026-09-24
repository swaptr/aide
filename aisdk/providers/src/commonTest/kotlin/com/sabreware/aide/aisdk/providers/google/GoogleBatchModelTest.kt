package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BatchCancelResult
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.BatchError
import com.sabreware.aide.aisdk.BatchItemResult
import com.sabreware.aide.aisdk.BatchListItem
import com.sabreware.aide.aisdk.BatchListOptions
import com.sabreware.aide.aisdk.BatchListResult
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
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.google.GoogleBatchFixtures.BATCHES_URL
import com.sabreware.aide.aisdk.providers.google.GoogleBatchFixtures.BATCH_URL
import com.sabreware.aide.aisdk.providers.google.GoogleBatchFixtures.CANCEL_URL
import com.sabreware.aide.aisdk.providers.google.GoogleBatchFixtures.CREATE_URL
import com.sabreware.aide.aisdk.providers.google.GoogleBatchFixtures.OUTPUT_URL
import com.sabreware.aide.aisdk.providers.google.GoogleBatchFixtures.UPLOAD_SESSION_URL
import com.sabreware.aide.aisdk.providers.google.GoogleBatchFixtures.UPLOAD_START_URL
import com.sabreware.aide.aisdk.providers.google.GoogleBatchFixtures.googleResponse
import com.sabreware.aide.aisdk.providers.google.GoogleBatchFixtures.operation
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gemini's batch API, against the wire shapes of `google-batch.test.ts`.
 *
 * Two assertions carry the file. The creation body is the SAME body a live call sends, request for
 * request, because one builder produces both; and a stored result comes back with the finish reason,
 * usage and vendor metadata a live call would have produced, because one mapper decodes both.
 */
@OptIn(ExperimentalEncodingApi::class)
class GoogleBatchModelTest {

    private fun model(server: TestServer, extraHeaders: Map<String, String> = emptyMap()) = GoogleBatchModel(
        modelId = "gemini-2.5-flash",
        http = server.http(),
        headers = { mapOf("x-goog-api-key" to "test-api-key") + extraHeaders },
        generateId = { "test-id" },
    )

    private fun request(id: String, prompt: String, modelId: String? = null) =
        BatchRequest(id, CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text(prompt))))), modelId)

    private fun status(options: BatchOperationOptions = BatchOperationOptions("batches/batch-123")) = options

    /** `prepareOutput(lines)`: the finished operation names an output file, then the file. */
    private fun outputServer(vararg lines: String) = TestServer(
        TestServer.json(
            operation(metadata = mapOf("output" to parseJsonObject("""{"responsesFile":"files/batch-output"}"""))),
        ),
        TestServer.bytes(lines.joinToString("\n").encodeToByteArray(), "application/jsonl"),
    )

    // --- Start ---------------------------------------------------------------------------------------

    @Test
    fun `mixed models are refused before anything is sent - the model is part of the endpoint`() = runTest {
        // "rejects mixed models before creating a batch".
        val server = TestServer(TestServer.json(operation()))

        val error = assertFailsWith<InvalidArgumentError> {
            model(server).doStartBatch(
                BatchStartOptions(listOf(request("flash", "Hello"), request("pro", "Hello", modelId = "gemini-2.5-pro"))),
            )
        }

        assertEquals(
            "Google batches require every request to use the same model because the model is part of the batch endpoint.",
            error.message,
        )
        assertEquals("requests", error.argument)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a batch with no requests is refused`() = runTest {
        val error = assertFailsWith<InvalidArgumentError> {
            model(TestServer(TestServer.json(operation()))).doStartBatch(BatchStartOptions(emptyList()))
        }
        assertEquals("Google batches require at least one request.", error.message)
    }

    @Test
    fun `a request's own model names the endpoint`() = runTest {
        val server = TestServer(TestServer.json(operation()))

        model(server).doStartBatch(
            BatchStartOptions(listOf(request("a", "Hello", modelId = "gemini-2.5-pro"), request("b", "Hi", modelId = "gemini-2.5-pro"))),
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:batchGenerateContent",
            server.request().url,
        )
    }

    @Test
    fun `an image request is the image call's body, keyed like any other`() = runTest {
        // "starts an inline image generation batch".
        val server = TestServer(TestServer.json(operation(operation = mapOf("done" to JsonPrimitive(false)))))

        model(server).doStartBatch(
            BatchStartOptions(
                listOf(
                    BatchRequest.Image(
                        "image-1",
                        ImageCallOptions(prompt = "A red panda", aspectRatio = "16:9", seed = 42, providerOptions = emptyMap()),
                        modelId = "gemini-2.5-flash",
                    ),
                ),
            ),
        )

        assertEquals(
            parseJsonObject(
                """{"batch":{"displayName":"ai-sdk-batch-test-id","inputConfig":{"requests":{"requests":[""" +
                    """{"metadata":{"key":"image-1"},"request":{"contents":[{"role":"user","parts":[{"text":"A red panda"}]}],""" +
                    """"generationConfig":{"seed":42,"responseModalities":["IMAGE"],"imageConfig":{"aspectRatio":"16:9"}}}}]}}}}""",
            ),
            server.request().bodyJson(),
        )
    }

    // --- Cancel and list -----------------------------------------------------------------------------

    @Test
    fun `a cancel is an empty POST to the operation's cancel verb`() = runTest {
        // "cancels a batch".
        val server = TestServer(TestServer.json("{}"))

        val result = model(server, mapOf("Provider-Header" to "provider")).doCancelBatch(
            BatchOperationOptions("batches/batch-123", headers = mapOf("Operation-Header" to "operation")),
        )

        assertEquals(BatchCancelResult(), result)
        val call = server.request()
        assertEquals(CANCEL_URL, call.url)
        assertEquals("POST", call.method)
        assertEquals(JsonObject(emptyMap()), call.bodyJson())
        call.assertHeader("x-goog-api-key", "test-api-key")
        call.assertHeader("provider-header", "provider")
        call.assertHeader("operation-header", "operation")
    }

    @Test
    fun `a page of batches is listed and each operation normalized`() = runTest {
        // "lists and normalizes a page of batches".
        val server = TestServer(TestServer.json(GoogleBatchFixtures.listPage()))

        val result = model(server, mapOf("Provider-Header" to "provider")).doListBatches(
            BatchListOptions(limit = 2, cursor = "page-token-1", headers = mapOf("Operation-Header" to "operation")),
        )

        assertEquals(
            BatchListResult(
                batches = listOf(
                    BatchListItem(
                        "batches/batch-123",
                        BatchStatus(
                            state = BatchStatus.State.Pending,
                            rawStatus = "BATCH_STATE_RUNNING",
                            requestCounts = BatchRequestCounts(total = 3, pending = 2, completed = 1, failed = 0),
                            createdAt = "2026-08-04T12:34:56.123Z",
                        ),
                    ),
                    BatchListItem(
                        "batches/batch-122",
                        BatchStatus(
                            state = BatchStatus.State.Completed,
                            rawStatus = "BATCH_STATE_SUCCEEDED",
                            requestCounts = BatchRequestCounts(total = 2, pending = 0, completed = 2, failed = 0),
                            createdAt = "2026-08-04T12:34:56.123Z",
                        ),
                    ),
                ),
                nextCursor = "page-token-2",
            ),
            result,
        )
        val call = server.request()
        assertEquals("$BATCHES_URL?pageSize=2&pageToken=page-token-1", call.url)
        call.assertHeader("provider-header", "provider")
        call.assertHeader("operation-header", "operation")
    }

    @Test
    fun `an empty page is a terminal empty list`() = runTest {
        // "returns an empty terminal page for %j".
        for (body in listOf("{}", """{"operations":[],"nextPageToken":null}""")) {
            val server = TestServer(TestServer.json(body))
            assertEquals(BatchListResult(emptyList()), model(server).doListBatches(BatchListOptions()), body)
            assertEquals(BATCHES_URL, server.request().url)
        }
    }

    @Test
    fun `a batch under 20 MB is created with its requests inline`() = runTest {
        val server = TestServer(
            TestServer.json(
                operation(
                    metadata = mapOf(
                        "state" to JsonPrimitive("BATCH_STATE_PENDING"),
                        "batchStats" to parseJsonObject(
                            """{"requestCount":"1","successfulRequestCount":"0","failedRequestCount":"0",""" +
                                """"pendingRequestCount":"1"}""",
                        ),
                    ),
                    operation = mapOf("done" to JsonPrimitive(false)),
                ),
            ),
        )

        val result = model(server, mapOf("Provider-Header" to "provider")).doStartBatch(
            BatchStartOptions(
                requests = listOf(
                    BatchRequest(
                        "france",
                        CallOptions(
                            prompt = listOf(
                                ModelMessage.System("Answer with only the city name."),
                                ModelMessage.User(listOf(UserPart.Text("What is the capital of France?"))),
                            ),
                            maxOutputTokens = 20,
                            temperature = 0.2,
                            topP = 0.9,
                            topK = 10,
                            frequencyPenalty = 0.1,
                            presencePenalty = 0.2,
                            stopSequences = listOf("END"),
                            seed = 42,
                        ),
                    ),
                ),
                webhookUrl = "https://example.com/google-batch-webhook",
                headers = mapOf("Operation-Header" to "operation"),
            ),
        )

        assertEquals("batches/batch-123", result.batchId)
        assertEquals(BatchStatus.State.Pending, result.status.state)
        assertEquals("BATCH_STATE_PENDING", result.status.rawStatus)
        assertEquals(BatchRequestCounts(total = 1, pending = 1, completed = 0, failed = 0), result.status.requestCounts)
        assertEquals("2026-08-04T12:34:56.123Z", result.status.createdAt)
        // Gemini takes both penalties (DESIGN.md), so unlike the reference nothing here was dropped.
        assertTrue(result.warnings.isEmpty(), "nothing was unsupported: ${result.warnings}")

        assertEquals(listOf(CREATE_URL), server.calls.map { it.url })
        val call = server.request()
        assertEquals("POST", call.method)
        call.assertHeader("provider-header", "provider")
        call.assertHeader("operation-header", "operation")
        call.assertHeader("x-goog-api-key", "test-api-key")
        // The request is the live call's body, verbatim, keyed by the caller's id.
        call.assertBodyEquals(
            """
            {"batch":{"displayName":"ai-sdk-batch-test-id",
              "webhookConfig":{"uris":["https://example.com/google-batch-webhook"]},
              "inputConfig":{"requests":{"requests":[{
                "request":{
                  "generationConfig":{"maxOutputTokens":20,"temperature":0.2,"topK":10,"topP":0.9,
                    "frequencyPenalty":0.1,"presencePenalty":0.2,"stopSequences":["END"],"seed":42},
                  "contents":[{"role":"user","parts":[{"text":"What is the capital of France?"}]}],
                  "systemInstruction":{"parts":[{"text":"Answer with only the city name."}]}},
                "metadata":{"key":"france"}}]}}}}
            """,
        )
    }

    @Test
    fun `a creation body that reaches 20 MB goes up as a resumable file instead`() = runTest {
        val server = TestServer(
            // The session-open reply is headers only; the upload URL is one of them.
            TestServer.bytes(ByteArray(0)).withHeaders("x-goog-upload-url" to UPLOAD_SESSION_URL),
            TestServer.json(GoogleBatchFixtures.UPLOADED_FILE),
            TestServer.json(operation()),
        )
        // The reference's construction of the boundary: the body with both requests is EXACTLY 20 MB,
        // which is the first size that is not under it.
        fun inlined(id: String, prompt: String) =
            """{"request":{"generationConfig":{},"contents":[{"role":"user","parts":[{"text":"$prompt"}]}]},""" +
                """"metadata":{"key":"$id"}}"""
        fun inlineBody(largePrompt: String) =
            """{"batch":{"displayName":"ai-sdk-batch-test-id",""" +
                """"webhookConfig":{"uris":["https://example.com/google-batch-webhook"]},""" +
                """"inputConfig":{"requests":{"requests":[${inlined("small-request", "small")},""" +
                """${inlined("large-request", largePrompt)}]}}}}"""
        val emptyBodyBytes = inlineBody("").encodeToByteArray().size
        val prompt = "a".repeat(20_000_000 - emptyBodyBytes)
        assertEquals(20_000_000, inlineBody(prompt).encodeToByteArray().size)

        val result = model(server, mapOf("Provider-Header" to "provider")).doStartBatch(
            BatchStartOptions(
                requests = listOf(request("small-request", "small"), request("large-request", prompt)),
                webhookUrl = "https://example.com/google-batch-webhook",
                headers = mapOf("Operation-Header" to "operation"),
            ),
        )

        assertTrue(result.warnings.isEmpty())
        assertEquals(listOf(UPLOAD_START_URL, UPLOAD_SESSION_URL, CREATE_URL), server.calls.map { it.url })
        // The uploaded input is reported back under the provider key, so the caller can find or delete it.
        assertEquals(
            parseJsonObject("""{"inputFileId":"files/batch-input","inputFileExpiresAt":"2026-08-27T12:00:00Z"}"""),
            result.status.providerMetadata?.get(GOOGLE_PROVIDER_ID),
        )

        val start = server.request(0)
        start.assertHeader("provider-header", "provider")
        start.assertHeader("operation-header", "operation")
        start.assertHeader("x-goog-api-key", "test-api-key")
        start.assertHeader("x-goog-upload-protocol", "resumable")
        start.assertHeader("x-goog-upload-command", "start")
        start.assertHeader("x-goog-upload-header-content-type", "application/jsonl")
        start.assertBodyEquals("""{"file":{"display_name":"ai-sdk-batch-test-id-input"}}""")

        // The session URL is pre-authorized: no key, and none of the caller's headers either.
        val upload = server.request(1)
        upload.assertHeader("x-goog-upload-command", "upload, finalize")
        upload.assertHeader("x-goog-upload-offset", "0")
        upload.assertNoHeader("x-goog-api-key")
        upload.assertNoHeader("provider-header")
        upload.assertNoHeader("operation-header")
        val jsonl = upload.bodyText
        assertTrue(jsonl.startsWith("""{"key":"small-request","request":"""))
        assertTrue(jsonl.contains("\n{\"key\":\"large-request\",\"request\":"))
        assertTrue(jsonl.endsWith("\n"))

        val create = server.request(2)
        create.assertHeader("provider-header", "provider")
        create.assertHeader("operation-header", "operation")
        create.assertHeader("x-goog-api-key", "test-api-key")
        create.assertBodyEquals(
            """{"batch":{"displayName":"ai-sdk-batch-test-id",""" +
                """"webhookConfig":{"uris":["https://example.com/google-batch-webhook"]},""" +
                """"inputConfig":{"fileName":"files/batch-input"}}}""",
        )
    }

    @Test
    fun `the provider wires the batch model with its key`() = runTest {
        val server = TestServer(TestServer.json(operation()))

        val result = GoogleProvider(client = HttpClient(server.engine()), apiKey = "test-api-key")
            .batchLanguageModel("gemini-2.5-flash")
            .doStartBatch(BatchStartOptions(requests = listOf(request("france", "What is the capital of France?"))))

        assertEquals("batches/batch-123", result.batchId)
        val call = server.request()
        assertEquals(CREATE_URL, call.url)
        call.assertHeader("x-goog-api-key", "test-api-key")
        assertTrue(call.bodyJson().obj("batch")["displayName"].string()!!.startsWith("ai-sdk-batch-"))
    }

    @Test
    fun `a Google error on start surfaces as the API error it is`() = runTest {
        val server = TestServer(TestServer.error(400, GoogleBatchFixtures.INVALID_ARGUMENT))

        val error = assertFailsWith<APICallError> {
            model(server).doStartBatch(
                BatchStartOptions(requests = listOf(request("france", "What is the capital of France?"))),
            )
        }

        assertEquals(400, error.statusCode)
        assertEquals(CREATE_URL, error.url)
        assertTrue("The batch input was invalid." in error.message.orEmpty())
    }

    // --- Status --------------------------------------------------------------------------------------

    @Test
    fun `the state word is read after either prefix`() = runTest {
        val cases = listOf(
            "JOB_STATE_PENDING" to BatchStatus.State.Pending,
            "JOB_STATE_RUNNING" to BatchStatus.State.Pending,
            "JOB_STATE_SUCCEEDED" to BatchStatus.State.Completed,
            "JOB_STATE_FAILED" to BatchStatus.State.Failed,
            "JOB_STATE_CANCELLED" to BatchStatus.State.Failed,
            "JOB_STATE_EXPIRED" to BatchStatus.State.Failed,
            "BATCH_STATE_SUCCEEDED" to BatchStatus.State.Completed,
        )
        for ((rawStatus, expected) in cases) {
            val server = TestServer(TestServer.json(operation(metadata = mapOf("state" to JsonPrimitive(rawStatus)))))

            val result = model(server).doGetBatchStatus(status())

            assertEquals(expected, result.state, rawStatus)
            assertEquals(rawStatus, result.rawStatus)
            assertEquals(BATCH_URL, server.request().url)
        }
    }

    @Test
    fun `without a state word the operation's own done flag and error decide`() = runTest {
        val cases = listOf(
            Triple(false, null, BatchStatus.State.Pending),
            Triple(true, null, BatchStatus.State.Completed),
            Triple(true, """{"code":13,"message":"The operation failed."}""", BatchStatus.State.Failed),
        )
        for ((done, error, expected) in cases) {
            val server = TestServer(
                TestServer.json(
                    operation(
                        metadata = mapOf("state" to null),
                        operation = mapOf("done" to JsonPrimitive(done), "error" to error?.let { parseJsonObject(it) }),
                    ),
                ),
            )

            assertEquals(expected, model(server).doGetBatchStatus(status()).state, "done=$done error=$error")
        }
    }

    @Test
    fun `int64 counts, the timestamp and a top-level RPC error are normalized`() = runTest {
        val server = TestServer(
            TestServer.json(
                operation(
                    metadata = mapOf(
                        "state" to JsonPrimitive("BATCH_STATE_FAILED"),
                        "batchStats" to parseJsonObject(
                            """{"requestCount":"7","successfulRequestCount":"2","failedRequestCount":"3",""" +
                                """"pendingRequestCount":"2"}""",
                        ),
                    ),
                    operation = mapOf(
                        "error" to parseJsonObject(
                            """{"code":3,"message":"The batch input was invalid.","details":[{"reason":"INVALID_ARGUMENT"}]}""",
                        ),
                    ),
                ),
            ),
        )

        val result = model(server).doGetBatchStatus(status())

        assertEquals(BatchStatus.State.Failed, result.state)
        assertEquals("BATCH_STATE_FAILED", result.rawStatus)
        assertEquals(BatchRequestCounts(total = 7, pending = 2, completed = 2, failed = 3), result.requestCounts)
        assertEquals(BatchError(message = "The batch input was invalid.", code = "3"), result.error)
        assertEquals("2026-08-04T12:34:56.123Z", result.createdAt)
    }

    @Test
    fun `an omitted counter is a zero, not a missing total`() = runTest {
        val cases = listOf(
            """{"requestCount":"1","failedRequestCount":"1"}""" to
                BatchRequestCounts(total = 1, pending = 0, completed = 0, failed = 1),
            """{"requestCount":"2","pendingRequestCount":"2"}""" to
                BatchRequestCounts(total = 2, pending = 2, completed = 0, failed = 0),
        )
        for ((stats, expected) in cases) {
            val server = TestServer(TestServer.json(operation(metadata = mapOf("batchStats" to parseJsonObject(stats)))))

            assertEquals(expected, model(server).doGetBatchStatus(status()).requestCounts, stats)
        }
    }

    @Test
    fun `a Google error on status retrieval surfaces as the API error it is`() = runTest {
        val server = TestServer(TestServer.error(404, GoogleBatchFixtures.NOT_FOUND))

        val error = assertFailsWith<APICallError> { model(server).doGetBatchStatus(status()) }

        assertEquals(404, error.statusCode)
        assertEquals(BATCH_URL, error.url)
        assertTrue("Batch not found." in error.message.orEmpty())
    }

    // --- Results -------------------------------------------------------------------------------------

    @Test
    fun `a stored result decodes through the live mapper, metadata and all`() = runTest {
        val server = outputServer(
            """{"key":"france","response":${googleResponse("response-france", "Paris")}}""",
            GoogleBatchFixtures.GERMANY_ERROR_LINE,
        )

        val results = model(server).doGetBatchResults(status()).toList()

        assertEquals(2, results.size)
        val france = assertIs<BatchItemResult.Succeeded>(results[0])
        assertEquals("france", france.id)
        val result = france.result
        assertEquals(listOf<Content>(Content.Text("Paris")), result.content)
        assertEquals(FinishReason(FinishReason.Unified.Stop, raw = "STOP"), result.finishReason)
        assertEquals(10, result.usage.inputTokens.total)
        assertEquals(8, result.usage.inputTokens.noCache)
        assertEquals(2, result.usage.inputTokens.cacheRead)
        assertEquals(4, result.usage.outputTokens.total)
        assertEquals(3, result.usage.outputTokens.text)
        assertEquals(1, result.usage.outputTokens.reasoning)
        assertEquals(parseJsonObject(GoogleBatchFixtures.USAGE_METADATA), result.usage.raw)
        assertEquals("response-france", result.response?.metadata?.id)
        assertTrue(result.warnings.isEmpty())
        // The same seven keys the reference files under `google` — read off the one mapper both share.
        val metadata = result.providerMetadata?.get(GOOGLE_PROVIDER_ID)!!
        assertEquals(
            parseJsonObject(
                """{"promptFeedback":{"safetyRatings":[{"category":"HARM_CATEGORY_HATE_SPEECH","probability":"NEGLIGIBLE"}]},""" +
                    """"groundingMetadata":{"webSearchQueries":["capital of France"]},""" +
                    """"safetyRatings":[{"category":"HARM_CATEGORY_HATE_SPEECH","probability":"NEGLIGIBLE"}],""" +
                    """"usageMetadata":${GoogleBatchFixtures.USAGE_METADATA},""" +
                    """"finishMessage":"Generation completed.","serviceTier":"priority"}""",
            ),
            metadata,
        )

        val germany = assertIs<BatchItemResult.Failed>(results[1])
        assertEquals("germany", germany.id)
        assertEquals("The request was invalid.", germany.error.message)
        assertEquals("3", germany.error.code)
        assertEquals(listOf(BATCH_URL, OUTPUT_URL), server.calls.map { it.url })
    }

    @Test
    fun `inline results are read without downloading anything`() = runTest {
        val server = TestServer(
            TestServer.json(
                operation(
                    metadata = mapOf("output" to null),
                    operation = mapOf("response" to parseJsonObject(GoogleBatchFixtures.inlinedResponses())),
                ),
            ),
        )

        val results = model(server).doGetBatchResults(status()).toList()

        val france = assertIs<BatchItemResult.Succeeded>(results[0])
        assertEquals(listOf<Content>(Content.Text("Paris")), france.result.content)
        val germany = assertIs<BatchItemResult.Failed>(results[1])
        assertEquals(
            BatchError(message = "Resource has been exhausted.", type = "RESOURCE_EXHAUSTED", code = "8"),
            germany.error,
        )
        assertEquals(listOf(BATCH_URL), server.calls.map { it.url })
    }

    @Test
    fun `a numeric gRPC cancellation is a cancelled item, not a failed one`() = runTest {
        val server = outputServer(GoogleBatchFixtures.CANCELLED_LINE)

        val results = model(server).doGetBatchResults(status()).toList()

        assertEquals(
            listOf<BatchItemResult>(
                BatchItemResult.Cancelled(
                    "cancelled-request",
                    BatchError(message = "The request was cancelled.", code = "1"),
                ),
            ),
            results,
        )
    }

    @Test
    fun `a blocked prompt is a failed item naming the block reason`() = runTest {
        val server = outputServer(
            GoogleBatchFixtures.blockedLine("blocked-undefined", candidates = null),
            GoogleBatchFixtures.blockedLine("blocked-empty", candidates = "[]"),
        )

        val results = model(server).doGetBatchResults(status()).toList()

        assertEquals(2, results.size)
        for ((index, id) in listOf("blocked-undefined", "blocked-empty").withIndex()) {
            assertEquals(
                BatchItemResult.Failed(
                    id = id,
                    error = BatchError(
                        message = "Google blocked the batch request (SAFETY).",
                        type = "SAFETY",
                        code = "prompt_blocked",
                    ),
                    providerMetadata = mapOf(
                        GOOGLE_PROVIDER_ID to buildJsonObject {
                            put("promptFeedback", buildJsonObject { put("blockReason", "SAFETY") })
                        },
                    ),
                ),
                results[index],
            )
        }
    }

    @Test
    fun `the output file is read from the operation's response half too`() = runTest {
        val server = TestServer(
            TestServer.json(
                operation(
                    metadata = mapOf("output" to null),
                    operation = mapOf("response" to parseJsonObject("""{"responsesFile":"files/batch-output"}""")),
                ),
            ),
            TestServer.bytes(
                """{"key":"france","response":${googleResponse("response-france", "Paris")}}""".encodeToByteArray(),
            ),
        )

        val results = model(server).doGetBatchResults(status()).toList()

        val france = assertIs<BatchItemResult.Succeeded>(results.single())
        assertEquals(listOf<Content>(Content.Text("Paris")), france.result.content)
    }

    @Test
    fun `every segment of the file name is encoded into the download URL`() = runTest {
        val server = TestServer(
            TestServer.json(
                operation(
                    metadata = mapOf(
                        "output" to parseJsonObject("""{"responsesFile":"files/batch-output?alt=json#fragment"}"""),
                    ),
                ),
            ),
            TestServer.bytes(
                """{"key":"france","response":${googleResponse("response-france", "Paris")}}""".encodeToByteArray(),
            ),
        )

        val results = model(server).doGetBatchResults(status()).toList()

        assertIs<BatchItemResult.Succeeded>(results.single())
        assertEquals(
            listOf(
                BATCH_URL,
                "https://generativelanguage.googleapis.com/download/v1beta/files/" +
                    "batch-output%3Falt%3Djson%23fragment:download?alt=media",
            ),
            server.calls.map { it.url },
        )
    }

    @Test
    fun `a failed batch with no output has no results, not an error`() = runTest {
        val server = TestServer(
            TestServer.json(
                operation(metadata = mapOf("state" to JsonPrimitive("BATCH_STATE_FAILED"), "output" to null)),
            ),
        )

        assertEquals(emptyList(), model(server).doGetBatchResults(status()).toList())
        assertEquals(listOf(BATCH_URL), server.calls.map { it.url })
    }

    @Test
    fun `a pending batch has no results to fetch`() = runTest {
        val server = TestServer(
            TestServer.json(
                operation(
                    metadata = mapOf("state" to JsonPrimitive("BATCH_STATE_RUNNING"), "output" to null),
                    operation = mapOf("done" to JsonPrimitive(false)),
                ),
            ),
        )

        val error = assertFailsWith<InvalidArgumentError> { model(server).doGetBatchResults(status()).toList() }

        assertEquals("batchId", error.argument)
        assertEquals("Google batch \"batches/batch-123\" is not complete.", error.message)
    }

    @Test
    fun `a completed batch without output is a broken reply, not an empty one`() = runTest {
        val server = TestServer(TestServer.json(operation(metadata = mapOf("output" to null))))

        val error = assertFailsWith<InvalidResponseDataError> {
            model(server).doGetBatchResults(status()).toList()
        }

        assertEquals("Google batch \"batches/batch-123\" completed without batch output.", error.message)
    }

    @Test
    fun `an item that does not decode fails alone and the rest continue`() = runTest {
        val server = outputServer(
            GoogleBatchFixtures.INVALID_ITEM_LINE,
            """{"key":"valid-request","response":${googleResponse("response-valid", "Paris")}}""",
        )

        val results = model(server).doGetBatchResults(status()).toList()

        assertEquals(2, results.size)
        val invalid = assertIs<BatchItemResult.Failed>(results[0])
        assertEquals("invalid-request", invalid.id)
        assertEquals("Google returned an invalid GenerateContent batch result.", invalid.error.message)
        assertEquals("invalid_response", invalid.error.code)
        val valid = assertIs<BatchItemResult.Succeeded>(results[1])
        assertEquals(listOf<Content>(Content.Text("Paris")), valid.result.content)
    }

    @Test
    fun `a generated image decodes as an image result`() = runTest {
        // "converts generated image results".
        val server = outputServer(GoogleBatchFixtures.GENERATED_IMAGE_LINE)

        val result = assertIs<BatchItemResult.ImageSucceeded>(model(server).doGetBatchResults(status()).toList().single())

        assertEquals("image-1", result.id)
        assertEquals(listOf<BinaryData>(BinaryData.Bytes(Base64.decode("aGVsbG8="))), result.result.images)
        assertEquals(2, result.result.usage?.inputTokens)
        assertEquals(3, result.result.usage?.outputTokens)
        assertEquals(5, result.result.usage?.totalTokens)
        assertEquals(1, result.result.providerMetadata?.get(GOOGLE_PROVIDER_ID)?.arr("images")?.size)
        assertNull(result.result.isRetryable)
    }

    @Test
    fun `image, text and tool results all succeed, each as its own kind`() = runTest {
        // "fails unsupported items and continues with later results" — which, since images and tool
        // calls joined the batch, expects all three to succeed.
        val server = outputServer(
            GoogleBatchFixtures.IMAGE_ITEM_LINE,
            """{"key":"text-request","response":${googleResponse("response-text", "Paris")}}""",
            GoogleBatchFixtures.TOOL_ITEM_LINE,
        )

        val results = model(server).doGetBatchResults(status()).toList()

        assertEquals(3, results.size)
        assertEquals("image-request", assertIs<BatchItemResult.ImageSucceeded>(results[0]).id)
        assertEquals(listOf<Content>(Content.Text("Paris")), assertIs<BatchItemResult.Succeeded>(results[1]).result.content)
        val tool = assertIs<BatchItemResult.Succeeded>(results[2])
        assertEquals(
            listOf<Content>(Content.ToolCall(toolCallId = "call-1", toolName = "weather", input = """{"city":"Paris"}""")),
            tool.result.content,
        )
    }

    @Test
    fun `a file that is not an image still fails the item and names the kind`() = runTest {
        val server = outputServer(
            GoogleBatchFixtures.AUDIO_ITEM_LINE,
            """{"key":"text-request","response":${googleResponse("response-text", "Paris")}}""",
        )

        val results = model(server).doGetBatchResults(status()).toList()

        assertEquals(2, results.size)
        val audio = assertIs<BatchItemResult.Failed>(results[0])
        assertEquals("audio-request", audio.id)
        assertEquals(
            "Google returned a \"file\" content block, but that content is not supported in AI SDK text batches.",
            audio.error.message,
        )
        assertEquals("unsupported_content", audio.error.code)
        assertNull(audio.providerMetadata)
        assertIs<BatchItemResult.Succeeded>(results[1])
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject

}
