package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.BatchCancelResult
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
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.RecordedCall
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The Batch API over Responses, against the wire shapes of `openai-responses-batch.test.ts`.
 *
 * Four calls, four shapes: the JSONL upload (a multipart form whose FILE is the request list), the
 * batch creation body, the status document, and the JSONL result files. Each is asserted on the wire,
 * because every one of them is a place a key can be misspelled without anything failing here.
 */
class OpenAIResponsesBatchTest {

    private fun model(server: TestServer, extraHeaders: Map<String, String> = emptyMap()) =
        OpenAIProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-api-key",
            extraHeaders = extraHeaders,
        ).batchLanguageModel("gpt-5.6")

    private fun request(id: String, text: String, topK: Int? = null) = BatchRequest(
        id = id,
        options = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text(text)))), topK = topK),
    )

    private fun jsonl(vararg lines: String, separator: String = "\n") =
        TestServer.bytes(lines.joinToString(separator).encodeToByteArray(), "application/jsonl")

    private fun status(options: BatchOperationOptions = BatchOperationOptions("batch_123")) = options

    /** The uploaded JSONL, read back out of the multipart body the file part travelled in. */
    private fun RecordedCall.uploadedLines(): List<JsonObject> = bodyText
        .split(Regex("--[-A-Za-z0-9]+(--)?\r?\n"))
        .first { "filename=\"batch.jsonl\"" in it }
        .substringAfter("\r\n\r\n")
        .substringBeforeLast("\r\n")
        .trim()
        .split('\n')
        .map { parseJsonObject(it) }

    // --- Start ---------------------------------------------------------------------------------------

    @Test
    fun `a start uploads the requests as JSONL, then creates the batch against the file`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.BATCH_INPUT_FILE),
            TestServer.json(
                OpenAIFixtures.batchResponse(
                    status = "validating",
                    requestCounts = """{"total":2,"completed":0,"failed":0}""",
                ),
            ),
        )

        val result = model(server, extraHeaders = mapOf("Provider-Header" to "provider")).doStartBatch(
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
        assertEquals(
            BatchStatus(
                state = BatchStatus.State.Pending,
                rawStatus = "validating",
                requestCounts = BatchRequestCounts(total = 2, pending = 2, completed = 0, failed = 0),
                createdAt = "2023-11-14T22:13:20.000Z",
                expiresAt = "2023-11-15T22:13:20.000Z",
                // The upload the batch reads from, so a caller can find, extend or delete it.
                providerMetadata = mapOf(
                    OPENAI_PROVIDER_ID to buildJsonObject {
                        put("inputFileId", "file-input")
                        put("inputFileExpiresAt", "2023-11-16T22:13:20.000Z")
                    },
                ),
            ),
            result.status,
        )
        // The batch-wide warning has no request; the builder's own warning names the request it is about.
        assertEquals(
            listOf(null to "webhookUrl", "germany" to "topK"),
            result.warnings.map { it.requestId to (it.warning as Warning.Unsupported).feature },
        )
        assertEquals(
            "The OpenAI Batch API does not support per-batch webhook URLs.",
            (result.warnings[0].warning as Warning.Unsupported).details,
        )

        val upload = server.request(0)
        assertEquals("POST", upload.method)
        assertEquals("v1/files", upload.path)
        upload.assertMultipartField("purpose", "batch")
        upload.assertMultipartField("expires_after[anchor]", "created_at")
        upload.assertMultipartField("expires_after[seconds]", "172800")
        upload.assertMultipartFile("file", fileName = "batch.jsonl", contentType = "application/jsonl")
        upload.assertHeader("Authorization", "Bearer test-api-key")
        upload.assertHeader("Provider-Header", "provider")
        upload.assertHeader("Operation-Header", "operation")

        val lines = upload.uploadedLines()
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals("france", first["custom_id"].string())
        assertEquals("POST", first["method"].string())
        assertEquals("/v1/responses", first["url"].string())
        val body = first["body"]!!.jsonObject
        assertEquals("gpt-5.6", body["model"].string())
        assertEquals(
            """[{"role":"user","content":[{"type":"input_text","text":"What is the capital of France?"}]}]""",
            body["input"].toString(),
        )
        // The live builder can build for the streaming endpoint; a queued request must not say so.
        assertNull(body["stream"])
        assertEquals("germany", lines[1]["custom_id"].string())

        val create = server.request(1)
        assertEquals("v1/batches", create.path)
        create.assertBodyEquals(
            """{"input_file_id":"file-input","endpoint":"/v1/responses","completion_window":"24h"}""",
        )
        create.assertHeader("Authorization", "Bearer test-api-key")
        create.assertHeader("Provider-Header", "provider")
        create.assertHeader("Operation-Header", "operation")
    }

    @Test
    fun `a compaction trigger is an input item on the queued request too`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.BATCH_INPUT_FILE),
            TestServer.json(OpenAIFixtures.batchResponse()),
        )

        model(server).doStartBatch(
            BatchStartOptions(
                requests = listOf(
                    BatchRequest(
                        id = "compact",
                        options = CallOptions(
                            prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Compact this context.")))),
                            providerOptions = mapOf(
                                OPENAI_PROVIDER_ID to buildJsonObject { put("compactionTrigger", true) },
                            ),
                        ),
                    ),
                ),
            ),
        )

        val line = server.request(0).uploadedLines().single()
        assertEquals(
            """[{"role":"user","content":[{"type":"input_text","text":"Compact this context."}]},""" +
                """{"type":"compaction_trigger"}]""",
            line["body"]!!.jsonObject["input"].toString(),
        )
    }

    @Test
    fun `an aliased provider tool is refused at start, where the caller can rename it`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.BATCH_INPUT_FILE))
        val aliased = BatchRequest(
            id = "search",
            options = CallOptions(
                prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Latest news?")))),
                tools = listOf(
                    Tool.ProviderDefined(name = "my_search", id = "openai.web_search", args = JsonObject(emptyMap())),
                ),
            ),
        )

        // Results are read by a process that never saw the request, so the custom name cannot be put
        // back; a refusal now beats a result under a name the caller never registered later.
        assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doStartBatch(BatchStartOptions(requests = listOf(aliased)))
        }
        assertEquals(0, server.callCount)
    }

    // --- Status --------------------------------------------------------------------------------------

    @Test
    fun `every OpenAI status word lands on one of the poller's three states`() = runTest {
        val expected = listOf(
            "validating" to BatchStatus.State.Pending,
            "in_progress" to BatchStatus.State.Pending,
            "finalizing" to BatchStatus.State.Pending,
            "cancelling" to BatchStatus.State.Pending,
            "completed" to BatchStatus.State.Completed,
            "failed" to BatchStatus.State.Failed,
            "expired" to BatchStatus.State.Failed,
            "cancelled" to BatchStatus.State.Failed,
            // A word OpenAI adds later is waited on, not read as done and its partial artifacts fetched.
            "future_status" to BatchStatus.State.Pending,
        )
        for ((raw, state) in expected) {
            val server = TestServer(TestServer.json(OpenAIFixtures.batchResponse(status = raw)))

            val status = model(server).doGetBatchStatus(status())

            assertEquals("v1/batches/batch_123", server.request().path)
            assertEquals(state, status.state, "state for '$raw'")
            assertEquals(raw, status.rawStatus)
        }
    }

    @Test
    fun `counts, timestamps and the batch's own error are normalized`() = runTest {
        val server = TestServer(
            TestServer.json(
                OpenAIFixtures.batchResponse(
                    status = "failed",
                    requestCounts = """{"total":5,"completed":2,"failed":1}""",
                    errors = """{"data":[{"code":"invalid_request","message":"Invalid input file."}]}""",
                ),
            ),
        )

        val status = model(server).doGetBatchStatus(status())

        assertEquals(
            BatchStatus(
                state = BatchStatus.State.Failed,
                rawStatus = "failed",
                // OpenAI reports no pending count; it is what is left after completed and failed.
                requestCounts = BatchRequestCounts(total = 5, pending = 2, completed = 2, failed = 1),
                error = BatchError(message = "Invalid input file.", code = "invalid_request"),
                createdAt = "2023-11-14T22:13:20.000Z",
                expiresAt = "2023-11-15T22:13:20.000Z",
            ),
            status,
        )
    }

    @Test
    fun `an error entry with no message still names its code`() = runTest {
        val server = TestServer(
            TestServer.json(
                OpenAIFixtures.batchResponse(status = "failed", errors = """{"data":[{"code":"invalid_request"}]}"""),
            ),
        )

        val status = model(server).doGetBatchStatus(status())

        assertEquals(BatchStatus.State.Failed, status.state)
        assertEquals(BatchError(message = "OpenAI batch failed.", code = "invalid_request"), status.error)
    }

    @Test
    fun `counts that do not add up are reported as none rather than as a negative`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.batchResponse(requestCounts = """{"total":2,"completed":3,"failed":0}""")),
        )

        assertNull(model(server).doGetBatchStatus(status()).requestCounts)
    }

    @Test
    fun `the file ids ride the status so a caller can read the raw output itself`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.batchResponse(outputFileId = "file-output", errorFileId = "file-errors")),
        )

        val status = model(server).doGetBatchStatus(status())

        assertEquals(
            """{"outputFileId":"file-output","errorFileId":"file-errors"}""",
            status.providerMetadata?.get(OPENAI_PROVIDER_ID).toString(),
        )
    }

    @Test
    fun `the timestamp shape is the reference's, milliseconds always present`() {
        assertEquals("2023-11-14T22:13:20.000Z", openAIBatchTimestamp(1_700_000_000))
        assertEquals("2023-11-15T22:13:20.000Z", openAIBatchTimestamp(1_700_086_400))
    }

    // --- Results -------------------------------------------------------------------------------------

    @Test
    fun `results are read line by line, CRLF and all, after fresh batch metadata`() = runTest {
        val france = OpenAIFixtures.resultLine("france", OpenAIFixtures.responsesResultBody("Paris"))
        val germany = OpenAIFixtures.resultLine("germany", OpenAIFixtures.responsesResultBody("Berlin"))
        val server = TestServer(
            TestServer.json(OpenAIFixtures.batchResponse(outputFileId = "file-output")),
            jsonl(france, germany, separator = "\r\n"),
        )

        val results = model(server).doGetBatchResults(status()).toList()

        assertEquals(2, results.size)
        val first = assertIs<BatchItemResult.Succeeded>(results[0])
        assertEquals("france", first.id)
        assertEquals("Paris", assertIs<Content.Text>(first.result.content.single()).text)
        assertEquals(FinishReason(FinishReason.Unified.Stop, raw = null), first.result.finishReason)
        assertEquals(
            Usage(
                inputTokens = Usage.InputTokens(total = 10, noCache = 8, cacheRead = 2),
                outputTokens = Usage.OutputTokens(total = 3, text = 2, reasoning = 1),
                // A batch line's usage rides through the live response mapper, raw object and all.
                raw = parseJsonObject(
                    """{"input_tokens":10,"input_tokens_details":{"cached_tokens":2},"output_tokens":3,""" +
                        """"output_tokens_details":{"reasoning_tokens":1}}""",
                ),
            ),
            first.result.usage,
        )
        val response = first.result.response!!.metadata
        assertEquals("resp_123", response.id)
        // `created_at` is epoch seconds on the wire; the record is millis.
        assertEquals(1_700_000_000_000, response.timestamp)
        assertEquals("gpt-5.6", response.modelId)
        assertEquals(
            """{"responseId":"resp_123","serviceTier":"default"}""",
            first.result.providerMetadata?.get(OPENAI_PROVIDER_ID).toString(),
        )
        val second = assertIs<BatchItemResult.Succeeded>(results[1])
        assertEquals("germany", second.id)
        assertEquals("Berlin", assertIs<Content.Text>(second.result.content.single()).text)
        assertEquals(
            listOf("v1/batches/batch_123", "v1/files/file-output/content"),
            server.calls.map { it.path },
        )
        server.request(1).assertHeader("Authorization", "Bearer test-api-key")
    }

    @Test
    fun `reasoning keeps its item id and encrypted payload, in output order`() = runTest {
        val output = """[{"type":"reasoning","id":"reasoning-123","encrypted_content":"encrypted-reasoning",""" +
            """"summary":[{"type":"summary_text","text":"I should answer directly."}]},""" +
            """{"type":"message","role":"assistant","id":"msg_123","phase":null,"content":[""" +
            """{"type":"output_text","text":"Paris","logprobs":null,"annotations":[]}]}]"""
        val server = TestServer(
            TestServer.json(OpenAIFixtures.batchResponse(outputFileId = "file-output")),
            jsonl(OpenAIFixtures.resultLine("reasoning", OpenAIFixtures.responsesResultBodyWithOutput(output))),
        )

        val item = assertIs<BatchItemResult.Succeeded>(model(server).doGetBatchResults(status()).single())

        val reasoning = assertIs<Content.Reasoning>(item.result.content[0])
        assertEquals("I should answer directly.", reasoning.text)
        // THE assertion: the payload a stateless replay needs survives the batch path, because the
        // batch decodes through the same mapper the live call does.
        assertEquals(
            """{"itemId":"reasoning-123","reasoningEncryptedContent":"encrypted-reasoning"}""",
            reasoning.providerMetadata?.get(OPENAI_PROVIDER_ID).toString(),
        )
        assertEquals("Paris", assertIs<Content.Text>(item.result.content[1]).text)
    }

    @Test
    fun `a malformed line fails the stream after the good lines were delivered`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.batchResponse(outputFileId = "file-output")),
            jsonl(OpenAIFixtures.resultLine("france", OpenAIFixtures.responsesResultBody("Paris")), "{not json}", ""),
        )
        val seen = mutableListOf<BatchItemResult>()

        // A corrupt file must not hand the caller a shorter list than it submitted and call it done.
        assertFailsWith<JsonParseError> {
            model(server).doGetBatchResults(status()).collect { seen += it }
        }
        assertEquals(listOf("france"), seen.map { it.id })
    }

    @Test
    fun `the output file and the error file are both read, in that order`() = runTest {
        val batch = OpenAIFixtures.batchResponse(outputFileId = "file-output", errorFileId = "file-errors")
        val server = TestServer(
            TestServer.json(batch),
            TestServer.json(batch),
            jsonl(OpenAIFixtures.HTTP_ERROR_LINE),
            jsonl(*OpenAIFixtures.ERROR_FILE_LINES.toTypedArray()),
        )
        val model = model(server)

        model.doGetBatchStatus(status())
        val results = model.doGetBatchResults(status()).toList()

        assertEquals(
            listOf(
                BatchItemResult.Failed(
                    id = "http-error",
                    error = BatchError(
                        message = "Invalid request.",
                        type = "invalid_request_error",
                        code = "invalid_request",
                        statusCode = 400,
                    ),
                    providerMetadata = mapOf(OPENAI_PROVIDER_ID to buildJsonObject { put("requestId", "request-error") }),
                ),
                BatchItemResult.Cancelled(
                    id = "cancelled",
                    error = BatchError(message = "Batch cancelled.", code = "batch_cancelled"),
                ),
                BatchItemResult.Expired(
                    id = "expired",
                    error = BatchError(message = "Batch expired.", code = "batch_expired"),
                ),
                BatchItemResult.Failed(
                    id = "failed",
                    error = BatchError(message = "Request timed out.", code = "request_timeout"),
                ),
            ),
            results,
        )
        assertEquals(
            listOf(
                "v1/batches/batch_123",
                "v1/batches/batch_123",
                "v1/files/file-output/content",
                "v1/files/file-errors/content",
            ),
            server.calls.map { it.path },
        )
    }

    @Test
    fun `a pending batch has no results to fetch`() = runTest {
        val server = TestServer(
            TestServer.json(
                OpenAIFixtures.batchResponse(
                    status = "in_progress",
                    requestCounts = """{"total":2,"completed":1,"failed":0}""",
                ),
            ),
        )

        val error = assertFailsWith<InvalidArgumentError> {
            model(server).doGetBatchResults(status()).toList()
        }

        assertEquals("batchId", error.argument)
        assertEquals("OpenAI batch \"batch_123\" is not complete.", error.message)
    }

    @Test
    fun `an invalid item fails alone and the later results still arrive`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.batchResponse(outputFileId = "file-output")),
            jsonl(
                OpenAIFixtures.resultLine("invalid", """{"output":42}"""),
                OpenAIFixtures.resultLine("valid", OpenAIFixtures.responsesResultBody("Paris")),
            ),
        )

        val results = model(server).doGetBatchResults(status()).toList()

        assertEquals(2, results.size)
        val invalid = assertIs<BatchItemResult.Failed>(results[0])
        assertEquals("invalid", invalid.id)
        assertEquals(
            BatchError(message = "OpenAI returned an invalid Responses batch result.", code = "invalid_response"),
            invalid.error,
        )
        assertEquals("valid", assertIs<BatchItemResult.Succeeded>(results[1]).id)
    }

    @Test
    fun `a response carrying OpenAI's own error, or no output, is a failed item`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.batchResponse(outputFileId = "file-output")),
            jsonl(
                OpenAIFixtures.resultLine(
                    "refused",
                    """{"id":"resp_1","error":{"message":"boom","type":"server_error","code":"server_error"}}""",
                ),
                OpenAIFixtures.resultLine(
                    "truncated",
                    """{"id":"resp_2","incomplete_details":{"reason":"max_output_tokens"}}""",
                ),
                OpenAIFixtures.resultLine("empty", """{"id":"resp_3"}"""),
                """{"custom_id":"nothing","response":null,"error":null}""",
            ),
        )

        val results = model(server).doGetBatchResults(status()).toList().map { assertIs<BatchItemResult.Failed>(it) }

        assertEquals(BatchError(message = "boom", type = "server_error", code = "server_error"), results[0].error)
        assertEquals(
            BatchError(message = "OpenAI Responses returned no output (max_output_tokens).", code = "invalid_response"),
            results[1].error,
        )
        assertEquals(
            BatchError(message = "OpenAI Responses returned no output.", code = "invalid_response"),
            results[2].error,
        )
        assertEquals(
            BatchError(
                message = "OpenAI returned a batch result without a response or error.",
                code = "invalid_batch_result",
            ),
            results[3].error,
        )
    }

    @Test
    fun `a 4xx with no OpenAI envelope names its status code`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.batchResponse(outputFileId = "file-output")),
            jsonl("""{"custom_id":"gateway","response":{"status_code":502,"body":"<html>bad gateway</html>"},"error":null}"""),
        )

        val item = assertIs<BatchItemResult.Failed>(model(server).doGetBatchResults(status()).single())

        assertEquals(
            BatchError(message = "OpenAI batch request failed with status code 502.", statusCode = 502),
            item.error,
        )
    }

    @Test
    fun `a completed batch without output is a vendor defect, not an empty result`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.batchResponse()))

        val error = assertFailsWith<InvalidResponseDataError> {
            model(server).doGetBatchResults(status()).toList()
        }

        assertEquals("OpenAI batch \"batch_123\" completed without batch output.", error.message)
    }

    @Test
    fun `a failed batch with no files reports nothing, its failure being on the status`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.batchResponse(status = "failed")))

        assertEquals(emptyList(), model(server).doGetBatchResults(status()).toList())
    }

    /**
     * Where this port departs from the reference, deliberately: `openai-responses-batch.ts` fails a
     * result carrying a tool call or a provider-run tool with `unsupported_content`, because its batch
     * runtime is text-only. The contract here promises the live result, and the live decoder is the one
     * being reused, so the model's whole answer comes back.
     */
    @Test
    fun `tool calls and provider-run tools decode exactly as the live path decodes them`() = runTest {
        val functionCall = """[{"type":"function_call","id":"function-call","call_id":"call-123",""" +
            """"name":"get_weather","arguments":"{\"city\":\"Paris\"}","namespace":null,"caller":null}]"""
        val customToolCall = """[{"type":"custom_tool_call","id":"custom-tool-call","call_id":"call-123",""" +
            """"name":"shell","input":"echo Paris"}]"""
        val image = """[{"type":"image_generation_call","id":"image-123","result":"aW1hZ2U="}]"""
        val server = TestServer(
            TestServer.json(
                OpenAIFixtures.batchResponse(
                    outputFileId = "file-output",
                    requestCounts = """{"total":4,"completed":4,"failed":0}""",
                ),
            ),
            jsonl(
                OpenAIFixtures.resultLine("function-call", OpenAIFixtures.responsesResultBodyWithOutput(functionCall)),
                OpenAIFixtures.resultLine("custom-tool-call", OpenAIFixtures.responsesResultBodyWithOutput(customToolCall)),
                OpenAIFixtures.resultLine("image", OpenAIFixtures.responsesResultBodyWithOutput(image)),
                OpenAIFixtures.resultLine("valid", OpenAIFixtures.responsesResultBody("Paris")),
            ),
        )

        val results = model(server).doGetBatchResults(status()).toList()

        assertEquals(4, results.size)
        val call = assertIs<BatchItemResult.Succeeded>(results[0])
        val toolCall = assertIs<Content.ToolCall>(call.result.content.single())
        assertEquals("call-123", toolCall.toolCallId)
        assertEquals("get_weather", toolCall.toolName)
        assertEquals("""{"city":"Paris"}""", toolCall.input)
        // A pending client-side call is what makes the finish reason `tool-calls`, exactly as live.
        assertEquals(FinishReason.Unified.ToolCalls, call.result.finishReason.unified)

        val custom = assertIs<BatchItemResult.Succeeded>(results[1])
        val customCall = assertIs<Content.ToolCall>(custom.result.content.single())
        assertEquals("shell", customCall.toolName)
        assertEquals("echo Paris", customCall.input)

        val generated = assertIs<BatchItemResult.Succeeded>(results[2])
        val pair = generated.result.content
        assertTrue(assertIs<Content.ToolCall>(pair[0]).providerExecuted)
        assertEquals("image_generation", assertIs<Content.ToolResult>(pair[1]).toolName)
        assertEquals(FinishReason.Unified.Stop, generated.result.finishReason.unified)

        assertEquals("valid", assertIs<BatchItemResult.Succeeded>(results[3]).id)
        assertEquals(
            listOf("v1/batches/batch_123", "v1/files/file-output/content"),
            server.calls.map { it.path },
        )
    }

    @Test
    fun `logprobs that were asked for ride the result's metadata`() = runTest {
        val output = """[{"type":"message","role":"assistant","id":"msg_1","content":[""" +
            """{"type":"output_text","text":"Paris","logprobs":[{"token":"Paris","logprob":-0.1,"top_logprobs":[]}],""" +
            """"annotations":[]}]}]"""
        val server = TestServer(
            TestServer.json(OpenAIFixtures.batchResponse(outputFileId = "file-output")),
            jsonl(OpenAIFixtures.resultLine("lp", OpenAIFixtures.responsesResultBodyWithOutput(output))),
        )

        val item = assertIs<BatchItemResult.Succeeded>(model(server).doGetBatchResults(status()).single())

        val metadata = item.result.providerMetadata?.get(OPENAI_PROVIDER_ID)!!
        assertEquals(1, metadata.arr("logprobs")!!.size)
        assertEquals("resp_123", metadata["responseId"].string())
    }

    // --- The 2026-09 delta: request types, per-request models, expiry, cancel, list -------------------

    @Test
    fun `an image request is refused before anything is uploaded`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.BATCH_INPUT_FILE))

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doStartBatch(
                BatchStartOptions(requests = listOf(BatchRequest.Image("image-1", ImageCallOptions(prompt = "A cat")))),
            )
        }

        assertEquals("batch request type: image", error.functionality)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `mixed models are refused before anything is uploaded`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.BATCH_INPUT_FILE))

        val error = assertFailsWith<InvalidArgumentError> {
            model(server).doStartBatch(
                BatchStartOptions(
                    requests = listOf(
                        request("first", "First request"),
                        request("second", "Second request").copy(modelId = "gpt-5-mini"),
                    ),
                ),
            )
        }

        assertEquals(
            "The OpenAI Batch API requires all requests in a batch to use the same model. " +
                "Found \"gpt-5.6\" and \"gpt-5-mini\".",
            error.message,
        )
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a request's own model is the one its line names`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.BATCH_INPUT_FILE),
            TestServer.json(OpenAIFixtures.batchResponse()),
        )

        model(server).doStartBatch(
            BatchStartOptions(
                requests = listOf(
                    request("a", "First").copy(modelId = "gpt-5-mini"),
                    request("b", "Second").copy(modelId = "gpt-5-mini"),
                ),
            ),
        )

        assertEquals(
            listOf("gpt-5-mini", "gpt-5-mini"),
            server.request(0).uploadedLines().map { it["body"]!!.jsonObject["model"].string() },
        )
    }

    @Test
    fun `inputFileExpiresAfter sets the upload's expiry, and an invalid one is refused first`() = runTest {
        val server = TestServer(
            TestServer.json(OpenAIFixtures.BATCH_INPUT_FILE),
            TestServer.json(OpenAIFixtures.batchResponse()),
        )

        model(server).doStartBatch(
            BatchStartOptions(
                requests = listOf(request("france", "What is the capital of France?")),
                providerOptions = mapOf(OPENAI_PROVIDER_ID to buildJsonObject { put("inputFileExpiresAfter", 3600) }),
            ),
        )
        server.request(0).assertMultipartField("expires_after[anchor]", "created_at")
        server.request(0).assertMultipartField("expires_after[seconds]", "3600")

        val untouched = TestServer(TestServer.json(OpenAIFixtures.BATCH_INPUT_FILE))
        assertFailsWith<InvalidArgumentError> {
            model(untouched).doStartBatch(
                BatchStartOptions(
                    requests = listOf(request("france", "x")),
                    providerOptions = mapOf(OPENAI_PROVIDER_ID to buildJsonObject { put("inputFileExpiresAfter", 100) }),
                ),
            )
        }
        assertEquals(0, untouched.callCount)
    }

    @Test
    fun `an upload response without an expiry reports the file id alone`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"file-input","object":"file"}"""),
            TestServer.json(OpenAIFixtures.batchResponse()),
        )

        val result = model(server).doStartBatch(BatchStartOptions(requests = listOf(request("france", "x"))))

        assertEquals(
            buildJsonObject { put("inputFileId", "file-input") },
            result.status.providerMetadata?.get(OPENAI_PROVIDER_ID),
        )
    }

    @Test
    fun `a cancel is a POST with an empty body, and an ask rather than a guarantee`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.batchResponse(status = "cancelling")))

        val result = model(server, extraHeaders = mapOf("Provider-Header" to "provider")).doCancelBatch(
            BatchOperationOptions("batch_123", headers = mapOf("Operation-Header" to "operation")),
        )

        assertEquals(BatchCancelResult(), result)
        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("v1/batches/batch_123/cancel", call.path)
        call.assertBodyEquals("{}")
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Provider-Header", "provider")
        call.assertHeader("Operation-Header", "operation")
    }

    @Test
    fun `a listing is one normalized page, cursored by the last id`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"object":"list","data":[""" +
                    OpenAIFixtures.batchResponse(status = "in_progress", requestCounts = """{"total":3,"completed":1,"failed":0}""") +
                    "," + OpenAIFixtures.batchResponse(id = "batch_122") +
                    """],"first_id":"batch_123","last_id":"batch_122","has_more":true}""",
            ),
        )

        val result = model(server, extraHeaders = mapOf("Provider-Header" to "provider")).doListBatches(
            BatchListOptions(limit = 2, cursor = "batch_122", headers = mapOf("Operation-Header" to "operation")),
        )

        assertEquals(
            BatchListResult(
                batches = listOf(
                    BatchListItem(
                        "batch_123",
                        BatchStatus(
                            state = BatchStatus.State.Pending,
                            rawStatus = "in_progress",
                            requestCounts = BatchRequestCounts(total = 3, pending = 2, completed = 1, failed = 0),
                            createdAt = "2023-11-14T22:13:20.000Z",
                            expiresAt = "2023-11-15T22:13:20.000Z",
                        ),
                    ),
                    BatchListItem(
                        "batch_122",
                        BatchStatus(
                            state = BatchStatus.State.Completed,
                            rawStatus = "completed",
                            requestCounts = BatchRequestCounts(total = 2, pending = 0, completed = 2, failed = 0),
                            createdAt = "2023-11-14T22:13:20.000Z",
                            expiresAt = "2023-11-15T22:13:20.000Z",
                        ),
                    ),
                ),
                nextCursor = "batch_122",
            ),
            result,
        )
        val call = server.request()
        assertEquals(mapOf("limit" to "2", "after" to "batch_122"), call.query)
        call.assertHeader("Provider-Header", "provider")
        call.assertHeader("Operation-Header", "operation")
    }

    @Test
    fun `the last page carries no cursor`() = runTest {
        val server = TestServer(
            TestServer.json("""{"object":"list","data":[],"first_id":null,"last_id":null,"has_more":false}"""),
        )

        assertEquals(BatchListResult(batches = emptyList()), model(server).doListBatches(BatchListOptions()))
    }
}
