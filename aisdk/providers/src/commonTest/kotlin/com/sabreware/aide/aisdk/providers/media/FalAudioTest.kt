package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.fal.FAL_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.fal.FalProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * fal speech and transcription, ported from the reference's own two test files.
 *
 * The transcription half runs against `fal/src/__fixtures__/fal-transcription-queue.json` and
 * `fal-transcription-result.json`, copied byte for byte. Note the result fixture's language array is
 * spelled `languages` while the wire the model reads is `inferred_languages` — the reference's own
 * schema reads the latter, so against its own fixture the language comes out null. That mismatch is
 * pinned here as-is rather than "fixed", because inventing the field the fixture forgot is exactly the
 * class of fabrication recorded fixtures exist to prevent.
 */
class FalAudioTest {

    private fun provider(server: TestServer) = FalProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
    )

    // --- Speech --------------------------------------------------------------------------------------

    private fun speechServer() = TestServer(
        TestServer.json(
            """{"audio":{"url":"https://fal.media/files/test.mp3"},"duration_ms":1234,"request_id":"req-1"}""",
        ),
        TestServer.bytes(ByteArray(100), "audio/mp3"),
    )

    @Test
    fun `speech posts to the sync host with url output and fetches the clip`() = runTest {
        val server = speechServer()

        val result = provider(server).speechModel("fal-ai/minimax/speech-02-hd")
            .doGenerate(SpeechCallOptions(text = "Hello from the AI SDK!"))

        val submit = server.request()
        // The SYNC host, with the full model path — speech is not a queue protocol.
        assertEquals("fal-ai/minimax/speech-02-hd", submit.path)
        assertEquals("Hello from the AI SDK!", submit.bodyJson()["text"].string())
        assertEquals("url", submit.bodyJson()["output_format"].string())
        assertEquals("Key test-api-key", submit.header("Authorization"))
        assertEquals(100, (result.audio as BinaryData.Bytes).value.size)
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `the audio fetch carries no api key — the file host is not the key's origin`() = runTest {
        val server = speechServer()

        provider(server).speechModel("fal-ai/minimax/speech-02-hd")
            .doGenerate(SpeechCallOptions(text = "hi"))

        val download = server.request(1)
        assertEquals("https://fal.media/files/test.mp3", download.url)
        assertNull(download.headers["Authorization"], "the key must not travel to fal.media")
    }

    @Test
    fun `voice and speed ride the body and vendor options pass through verbatim`() = runTest {
        val server = speechServer()

        provider(server).speechModel("fal-ai/minimax/speech-02-hd").doGenerate(
            SpeechCallOptions(
                text = "hi",
                voice = "Wise_Woman",
                speed = 1.2,
                providerOptions = mapOf(
                    FAL_PROVIDER_ID to buildJsonObject {
                        put("language_boost", "English")
                    },
                ),
            ),
        )

        val body = server.request().bodyJson()
        assertEquals("Wise_Woman", body["voice"].string())
        assertEquals(1.2, body["speed"].double())
        assertEquals("English", body["language_boost"].string())
    }

    @Test
    fun `language has no field and says so, and a non-url format is clamped with a warning`() = runTest {
        val server = speechServer()

        val result = provider(server).speechModel("fal-ai/minimax/speech-02-hd").doGenerate(
            SpeechCallOptions(text = "hi", language = "es", outputFormat = "hex"),
        )

        assertEquals("url", server.request().bodyJson()["output_format"].string())
        assertTrue(result.warnings.filterIsInstance<Warning.Unsupported>().any { it.feature == "language" })
        assertTrue(result.warnings.filterIsInstance<Warning.Unsupported>().any { it.feature == "outputFormat" })
    }

    @Test
    fun `duration and request id survive into the metadata`() = runTest {
        val server = speechServer()

        val result = provider(server).speechModel("fal-ai/minimax/speech-02-hd")
            .doGenerate(SpeechCallOptions(text = "hi"))

        val metadata = result.providerMetadata?.forProvider(FAL_PROVIDER_ID)
        assertEquals(1234, metadata?.get("durationMs").int())
        assertEquals("req-1", metadata?.get("requestId").string())
    }

    // --- Transcription -------------------------------------------------------------------------------

    private fun transcriptionServer() = TestServer(
        TestServer.json(FalAudioFixtures.TRANSCRIPTION_QUEUE),
        TestServer.error(400, """{"detail": "Request is still in progress"}"""),
        TestServer.json(FalAudioFixtures.TRANSCRIPTION_RESULT),
    )

    @Test
    fun `transcription submits a data uri to the queue and polls through the in-progress error`() = runTest {
        val server = transcriptionServer()

        val result = provider(server).transcriptionModel("wizper").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Bytes(byteArrayOf(1, 2, 3)),
                mediaType = "audio/mp3",
            ),
        )

        val submit = server.request()
        assertEquals("fal-ai/wizper", submit.path)
        assertEquals("transcribe", submit.bodyJson()["task"].string())
        assertEquals("word", submit.bodyJson()["chunk_level"].string())
        assertTrue(submit.bodyJson()["audio_url"].string()!!.startsWith("data:audio/mp3;base64,"))
        // Poll: the queue's request URL, twice — once answered "still in progress" AS AN ERROR, once done.
        assertEquals("fal-ai/wizper/requests/test-id", server.request(1).path)
        assertEquals("fal-ai/wizper/requests/test-id", server.request(2).path)

        assertEquals("Hello from the Versal AISDK.", result.text)
        assertEquals(1, result.segments.size)
        assertEquals(0.0, result.segments[0].startSecond)
        assertEquals(2.508, result.segments[0].endSecond)
        assertEquals(2.508, result.durationInSeconds)
        // The fixture spells its language array `languages`; the wire field is `inferred_languages`.
        assertNull(result.language)
    }

    @Test
    fun `documented options are renamed and a fal-ai prefix does not double up`() = runTest {
        val server = transcriptionServer()

        provider(server).transcriptionModel("fal-ai/whisper").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Base64("AQID"),
                mediaType = "audio/wav",
                providerOptions = mapOf(
                    FAL_PROVIDER_ID to buildJsonObject {
                        put("batchSize", 32)
                        put("numSpeakers", 2)
                        put("chunkLevel", "segment")
                        put("diarize", false)
                        put("language", "en")
                    },
                ),
            ),
        )

        val body = server.request().bodyJson()
        assertEquals("fal-ai/whisper", server.request().path)
        assertEquals(32, body["batch_size"].int())
        assertEquals(2, body["num_speakers"].int())
        assertEquals("segment", body["chunk_level"].string())
        assertEquals(false, body["diarize"].bool())
        assertEquals("en", body["language"].string())
        assertEquals("data:audio/wav;base64,AQID", body["audio_url"].string())
    }
}

/** The reference's recorded fixtures, copied byte for byte. */
internal object FalAudioFixtures {

    const val TRANSCRIPTION_QUEUE: String = """{
  "status": "IN_QUEUE",
  "request_id": "test-id",
  "response_url": "https://queue.fal.run/fal-ai/wizper/requests/test-id/result",
  "status_url": "https://queue.fal.run/fal-ai/wizper/requests/test-id",
  "cancel_url": "https://queue.fal.run/fal-ai/wizper/requests/test-id/cancel",
  "logs": null,
  "metrics": {},
  "queue_position": 4
}"""

    const val TRANSCRIPTION_RESULT: String = """{
  "text": "Hello from the Versal AISDK.",
  "chunks": [
    {
      "timestamp": [0, 2.508],
      "text": "Hello from the Versal AISDK."
    }
  ],
  "languages": ["en"]
}"""
}
