package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.providers.fishaudio.FISH_AUDIO_OPTIONS_KEY
import com.sabreware.aide.aisdk.providers.fishaudio.FishAudioProvider
import com.sabreware.aide.aisdk.providers.gladia.GLADIA_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.gladia.GladiaProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarningAbout
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.PollPolicy
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fish Audio and Gladia, both read from the vendored reference. */
class FishGladiaTest {

    // --- Fish Audio -----------------------------------------------------------------------------

    private fun fishAudio(server: TestServer) = FishAudioProvider(
        server.http(),
        "k",
        FishAudioProvider.DEFAULT_BASE_URL,
        emptyMap(),
    )

    private fun audio() = TestServer(TestServer.bytes("AUDIO".encodeToByteArray(), "audio/mpeg"))

    @Test
    fun `Fish Audio omits the voice when unset because it is a cloned-voice handle`() = runTest {
        val server = audio()

        val result = fishAudio(server).speechModel("s1").doGenerate(SpeechCallOptions(text = "hi"))

        // reference_id is a handle for a cloned voice, not a name from a catalogue, so there is no
        // default worth inventing.
        val call = server.request()
        call.assertBodyKeys("text", "format")
        assertEquals("AUDIO", (result.audio as BinaryData.Bytes).value.decodeToString())
        assertEquals("v1/tts", call.path)
        // The model is a header here, not a body field.
        call.assertHeader("model", "s1")
    }

    @Test
    fun `a supplied voice becomes reference_id`() = runTest {
        val server = audio()

        fishAudio(server).speechModel("s1")
            .doGenerate(SpeechCallOptions(text = "hi", voice = "clone-42"))

        server.request().assertBodyJson { assertEquals("clone-42", it["reference_id"].string()) }
    }

    @Test
    fun `an unsupported Fish Audio format is clamped with a warning`() = runTest {
        val server = audio()

        val result = fishAudio(server).speechModel("s1")
            .doGenerate(SpeechCallOptions(text = "hi", outputFormat = "flac"))

        server.request().assertBodyJson { assertEquals("mp3", it["format"].string()) }
        result.warnings.assertUnsupported(
            feature = "outputFormat",
            details = "Fish Audio does not support the output format \"flac\". Falling back to mp3. " +
                "Supported formats are wav, pcm, mp3, opus.",
        )
    }

    @Test
    fun `Fish Audio speed is prosody, and one outside the range is dropped with a warning`() = runTest {
        val server = audio()

        val inRange = fishAudio(server).speechModel("s1")
            .doGenerate(SpeechCallOptions(text = "hi", speed = 1.5))
        server.request().assertBodyJson { assertEquals(1.5, it.obj("prosody")!!["speed"].double()) }
        inRange.warnings.assertNoWarningAbout("speed")

        val outOfRange = fishAudio(server).speechModel("s1")
            .doGenerate(SpeechCallOptions(text = "hi", speed = 4.0))
        server.request(1).assertBodyMissing("prosody")
        outOfRange.warnings.assertUnsupported(
            feature = "speed",
            details = "Fish Audio speed must be between 0.5 and 2. The speed option was ignored.",
        )
    }

    private val fishTranscript = """
        {"text":"hello there","duration":2.5,"language":"English","language_code":"en",
         "segments":[{"text":"hello","start":0.0,"end":1.0},
                     {"text":"there","start":1.0,"end":2.5}]}
    """.trimIndent()

    @Test
    fun `Fish Audio asks for timestamps, because its own default returns none`() = runTest {
        val server = TestServer(TestServer.json(fishTranscript))

        val result = fishAudio(server).transcriptionModel("asr")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/wav"))

        // `ignore_timestamps` defaults to TRUE at the vendor, so omitting the field — which is what this
        // did until the default was read — leaves `segments` permanently empty: a contract field that
        // looks supported and returns nothing on every call.
        val call = server.request()
        call.assertMultipartField("ignore_timestamps", "false")
        assertEquals("v1/asr", call.path)
        assertEquals("hello there", result.text)
        assertEquals(2, result.segments.size)
        assertEquals(1.0, result.segments[0].endSecond)
        // `language_code` is the ISO-639-1 code and reports what was DETECTED; the display name has
        // nowhere to go in the contract and is namespaced rather than dropped.
        assertEquals("en", result.language)
        assertEquals(2.5, result.durationInSeconds)
        assertEquals(
            "English",
            result.providerMetadata?.get(FISH_AUDIO_OPTIONS_KEY)?.get("language").string(),
        )
    }

