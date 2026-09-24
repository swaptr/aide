package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.providers.gladia.GLADIA_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.gladia.GladiaProvider
import com.sabreware.aide.aisdk.providers.revai.REVAI_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.revai.RevAiProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gladia and Rev AI, ported from the reference's own test files and run against the JSON those files
 * read off disk.
 *
 * Both are three-call flows — upload, submit, poll — which is the shape the shared harness's
 * call-indexed responses exist for: reading `lastRequest()` here reads a poll, which carries no body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GladiaRevAiConformanceTest {

    // --- Gladia -------------------------------------------------------------------------------------

    private fun TestScope.gladia(server: TestServer) = GladiaProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { currentTime },
    )

    private fun gladiaServer() = TestServer(
        TestServer.json(GladiaFixtures.UPLOAD),
        TestServer.json(GladiaFixtures.INITIATE),
        TestServer.json(GladiaFixtures.RESULT),
    )

    private fun transcribe() = TranscriptionCallOptions(
        audio = BinaryData.Bytes("AUDIO".encodeToByteArray()),
        mediaType = "audio/wav",
    )

    private val uploadedUrl = "https://api.gladia.io/file/7403f025-be30-4335-ae29-20131e0adbd6"

    @Test
    fun `the job references the URL the upload returned, not the bytes`() = runTest {
        val server = gladiaServer()

        gladia(server).transcriptionModel("default").doGenerate(transcribe())

        // Three calls: the audio is uploaded, the job names the returned URL, and the result is fetched
        // from the address the job itself chose.
        assertEquals("v2/upload", server.request(0).path)
        val submit = server.request(1)
        assertEquals("v2/pre-recorded", submit.path)
        assertEquals(uploadedUrl, submit.bodyJson()["audio_url"].string())
        assertEquals(
            "https://api.gladia.io/v2/pre-recorded/7b0137fd-5fd6-4d08-b9bf-4cdf9876797d",
            server.request(2).url,
        )
    }

    @Test
    fun `Gladia authenticates with its own header on every call in the flow`() = runTest {
        val server = gladiaServer()

        gladia(server).transcriptionModel("default").doGenerate(
            transcribe().copy(headers = mapOf("Custom-Request-Header" to "request-header-value")),
        )

        for (index in 0..2) {
            server.request(index).assertHeader("x-gladia-key", "test-api-key")
            server.request(index).assertNoHeader("Authorization")
            server.request(index).assertHeader("Custom-Request-Header", "request-header-value")
        }
        server.request(0).assertMultipartFile("audio", contentType = "audio/wav")
        server.request(1).assertHeader("Content-Type", "application/json")
    }

    @Test
    fun `the API key is withheld when the job names a result URL on a foreign origin`() = runTest {
        val server = TestServer(
            TestServer.json(GladiaFixtures.UPLOAD),
            TestServer.json(
                """{"id":"7b0137fd","result_url":"https://cdn.evil.example/v2/pre-recorded/result"}""",
            ),
            TestServer.json(GladiaFixtures.RESULT),
        )

        val result = gladia(server).transcriptionModel("default").doGenerate(transcribe())

        // The poll URL comes out of the vendor's own response, so attaching the key unconditionally
        // hands it to any host that response names — a credential-exfiltration primitive rather than a
        // transcription bug.
        val poll = server.request(2)
        assertEquals("https://cdn.evil.example/v2/pre-recorded/result", poll.url)
        poll.assertNoHeader("x-gladia-key")
        // Still transcribes: the guard drops the credential, it does not refuse the URL.
        assertTrue(result.text.startsWith("Galileo was an American robotic space program"))
    }

    @Test
    fun `the recorded result maps to text, utterances, language and duration`() = runTest {
        val server = gladiaServer()

        val result = gladia(server).transcriptionModel("default").doGenerate(transcribe())

        assertEquals(
            "Galileo was an American robotic space program that studied the planet Jupiter and " +
                "its moons, as well as several other solar system bodies. Named after the Italian " +
                "astronomer Galileo Galilei, the Galileo spacecraft consisted of an orbiter and an " +
                "atmospheric entry probe. It was delivered into Earth orbit on October 18, 1989, by " +
                "Space Shuttle Atlantis on the STS-34 mission. and arrived at Jupiter on December 7, " +
                "1995, after gravity-assist flybys of Venus and Earth, and became the first " +
                "spacecraft to orbit Jupiter.",
            result.text,
        )
        assertEquals("en", result.language)
        assertEquals(36.74, result.durationInSeconds)
        assertEquals(11, result.segments.size)
        assertEquals(
            TranscriptionResult.Segment(
                "Galileo was an American robotic space program that studied the planet Jupiter and " +
                    "its moons,",
                0.14,
                5.341,
            ),
            result.segments.first(),
        )
        assertEquals("default", result.response.modelId)
    }

    @Test
    fun `everything the contract cannot hold survives under the vendor's namespace`() = runTest {
        val server = gladiaServer()

        val result = gladia(server).transcriptionModel("default").doGenerate(transcribe())

        // Summaries, translations, moderation, named entities and the job's own timings all live in the
        // payload and have nowhere to go in the contract.
        val metadata = assertNotNull(result.providerMetadata?.get(GLADIA_PROVIDER_ID))
        assertEquals("done", metadata["status"].string())
        assertEquals("G-7b0137fd", metadata["request_id"].string())
        assertNotNull(metadata["file"])
        assertNotNull(metadata["request_params"])
    }

    @Test
    fun `a Gladia option is renamed on the way into the job body`() = runTest {
        val server = gladiaServer()

        gladia(server).transcriptionModel("default").doGenerate(
            transcribe().copy(
                providerOptions = mapOf(
                    GLADIA_PROVIDER_ID to buildJsonObject {
                        put("detectLanguage", true)
                        put("contextPrompt", "a space probe")
                        put(
                            "diarizationConfig",
                            buildJsonObject { put("numberOfSpeakers", 2) },
                        )
                        put("sentences", true)
                    },
                ),
            ),
        )

        val body = server.request(1).bodyJson()
        assertEquals(true, body["detect_language"].toString().toBoolean())
        assertEquals("a space probe", body["context_prompt"].string())
        // The rename reaches one level into the DOCUMENTED config objects and no further: a blanket
        // camel-to-snake pass would also rewrite `custom_metadata` and a caller's own spelling
        // dictionary keys, silently changing what they asked for.
        assertEquals(
            buildJsonObject { put("number_of_speakers", 2) },
            body["diarization_config"],
        )
        // Already Gladia's own spelling, so untouched.
        assertEquals(true, body["sentences"].toString().toBoolean())
        assertEquals(uploadedUrl, body["audio_url"].string())
    }

    // --- Rev AI -------------------------------------------------------------------------------------

    private fun TestScope.revAi(server: TestServer) = RevAiProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { currentTime },
    )

    private fun revAiServer() = TestServer(
        TestServer.json(RevAiFixtures.SUBMIT),
        TestServer.json(RevAiFixtures.STATUS),
        TestServer.json(RevAiFixtures.TRANSCRIPT),
    )

    @Test
    fun `the job config names the transcriber, or the model is billed at the account default`() =
        runTest {
            val server = revAiServer()

            revAi(server).transcriptionModel("machine").doGenerate(transcribe())

            // Everything about the job other than the media rides in one `config` part. Omitting it
            // means `machine` / `human` / `low_cost` is a no-op, transcribed and billed at whatever the
            // account happens to default to — which is a bill, not an error.
            val submit = server.request(0)
            assertEquals("speechtotext/v1/jobs", submit.path)
            submit.assertMultipartField("config", """{"transcriber":"machine"}""")
            submit.assertMultipartFile("media", contentType = "audio/wav")
            submit.assertHeader("Authorization", "Bearer test-api-key")
        }

    @Test
    fun `a Rev AI option joins the config rather than becoming a part of its own`() = runTest {
        val server = revAiServer()

        revAi(server).transcriptionModel("machine").doGenerate(
            transcribe().copy(
                providerOptions = mapOf(
                    REVAI_PROVIDER_ID to buildJsonObject {
                        put("language", "en")
                        put("skip_diarization", true)
                    },
                ),
            ),
        )

        // Rev AI's own option names are already its wire names, so they go out verbatim.
        val config = parseJsonObject(assertNotNull(server.request(0).multipart["config"]))
        assertEquals(
            buildJsonObject {
                put("transcriber", "machine")
                put("language", "en")
                put("skip_diarization", true)
            },
            config,
        )
    }

    @Test
    fun `the transcript fetch sends the vendor Accept header, which is load-bearing`() = runTest {
        val server = revAiServer()

        revAi(server).transcriptionModel("machine").doGenerate(transcribe())

        // Without it this endpoint answers in another format entirely, and the parse fails on a job
        // that transcribed perfectly.
        val transcript = server.request(2)
        assertEquals("speechtotext/v1/jobs/test-id/transcript", transcript.path)
        transcript.assertHeader("Accept", RevAiProvider.TRANSCRIPT_ACCEPT)
        assertEquals("speechtotext/v1/jobs/test-id", server.request(1).path)
    }

    @Test
    fun `elements are reassembled into text, with segments closed by the timed ones`() = runTest {
        val server = revAiServer()

        val result = revAi(server).transcriptionModel("machine").doGenerate(transcribe())

        assertEquals("Hello from the Sal, A-I-S-D-K.", result.text)
        // Punctuation elements carry no timings and join the word before them; a timed element closes a
        // segment. One segment per monologue — what this replaced — fused a whole speaker turn into an
        // un-seekable block.
        assertEquals(
            TranscriptionResult.Segment("Hello", 0.075, 0.425),
            result.segments.first(),
        )
        assertTrue(result.segments.size > 1, "segments: ${result.segments}")
        // The language is named on the SUBMIT response; the transcript never mentions it, which is why
        // the contract field used to be permanently null.
        assertEquals("en", result.language)
        assertEquals("machine", result.response.modelId)
    }

    @Test
    fun `the raw transcript is preserved, confidences and speakers included`() = runTest {
        val server = revAiServer()

        val result = revAi(server).transcriptionModel("machine").doGenerate(transcribe())

        val body = parseJsonObject(assertNotNull(result.response.body))
        // Per-element confidence and the monologue's speaker have nowhere to go in `segments`.
        assertNotNull(body["monologues"])
    }
}
