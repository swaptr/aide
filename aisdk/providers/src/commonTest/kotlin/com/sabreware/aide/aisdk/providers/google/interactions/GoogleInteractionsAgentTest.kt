package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.JobTimeoutError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * The background paths an agent call takes after its POST: polled to a terminal status, streamed from
 * its own GET with a reconnect, replayed when the POST was already terminal — and cancelled on the
 * server when the caller gives up on it.
 *
 * `TestServer` answers by call index and repeats its last answer, so a poll of unknown length and a
 * reconnect are both expressible as a short list of responses.
 */
class GoogleInteractionsAgentTest {

    private val agent = GoogleInteractionsTarget.Agent(TEST_AGENT)
    private val background = CallOptions(TEST_PROMPT, providerOptions = googleOptions { put("background", true) })
    private val pollUrl = "$INTERACTIONS_URL/v1_poll-test"

    private fun completedBody(text: String) =
        """{"id":"v1_poll-test","status":"completed","steps":[{"type":"model_output","content":[{"type":"text",""" +
            """"text":"$text"}]}],"usage":{"total_input_tokens":5,"total_output_tokens":3,"total_tokens":8}}"""

    private fun event(json: String) = "data: $json\n\n"

    @Test
    fun `doGenerate polls GET until the run is terminal and returns that answer`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress","model":"$TEST_AGENT"}"""),
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
            TestServer.json(completedBody("researched answer")),
        )
        val result = interactionsModel(server, agent).doGenerate(CallOptions(TEST_PROMPT))

        assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)
        assertTrue(result.content.any { it == Content.Text("researched answer", googleMeta("interactionId" to "v1_poll-test")) })
        assertEquals(listOf("POST", "GET", "GET"), server.calls.map { it.method })
        assertEquals(pollUrl, server.request(1).url)
        assertEquals(pollUrl, server.request(2).url)
        server.request(1).assertHeader("x-goog-api-key", "test-api-key")
    }

    @Test
    fun `doStream opens GET stream=true and pipes its events through when the POST is not terminal`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
            TestServer.sse(
                event("""{"event_type":"interaction.created","event_id":"evt-1","interaction":{"id":"v1_poll-test","status":"in_progress","model":"$TEST_AGENT"}}"""),
                event("""{"event_type":"step.start","event_id":"evt-2","index":0,"step":{"type":"model_output"}}"""),
                event("""{"event_type":"step.delta","event_id":"evt-3","index":0,"delta":{"type":"text","text":"streamed agent answer"}}"""),
                event("""{"event_type":"step.stop","event_id":"evt-4","index":0}"""),
                event(
                    """{"event_type":"interaction.completed","event_id":"evt-5","interaction":{"id":"v1_poll-test","status":"completed",""" +
                        """"usage":{"total_input_tokens":5,"total_output_tokens":3,"total_tokens":8}}}""",
                ),
            ),
        )
        val parts = interactionsModel(server, agent).doStream(background).stream.toList()

        val types = parts.map { it::class.simpleName }
        assertEquals(
            listOf("StreamStart", "ResponseMetadataPart", "TextStart", "TextDelta", "TextEnd", "Finish"),
            types,
        )
        assertEquals("streamed agent answer", parts.filterIsInstance<StreamPart.TextDelta>().single().delta)

        assertEquals(listOf("POST", "GET"), server.calls.map { it.method })
        val body = server.request(0).bodyJson()
        assertEquals(TEST_AGENT, body["agent"].string())
        assertEquals("true", body["background"].string())
        assertNull(body["stream"])
        assertNull(body["generation_config"])
        assertEquals("true", server.request(1).query["stream"])
        assertNull(server.request(1).query["last_event_id"])
        server.request(1).assertHeader("Accept", "text/event-stream")
        assertTrue(server.request(1).url.startsWith(pollUrl))
    }

    @Test
    fun `a dropped GET stream reconnects from the last event id`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
            TestServer.sse(
                event("""{"event_type":"interaction.created","event_id":"evt-1","interaction":{"id":"v1_poll-test","status":"in_progress","model":"$TEST_AGENT"}}"""),
                event("""{"event_type":"step.start","event_id":"evt-2","index":0,"step":{"type":"model_output"}}"""),
                event("""{"event_type":"step.delta","event_id":"evt-3","index":0,"delta":{"type":"text","text":"first half "}}"""),
            ),
            TestServer.sse(
                event("""{"event_type":"step.delta","event_id":"evt-4","index":0,"delta":{"type":"text","text":"second half"}}"""),
                event("""{"event_type":"step.stop","event_id":"evt-5","index":0}"""),
                event(
                    """{"event_type":"interaction.completed","event_id":"evt-6","interaction":{"id":"v1_poll-test","status":"completed",""" +
                        """"usage":{"total_input_tokens":5,"total_output_tokens":3,"total_tokens":8}}}""",
                ),
            ),
        )
        val parts = interactionsModel(server, agent).doStream(background).stream.toList()

        assertEquals(listOf("first half ", "second half"), parts.filterIsInstance<StreamPart.TextDelta>().map { it.delta })
        assertEquals(listOf("POST", "GET", "GET"), server.calls.map { it.method })
        assertNull(server.request(1).query["last_event_id"])
        assertEquals("evt-3", server.request(2).query["last_event_id"])
        assertEquals("true", server.request(2).query["stream"])
    }

    @Test
    fun `a POST that is already terminal is replayed as a stream with no GET at all`() = runTest {
        val server = TestServer(TestServer.json(completedBody("instant answer")))
        val parts = interactionsModel(server, agent).doStream(background).stream.toList()

        assertEquals(
            listOf("StreamStart", "ResponseMetadataPart", "TextStart", "TextDelta", "TextEnd", "Finish"),
            parts.map { it::class.simpleName },
        )
        assertEquals("instant answer", parts.filterIsInstance<StreamPart.TextDelta>().single().delta)
        assertEquals("v1_poll-test:0", parts.filterIsInstance<StreamPart.TextStart>().single().id)
        val finish = parts.filterIsInstance<StreamPart.Finish>().single()
        assertEquals(FinishReason(FinishReason.Unified.Stop, "completed"), finish.finishReason)
        assertEquals("v1_poll-test", finish.providerMetadata?.get("google")?.get("interactionId").string())
        assertEquals(1, server.callCount)
        assertEquals("POST", server.request().method)
    }

    @Test
    fun `the replayed stream carries generated images and videos as file parts`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"v1_poll-test","status":"completed","steps":[{"type":"model_output","content":[""" +
                    """{"type":"text","text":"here they are"},""" +
                    """{"type":"image","data":"aGVsbG8=","mime_type":"image/png"},""" +
                    """{"type":"image","uri":"https://example.com/img.png","mime_type":"image/png"},""" +
                    """{"type":"video","data":"AAAAIGZ0eXBpc29t","mime_type":"video/mp4"},""" +
                    """{"type":"video","uri":"https://example.com/clip.mp4","mime_type":"video/mp4"}]}],""" +
                    """"usage":{"total_input_tokens":5,"total_output_tokens":3,"total_tokens":8}}""",
            ),
        )
        val parts = interactionsModel(server, agent).doStream(background).stream.toList()
        val stamp = googleMeta("interactionId" to "v1_poll-test")
        assertEquals(
            listOf(
                Content.File("image/png", FileData.Bytes("hello".encodeToByteArray()), stamp),
                Content.File("image/png", FileData.Url("https://example.com/img.png"), stamp),
                Content.File("video/mp4", FileData.Bytes(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d)), stamp),
                Content.File("video/mp4", FileData.Url("https://example.com/clip.mp4"), stamp),
            ),
            parts.filterIsInstance<StreamPart.FilePart>().map { it.file },
        )
    }

    @Test
    fun `the replayed stream carries the request's warnings on its start part`() = runTest {
        val server = TestServer(TestServer.json(GoogleInteractionsFixtures.BASIC_JSON))
        val parts = interactionsModel(server, agent).doStream(background.copy(temperature = 0.5)).stream.toList()
        val start = parts.filterIsInstance<StreamPart.StreamStart>().single()
        assertTrue(start.warnings.any { it is Warning.Other && it.message.contains("temperature") })
        assertEquals(FinishReason.Unified.Stop, parts.filterIsInstance<StreamPart.Finish>().single().finishReason.unified)
    }

    // --- giving up -------------------------------------------------------------------------------

    @Test
    fun `abandoning the GET stream cancels the run on the server`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
            TestServer.sse(
                event("""{"event_type":"interaction.created","event_id":"evt-1","interaction":{"id":"v1_poll-test","status":"in_progress"}}"""),
                event("""{"event_type":"step.start","event_id":"evt-2","index":0,"step":{"type":"model_output"}}"""),
                event("""{"event_type":"step.delta","event_id":"evt-3","index":0,"delta":{"type":"text","text":"partial"}}"""),
            ),
        )
        // StreamStart, ResponseMetadataPart, TextStart, TextDelta — then the consumer walks away.
        val taken = interactionsModel(server, agent).doStream(background).stream.take(4).toList()
        assertEquals("partial", (taken.last() as StreamPart.TextDelta).delta)

        val cancel = server.calls.last()
        assertEquals("POST", cancel.method)
        assertEquals("$pollUrl/cancel", cancel.url)
        assertEquals("{}", cancel.bodyText)
    }

    @Test
    fun `a stream that completes normally is not cancelled`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
            TestServer.sse(
                event("""{"event_type":"interaction.completed","event_id":"evt-1","interaction":{"id":"v1_poll-test","status":"completed"}}"""),
            ),
        )
        interactionsModel(server, agent).doStream(background).stream.toList()
        assertTrue(server.calls.none { it.url.endsWith("/cancel") })
    }

    @Test
    fun `a GET stream that keeps closing empty gives up after the reconnect budget`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
            TestServer.sse(),
        )
        val model = interactionsModel(server, agent, reconnect = GoogleInteractionsReconnectPolicy(maxRetries = 2, retryDelayMillis = 1))
        val error = assertFailsWith<InvalidResponseDataError> { model.doStream(background).stream.toList() }
        assertTrue(error.message!!.contains("closed without producing any events"))
        assertEquals(listOf("POST", "GET", "GET"), server.calls.map { it.method })
    }

    @Test
    fun `cancelling a poll cancels the run on the server`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
        )
        val job = launch { interactionsModel(server, agent).doGenerate(CallOptions(TEST_PROMPT)) }
        // The mock engine answers off the test dispatcher, so wait in real time until the first poll has
        // been issued: a cancellation that lands during the POST has no id to cancel, by design.
        while (server.callCount < 2) withContext(Dispatchers.Default) { delay(5) }
        job.cancelAndJoin()

        val cancel = server.calls.last()
        assertEquals("POST", cancel.method)
        assertEquals("$pollUrl/cancel", cancel.url)
    }

    @Test
    fun `a poll that outlives its budget fails with the poller's timeout`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
        )
        var clock = 0L
        val model = interactionsModel(server, agent, now = { clock += 20L * 60 * 1_000; clock })
        assertFailsWith<JobTimeoutError> { model.doGenerate(CallOptions(TEST_PROMPT)) }
    }

    @Test
    fun `a background POST without an id cannot be polled or streamed`() = runTest {
        val noId = TestServer(TestServer.json("""{"status":"in_progress"}"""))
        assertFailsWith<InvalidResponseDataError> { interactionsModel(noId, agent).doGenerate(CallOptions(TEST_PROMPT)) }
        val noIdStream = TestServer(TestServer.json("""{"status":"in_progress"}"""))
        assertFailsWith<InvalidResponseDataError> { interactionsModel(noIdStream, agent).doStream(background).stream.toList() }
    }

    @Test
    fun `a model call in the background is polled too, and its response modalities still ride the body`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"v1_poll-test","status":"in_progress"}"""),
            TestServer.json(completedBody("done")),
        )
        val result = interactionsModel(server).doGenerate(
            background.copy(providerOptions = googleOptions {
                put("background", true)
                put("responseModalities", buildJsonArray { add("text") })
            }),
        )
        assertEquals(FinishReason.Unified.Stop, result.finishReason.unified)
        assertEquals(listOf("POST", "GET"), server.calls.map { it.method })
        assertEquals(TEST_MODEL, server.request(0).bodyJson()["model"].string())
    }
}