    @Test
    fun `a caller can trade the timestamps back for the latency`() = runTest {
        val server = TestServer(TestServer.json(fishTranscript))

        fishAudio(server).transcriptionModel("asr").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Bytes(ByteArray(1)),
                mediaType = "audio/wav",
                providerOptions = mapOf(
                    FISH_AUDIO_OPTIONS_KEY to buildJsonObject {
                        put("ignoreTimestamps", JsonPrimitive(true))
                        put("language", JsonPrimitive("en"))
                    },
                ),
            ),
        )

        val call = server.request()
        call.assertMultipartField("ignore_timestamps", "true")
        call.assertMultipartField("language", "en")
    }

    // --- Gladia ---------------------------------------------------------------------------------

    private fun TestScope.gladia(server: TestServer) = GladiaProvider(
        client = HttpClient(server.engine()),
        apiKey = "k",
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { currentTime },
    )

    private val gladiaDone = """
        {"status":"done","result":{
            "metadata":{"audio_duration":3.5},
            "transcription":{"full_transcript":"hello there","languages":["en"],
            "utterances":[{"start":0.0,"end":1.0,"text":"hello"},
                          {"start":1.0,"end":3.5,"text":"there"}]}}}
    """.trimIndent()

    @Test
    fun `Gladia uploads, starts a job, then polls the result_url it was given`() = runTest {
        val server = TestServer(
            TestServer.json("""{"audio_url":"https://cdn.gladia.io/a.wav"}"""),
            TestServer.json("""{"id":"j1","result_url":"https://api.gladia.io/v2/pre-recorded/j1"}"""),
            TestServer.json("""{"status":"queued"}"""),
            TestServer.json("""{"status":"processing"}"""),
            TestServer.json(gladiaDone),
        )

        val result = gladia(server).transcriptionModel("v2")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/wav"))

        assertEquals("v2/upload", server.request(0).path)
        assertEquals("v2/pre-recorded", server.request(1).path)
        // The result URL is the one the job handed back, not one rebuilt from our base.
        assertEquals("v2/pre-recorded/j1", server.request(2).path)
        // Deeply nested; each level is a place a client silently gets an empty transcript.
        assertEquals("hello there", result.text)
        assertEquals("en", result.language)
        assertEquals(3.5, result.durationInSeconds)
        assertEquals(2, result.segments.size)
    }

    @Test
    fun `Gladia authenticates with x-gladia-key, not a bearer`() = runTest {
        val server = TestServer(
            TestServer.json("""{"audio_url":"https://cdn.gladia.io/a"}"""),
            TestServer.json("""{"result_url":"https://api.gladia.io/r"}"""),
            TestServer.json(gladiaDone),
        )

        gladia(server).transcriptionModel("v2")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))

        server.request(0).assertHeader("x-gladia-key", "k")
        server.request(0).assertNoHeader("Authorization")
    }

    @Test
    fun `does not send the API key when the result URL is on a foreign origin`() = runTest {
        val server = TestServer(
            TestServer.json("""{"audio_url":"https://cdn.gladia.io/a"}"""),
            TestServer.json("""{"result_url":"https://attacker.example/r"}"""),
            TestServer.json(gladiaDone),
        )

        val result = gladia(server).transcriptionModel("v2")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))

        // The poll URL comes out of the vendor's own response, so attaching the key to it unconditionally
        // is a credential-exfiltration primitive rather than a transcription bug: any host the response
        // names is handed our API key.
        val poll = server.request(2)
        assertEquals("https://attacker.example/r", poll.url)
        poll.assertNoHeader("x-gladia-key")
        // Still transcribes — the guard drops the credential, it does not refuse a public URL.
        assertEquals("hello there", result.text)
    }

    @Test
    fun `a done job whose result is missing a level fails rather than transcribing to nothing`() =
        runTest {
            val server = TestServer(
                TestServer.json("""{"audio_url":"https://cdn.gladia.io/a"}"""),
                TestServer.json("""{"result_url":"https://api.gladia.io/r"}"""),
                TestServer.json("""{"status":"done","result":{"metadata":{"audio_duration":1.0}}}"""),
            )

            // Gladia's transcript is three levels down, and `?.orEmpty()` at any of them turns a shape
            // the parse does not understand into "" — indistinguishable from a genuinely silent
            // recording, and returned to the caller as a successful transcription of nothing.
            assertFailsWith<NoContentGeneratedError> {
                gladia(server).transcriptionModel("v2")
                    .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))
            }
        }

    @Test
    fun `a Gladia option reaches the job under its wire name`() = runTest {
        val server = TestServer(
            TestServer.json("""{"audio_url":"https://cdn.gladia.io/a"}"""),
            TestServer.json("""{"result_url":"https://api.gladia.io/r"}"""),
            TestServer.json(gladiaDone),
        )

        gladia(server).transcriptionModel("v2").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Bytes(ByteArray(1)),
                mediaType = "audio/wav",
                providerOptions = mapOf(
                    GLADIA_PROVIDER_ID to buildJsonObject {
                        put("diarization", JsonPrimitive(true))
                        put(
                            "diarizationConfig",
                            buildJsonObject { put("numberOfSpeakers", JsonPrimitive(2)) },
                        )
                    },
                ),
            ),
        )

        val job = server.request(1)
        job.assertBodyKeys("diarization", "diarization_config", "audio_url")
        job.assertBodyJson {
            // The rename reaches exactly one level: deeper is caller-keyed data, where a blanket
            // camel-to-snake pass would rewrite a user's own words.
            assertEquals(2, it.obj("diarization_config")!!["number_of_speakers"].string()?.toInt())
            assertNull(it["diarizationConfig"])
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private val TestScope.currentTime: Long get() = testScheduler.currentTime
