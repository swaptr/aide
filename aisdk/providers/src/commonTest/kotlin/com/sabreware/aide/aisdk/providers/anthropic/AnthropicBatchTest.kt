package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.BatchCancelResult
import com.sabreware.aide.aisdk.BatchItemResult
import com.sabreware.aide.aisdk.BatchListOptions
import com.sabreware.aide.aisdk.BatchOperationOptions
import com.sabreware.aide.aisdk.BatchRequest
import com.sabreware.aide.aisdk.BatchStartOptions
import com.sabreware.aide.aisdk.BatchStatus
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The Message Batches API, against the wire shapes of `anthropic-messages-batch.test.ts`.
 *
 * The one assertion that justifies the whole file: a stored thinking block's SIGNATURE comes back on
 * the decoded reasoning content — batch results ride through the same mapper as the live stream, so the
 * replay guarantees hold for a result that never streamed.
 */
class AnthropicBatchTest {

    private fun model(server: TestServer, extraHeaders: Map<String, String> = emptyMap()) = AnthropicProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        extraHeaders = extraHeaders,
    ).batchLanguageModel("claude-sonnet-4-5")

    private fun prompt(text: String = "Hello") =
        listOf(ModelMessage.User(listOf(UserPart.Text(text))))

    private fun request(id: String = "req-1") = BatchRequest(id, CallOptions(prompt = prompt()))

    private fun batchJson(status: String, resultsUrl: String? = null, archivedAt: String? = null) =
        buildJsonObject {
            put("id", "msgbatch_123")
            put("type", "message_batch")
            put("processing_status", status)
            put(
                "request_counts",
                buildJsonObject {
                    put("processing", 1)
                    put("succeeded", 2)
                    put("errored", 1)
                    put("canceled", 1)
                    put("expired", 1)
                },
            )
            put("created_at", "2026-08-30T11:00:00Z")
            put("expires_at", "2026-08-31T11:00:00Z")
            resultsUrl?.let { put("results_url", it) }
            archivedAt?.let { put("archived_at", it) }
        }.toString()

    // --- Start ---------------------------------------------------------------------------------------

    @Test
    fun `a start posts every request as custom_id plus params, with no stream flag`() = runTest {
        val server = TestServer(TestServer.json(batchJson("in_progress")))

        val result = model(server).doStartBatch(
            BatchStartOptions(requests = listOf(request("france"), request("germany"))),
        )

        val call = server.request()
        assertEquals("v1/messages/batches", call.path)
        val requests = call.bodyJson()["requests"]!!.jsonArray
        assertEquals(2, requests.size)
        val first = requests[0].jsonObject
        assertEquals("france", first["custom_id"].string())
        val params = first["params"]!!.jsonObject
        assertEquals("claude-sonnet-4-5", params["model"].string())
        // The live builder always builds for the streaming endpoint; a queued request must not say so.
        assertTrue("stream" !in params)
        assertTrue("messages" in params)

        assertEquals("msgbatch_123", result.batchId)
        assertEquals(BatchStatus.State.Pending, result.status.state)
        assertEquals("in_progress", result.status.rawStatus)
    }

    @Test
    fun `batch-level betas ride the header, deduplicated`() = runTest {
        val server = TestServer(TestServer.json(batchJson("in_progress")))

        model(server).doStartBatch(
            BatchStartOptions(
                requests = listOf(request()),
                providerOptions = mapOf(
                    ANTHROPIC_PROVIDER_ID to buildJsonObject {
                        put("anthropicBeta", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("my-beta")) })
                    },
                ),
            ),
        )

        val betas = server.request().header("anthropic-beta").orEmpty().split(',')
        assertTrue("my-beta" in betas)
        assertEquals(betas.toSet().size, betas.size, "betas must be deduplicated")
    }

    @Test
    fun `a webhook has nowhere to go, and the warning says so`() = runTest {
        val server = TestServer(TestServer.json(batchJson("in_progress")))

        val result = model(server).doStartBatch(
            BatchStartOptions(requests = listOf(request()), webhookUrl = "https://example.com/hook"),
        )

        val warning = result.warnings.single().warning
        assertIs<Warning.Unsupported>(warning)
        assertEquals("webhookUrl", warning.feature)
    }

    @Test
    fun `request ids are validated before anything is sent`() = runTest {
        val server = TestServer(TestServer.json(batchJson("in_progress")))
        val model = model(server)

        assertFailsWith<InvalidArgumentError> {
            model.doStartBatch(BatchStartOptions(requests = listOf(request("bad id!"))))
        }
        assertFailsWith<InvalidArgumentError> {
            model.doStartBatch(BatchStartOptions(requests = listOf(request("dup"), request("dup"))))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a per-request beta is a refusal, not a silent batch-wide upgrade`() = runTest {
        val server = TestServer(TestServer.json(batchJson("in_progress")))

        val withBeta = BatchRequest(
            "req-1",
            CallOptions(
                prompt = prompt(),
                providerOptions = mapOf(
                    ANTHROPIC_PROVIDER_ID to buildJsonObject {
                        put("anthropicBeta", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("x")) })
                    },
                ),
            ),
        )

        assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doStartBatch(BatchStartOptions(requests = listOf(withBeta)))
        }
        assertEquals(0, server.callCount)
    }

    // --- Status --------------------------------------------------------------------------------------

    @Test
    fun `ended is the one terminal word, and the counts are normalized`() = runTest {
        val server = TestServer(TestServer.json(batchJson("ended", resultsUrl = "https://api.anthropic.com/v1/x")))

        val status = model(server).doGetBatchStatus(BatchOperationOptions("msgbatch_123"))

        assertEquals("v1/messages/batches/msgbatch_123", server.request().path)
        assertEquals(BatchStatus.State.Completed, status.state)
        val counts = status.requestCounts!!
        assertEquals(6, counts.total)
        assertEquals(1, counts.pending)
        assertEquals(2, counts.completed)
        // errored + canceled + expired: the three ways an item can end without an answer.
        assertEquals(3, counts.failed)
        assertEquals(
            "https://api.anthropic.com/v1/x",
            status.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get("resultsUrl").string(),
        )
    }

    // --- Results -------------------------------------------------------------------------------------

    private val succeededMessage = """{"id":"msg_1","type":"message","role":"assistant",""" +
        """"model":"claude-sonnet-4-5","content":[""" +
        """{"type":"thinking","thinking":"Let me think.","signature":"sig-abc"},""" +
        """{"type":"redacted_thinking","data":"opaque-blob"},""" +
        """{"type":"text","text":"Paris."},""" +
        """{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Paris"}}],""" +
        """"stop_reason":"tool_use","stop_sequence":null,""" +
        """"usage":{"input_tokens":10,"output_tokens":25,""" +
        """"output_tokens_details":{"thinking_tokens":5}}}"""

    private fun resultsJsonl(): String = listOf(
        """{"custom_id":"france","result":{"type":"succeeded","message":$succeededMessage}}""",
        """{"custom_id":"broken","result":{"type":"errored","error":{"type":"error",""" +
            """"error":{"type":"invalid_request_error","message":"max_tokens too large"},""" +
            """"request_id":"req_011"}}}""",
        """{"custom_id":"canceled","result":{"type":"canceled"}}""",
        """{"custom_id":"expired","result":{"type":"expired"}}""",
        """{"custom_id":"weird","result":{"type":"someday-new"}}""",
    ).joinToString("\n")

    private fun resultsServer() = TestServer(
        TestServer.json(batchJson("ended", resultsUrl = "https://api.anthropic.com/v1/messages/batches/msgbatch_123/results")),
        TestServer.bytes(resultsJsonl().encodeToByteArray(), "application/x-jsonl"),
    )

    @Test
    fun `a pending batch has no results to fetch`() = runTest {
        val server = TestServer(TestServer.json(batchJson("in_progress")))

        assertFailsWith<InvalidArgumentError> {
            model(server).doGetBatchResults(BatchOperationOptions("msgbatch_123")).toList()
        }
    }

    @Test
    fun `an archived batch says so instead of 404ing`() = runTest {
        val server = TestServer(
            TestServer.json(batchJson("ended", resultsUrl = "https://x", archivedAt = "2026-08-31T00:00:00Z")),
        )

        assertFailsWith<InvalidArgumentError> {
            model(server).doGetBatchResults(BatchOperationOptions("msgbatch_123")).toList()
        }
    }

    @Test
    fun `a stored result decodes through the live mapper, signature intact`() = runTest {
        val server = resultsServer()

        val items = model(server).doGetBatchResults(BatchOperationOptions("msgbatch_123")).toList()
        assertEquals(5, items.size)

        val succeeded = assertIs<BatchItemResult.Succeeded>(items[0])
        assertEquals("france", succeeded.id)
        val content = succeeded.result.content
        // Order preserved: thinking, redacted thinking, text, tool call — exactly as stored.
        val reasoning = assertIs<Content.Reasoning>(content[0])
        assertEquals("Let me think.", reasoning.text)
        // THE assertion: the signature a live stream delivers as signature_delta survives the batch
        // path too, because there is only one decode path.
        assertEquals(
            "sig-abc",
            reasoning.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get(ANTHROPIC_SIGNATURE_KEY).string(),
        )
        val redacted = assertIs<Content.Reasoning>(content[1])
        assertEquals(
            "opaque-blob",
            redacted.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get(ANTHROPIC_REDACTED_KEY).string(),
        )
        assertEquals("Paris.", assertIs<Content.Text>(content[2]).text)
        val toolCall = assertIs<Content.ToolCall>(content[3])
        assertEquals("toolu_1", toolCall.toolCallId)
        assertEquals("get_weather", toolCall.toolName)
        assertEquals("""{"city":"Paris"}""", toolCall.input)

        assertEquals(FinishReason.Unified.ToolCalls, succeeded.result.finishReason.unified)
        assertEquals(10, succeeded.result.usage.inputTokens.total)
        assertEquals(25, succeeded.result.usage.outputTokens.total)
        assertEquals(5, succeeded.result.usage.outputTokens.reasoning)
    }

    @Test
    fun `the other outcomes stay distinct`() = runTest {
        val server = resultsServer()

        val items = model(server).doGetBatchResults(BatchOperationOptions("msgbatch_123")).toList()

        val failed = assertIs<BatchItemResult.Failed>(items[1])
        assertEquals("max_tokens too large", failed.error.message)
        assertEquals("invalid_request_error", failed.error.type)
        assertEquals(
            "req_011",
            failed.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get("requestId").string(),
        )
        assertIs<BatchItemResult.Cancelled>(items[2])
        assertIs<BatchItemResult.Expired>(items[3])
        // A result type this port has never seen is a FAILED item, not a crash and not a silent skip.
        val unknown = assertIs<BatchItemResult.Failed>(items[4])
        assertEquals("invalid_response", unknown.error.code)
    }

    @Test
    fun `text citations come back as metadata plus a source`() = runTest {
        val cited = """{"custom_id":"citation","result":{"type":"succeeded","message":""" +
            """{"id":"msg_2","type":"message","role":"assistant","model":"claude-sonnet-4-5",""" +
            """"content":[{"type":"text","text":"Paris is the capital.","citations":[""" +
            """{"type":"web_search_result_location","url":"https://en.wikipedia.org/wiki/Paris",""" +
            """"title":"Paris","cited_text":"Paris is the capital of France.",""" +
            """"encrypted_index":"enc-1"}]}],"stop_reason":"end_turn",""" +
            """"usage":{"input_tokens":5,"output_tokens":7}}}}"""
        val server = TestServer(
            TestServer.json(batchJson("ended", resultsUrl = "https://api.anthropic.com/v1/r")),
            TestServer.bytes(cited.encodeToByteArray(), "application/x-jsonl"),
        )

        val items = model(server).doGetBatchResults(BatchOperationOptions("msgbatch_123")).toList()
        val content = assertIs<BatchItemResult.Succeeded>(items.single()).result.content

        val text = assertIs<Content.Text>(content[0])
        val citations = text.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get("citations")
        assertTrue(citations != null && citations.jsonArray.size == 1)
        val source = assertIs<Content.Source.Url>(content[1])
        assertEquals("https://en.wikipedia.org/wiki/Paris", source.url)
        assertEquals("Paris", source.title)
        assertEquals(
            "Paris is the capital of France.",
            source.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get("citedText").string(),
        )
    }

    // --- The 2026-09 upstream delta (reference `anthropic-batch.test.ts`) -------------------------

    @Test
    fun `an image request is refused before anything is sent`() = runTest {
        val server = TestServer(TestServer.json(batchJson("in_progress")))

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doStartBatch(
                BatchStartOptions(requests = listOf(BatchRequest.Image("image-1", ImageCallOptions(prompt = "a cat")))),
            )
        }

        assertEquals("batch request type: image", error.functionality)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a request runs on its own model when it names one`() = runTest {
        val server = TestServer(TestServer.json(batchJson("in_progress")))

        model(server).doStartBatch(
            BatchStartOptions(
                requests = listOf(
                    BatchRequest("france", CallOptions(prompt = prompt()), modelId = "claude-3-haiku-20240307"),
                    BatchRequest("germany", CallOptions(prompt = prompt())),
                ),
            ),
        )

        val requests = server.request().bodyJson()["requests"]!!.jsonArray
        assertEquals("claude-3-haiku-20240307", requests[0].jsonObject["params"]!!.jsonObject["model"].string())
        // No model of its own: the batch model's is the default.
        assertEquals("claude-sonnet-4-5", requests[1].jsonObject["params"]!!.jsonObject["model"].string())
    }

    @Test
    fun `thinking binding controls ride a batch request and its beta`() = runTest {
        // "starts a batch with thinking binding controls" — `display: summarized` is this port's
        // standing addition to adaptive thinking (DESIGN.md).
        val server = TestServer(TestServer.json(batchJson("in_progress")))

        model(server).doStartBatch(
            BatchStartOptions(
                requests = listOf(
                    BatchRequest(
                        "preserved-thinking",
                        CallOptions(
                            prompt = prompt("Continue the conversation."),
                            maxOutputTokens = 4096,
                            providerOptions = mapOf(
                                ANTHROPIC_PROVIDER_ID to buildJsonObject {
                                    putJsonObject("thinking") {
                                        put("type", "adaptive")
                                        putJsonObject("blockBinding") { put("prefixMismatchBehavior", "error") }
                                    }
                                },
                            ),
                        ),
                        modelId = "claude-fable-5-1",
                    ),
                ),
            ),
        )

        val call = server.request()
        assertEquals(
            parseJsonObject(
                """
                {"requests":[{"custom_id":"preserved-thinking","params":{
                  "max_tokens":4096,
                  "messages":[{"content":[{"text":"Continue the conversation.","type":"text"}],"role":"user"}],
                  "model":"claude-fable-5-1",
                  "thinking":{"block_binding":{"prefix_mismatch_behavior":"error"},"display":"summarized","type":"adaptive"}
                }}]}
                """,
            ),
            call.bodyJson(),
        )
        assertEquals("thinking-binding-controls-2026-08-01", call.header("anthropic-beta"))
    }

    @Test
    fun `tools ride each request's params`() = runTest {
        val server = TestServer(TestServer.json(batchJson("in_progress")))

        model(server).doStartBatch(
            BatchStartOptions(
                requests = listOf(
                    BatchRequest(
                        "weather",
                        CallOptions(
                            prompt = prompt(),
                            tools = listOf(Tool.Function("get_weather", buildJsonObject { put("type", "object") })),
                        ),
                    ),
                ),
            ),
        )

        val params = server.request().bodyJson()["requests"]!!.jsonArray.single().jsonObject["params"]!!.jsonObject
        assertEquals("get_weather", params["tools"]!!.jsonArray.single().jsonObject["name"].string())
    }

    @Test
    fun `cancel posts an empty body to the cancel endpoint with both header sets`() = runTest {
        val server = TestServer(TestServer.json(batchJson("canceling")))

        val result = model(server, extraHeaders = mapOf("Provider-Header" to "provider")).doCancelBatch(
            BatchOperationOptions("msgbatch_123", headers = mapOf("Operation-Header" to "operation")),
        )

        assertEquals(BatchCancelResult(), result)
        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("v1/messages/batches/msgbatch_123/cancel", call.path)
        assertEquals(parseJsonObject("{}"), call.bodyJson())
        call.assertHeader("x-api-key", "test-api-key")
        call.assertHeader("provider-header", "provider")
        call.assertHeader("operation-header", "operation")
    }

    private fun listJson(vararg batches: String, hasMore: Boolean, lastId: String?) =
        """{"data":[${batches.joinToString(",")}],"first_id":${if (batches.isEmpty()) "null" else "\"msgbatch_123\""},""" +
            """"last_id":${lastId?.let { "\"$it\"" } ?: "null"},"has_more":$hasMore}"""

    @Test
    fun `a page of batches is listed and normalized`() = runTest {
        val inProgress = """{"id":"msgbatch_123","type":"message_batch","processing_status":"in_progress",""" +
            """"request_counts":{"processing":2,"succeeded":1,"errored":0,"canceled":0,"expired":0},""" +
            """"created_at":"2026-08-30T11:00:00Z","expires_at":"2026-08-31T11:00:00Z"}"""
        val ended = """{"id":"msgbatch_122","type":"message_batch","processing_status":"ended",""" +
            """"request_counts":{"processing":0,"succeeded":1,"errored":0,"canceled":0,"expired":0},""" +
            """"created_at":"2026-08-30T10:00:00Z","expires_at":"2026-08-31T10:00:00Z"}"""
        val server = TestServer(TestServer.json(listJson(inProgress, ended, hasMore = true, lastId = "msgbatch_122")))

        val page = assertNotNull(
            model(server, extraHeaders = mapOf("Provider-Header" to "provider")).doListBatches(
                BatchListOptions(limit = 2, cursor = "msgbatch_122", headers = mapOf("Operation-Header" to "operation")),
            ),
        )

        val call = server.request()
        assertEquals("GET", call.method)
        assertEquals("v1/messages/batches", call.path)
        assertEquals(mapOf("limit" to "2", "after_id" to "msgbatch_122"), call.query)
        call.assertHeader("provider-header", "provider")
        call.assertHeader("operation-header", "operation")

        assertEquals(listOf("msgbatch_123", "msgbatch_122"), page.batches.map { it.batchId })
        val first = page.batches[0].status
        assertEquals(BatchStatus.State.Pending, first.state)
        assertEquals("in_progress", first.rawStatus)
        assertEquals(3, first.requestCounts!!.total)
        assertEquals(2, first.requestCounts!!.pending)
        assertEquals(1, first.requestCounts!!.completed)
        assertEquals(0, first.requestCounts!!.failed)
        assertEquals(BatchStatus.State.Completed, page.batches[1].status.state)
        assertEquals("ended", page.batches[1].status.rawStatus)
        assertEquals("msgbatch_122", page.nextCursor)
    }

    @Test
    fun `the next cursor is omitted on the last page`() = runTest {
        val server = TestServer(TestServer.json(listJson(hasMore = false, lastId = null)))

        val page = assertNotNull(model(server).doListBatches(BatchListOptions()))

        assertTrue(page.batches.isEmpty())
        assertNull(page.nextCursor)
        assertTrue(server.request().query.isEmpty(), server.request().url)
    }

    @Test
    fun `input transformations stored on a batch result reach the finish metadata`() = runTest {
        val dropped = """{"custom_id":"dropped","result":{"type":"succeeded","message":""" +
            """{"id":"msg_3","type":"message","role":"assistant","model":"claude-fable-5-1",""" +
            """"content":[{"type":"text","text":"Continued."}],"stop_reason":"end_turn",""" +
            """"input_transformations":[{"type":"thinking_block_dropped","path":"messages.1.content.0","reason":"prefix_mismatch"}],""" +
            """"usage":{"input_tokens":5,"output_tokens":7}}}}"""
        val server = TestServer(
            TestServer.json(batchJson("ended", resultsUrl = "https://api.anthropic.com/v1/r")),
            TestServer.bytes(dropped.encodeToByteArray(), "application/x-jsonl"),
        )

        val items = model(server).doGetBatchResults(BatchOperationOptions("msgbatch_123")).toList()
        val result = assertIs<BatchItemResult.Succeeded>(items.single()).result

        assertEquals(
            parseJsonObject("""{"t":[{"type":"thinking_block_dropped","path":"messages.1.content.0","reason":"prefix_mismatch"}]}""")["t"],
            result.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get("inputTransformations"),
        )
    }
}
