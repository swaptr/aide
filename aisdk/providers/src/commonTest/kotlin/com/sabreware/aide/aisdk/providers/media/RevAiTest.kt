package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.providers.revai.REVAI_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.revai.RevAiProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.PollPolicy
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Rev AI, whose transcript is shaped unlike anyone else's — monologues of word-and-punctuation elements
 * rather than sentence segments.
 */
class RevAiTest {

    private fun TestScope.provider(server: TestServer) = RevAiProvider(
        client = HttpClient(server.engine()),
        apiKey = "k",
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { currentTime },
    )

    /** Two monologues, so the boundary between speaker turns is visible in the assembled text. */
    private val transcript = """
        {"monologues":[
          {"speaker":0,"elements":[
            {"type":"text","value":"hello","ts":0.0,"end_ts":0.5},
            {"type":"punct","value":" "},
            {"type":"text","value":"there","ts":0.5,"end_ts":1.2},
            {"type":"punct","value":"."}]},
          {"speaker":1,"elements":[
            {"type":"text","value":"hi","ts":1.5,"end_ts":2.0}]}]}
    """.trimIndent()

    @Test
    fun `elements are reassembled into text with timings from the words only`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"j1","status":"in_progress","language":"en"}"""),
            TestServer.json("""{"status":"in_progress"}"""),
            TestServer.json("""{"status":"transcribed"}"""),
            TestServer.json(transcript),
        )

        val result = provider(server).transcriptionModel("machine")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes("A".encodeToByteArray()), "audio/wav"))

        // Monologues are separate speaker turns and need a separator; joining with nothing — which is
        // what this asserted — fuses the last word of one turn onto the first word of the next.
        assertEquals("hello there. hi", result.text)
        // A timed element CLOSES a segment. One segment per monologue was the thing this replaced, and
        // it fused every speaker turn into a single un-seekable block. Punctuation carries no
        // timestamps, so it joins the word before it rather than becoming a segment of its own.
        assertEquals(3, result.segments.size)
        assertEquals(listOf("hello", "there", "hi"), result.segments.map { it.text })
        assertEquals(0.0, result.segments[0].startSecond)
        assertEquals(1.2, result.segments[1].endSecond)
        assertEquals(2.0, result.durationInSeconds)
        // The submit response is where Rev AI names the language; the transcript never mentions it,
        // which is why the contract field used to be permanently null.
        assertEquals("en", result.language)
    }

    @Test
    fun `the job config names the transcriber, or the model is billed at the account default`() =
        runTest {
            val server = TestServer(
                TestServer.json("""{"id":"j1","status":"transcribed"}"""),
                TestServer.json("""{"status":"transcribed"}"""),
                TestServer.json(transcript),
            )

            provider(server).transcriptionModel("low_cost").doGenerate(
                TranscriptionCallOptions(
                    audio = BinaryData.Bytes(ByteArray(1)),
                    mediaType = "audio/wav",
                    providerOptions = mapOf(
                        REVAI_PROVIDER_ID to buildJsonObject {
                            put("skip_diarization", JsonPrimitive(true))
                        },
                    ),
                ),
            )

            // Everything about the job other than the media rides in this one part. Without it the
            // model never reaches the vendor at all: machine/human/low_cost becomes a no-op, silently
            // transcribed and billed at whatever the account defaults to.
            server.request(0).assertMultipartField(
                "config",
                """{"transcriber":"low_cost","skip_diarization":true}""",
            )
        }

    @Test
    fun `the transcript fetch sends the vendor Accept header`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"j1"}"""),
            TestServer.json("""{"status":"transcribed"}"""),
            TestServer.json(transcript),
        )

        provider(server).transcriptionModel("machine")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))

        // Load-bearing: without it this endpoint answers in another format and the parse fails on a job
        // that transcribed perfectly.
        val fetch = server.lastRequest()
        fetch.assertHeader("Accept", RevAiProvider.TRANSCRIPT_ACCEPT)
        assertEquals("speechtotext/v1/jobs/j1/transcript", fetch.path)
    }

    @Test
    fun `a failed job surfaces the vendor's failure detail`() = runTest {
        val server = TestServer(
            TestServer.json("""{"id":"j1"}"""),
            TestServer.json("""{"status":"failed","failure_detail":"audio too short"}"""),
        )

        val error = assertFailsWith<JobFailedError> {
            provider(server).transcriptionModel("machine")
                .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))
        }
        assertTrue(error.message!!.contains("audio too short"), error.message!!)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private val TestScope.currentTime: Long get() = testScheduler.currentTime
