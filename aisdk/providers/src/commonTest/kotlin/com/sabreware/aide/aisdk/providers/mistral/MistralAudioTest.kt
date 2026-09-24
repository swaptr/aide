package com.sabreware.aide.aisdk.providers.mistral

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Mistral speech (Voxtral TTS) and native transcription, ported from the reference's two test files.
 *
 * The transcription mapping runs against `mistral/src/__fixtures__/mistral-transcription.json`, copied
 * byte for byte — diarized speaker ids, a null top-level language, and the usage block that carries the
 * audio duration are all details a hand-written fixture would have simplified away.
 */
class MistralAudioTest {

    private fun provider(server: TestServer) = MistralProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
    )

    // --- Speech --------------------------------------------------------------------------------------

    private fun speechServer() = TestServer(TestServer.json("""{"audio_data":"QUJD"}"""))

    @Test
    fun `the voice field is voice_id, and the audio comes back as base64 in JSON`() = runTest {
        val server = speechServer()

        val result = provider(server).speechModel("voxtral-mini-tts-2603").doGenerate(
            SpeechCallOptions(text = "Hello!", voice = "aurelie"),
        )

        val call = server.request()
        assertEquals("v1/audio/speech", call.path)
        assertEquals("Bearer test-api-key", call.header("Authorization"))
        // `voice_id`, not `voice` — the one-field difference that makes the compat path unusable here.
        assertEquals("aurelie", call.bodyJson()["voice_id"].string())
        assertNull(call.bodyJson()["voice"])
        assertEquals("mp3", call.bodyJson()["response_format"].string())
        assertEquals("QUJD", (result.audio as BinaryData.Base64).value)
    }

    @Test
    fun `a reference clip replaces the voice and is redacted from the recorded request`() = runTest {
        val server = speechServer()

        val result = provider(server).speechModel("voxtral-mini-tts-2603").doGenerate(
            SpeechCallOptions(
                text = "Hello!",
                voice = "aurelie",
                providerOptions = mapOf(
                    MISTRAL_PROVIDER_ID to buildJsonObject { put("refAudio", "UkVGLUFVRElP") },
                ),
            ),
        )

        val body = server.request().bodyJson()
        assertEquals("UkVGLUFVRElP", body["ref_audio"].string())
        // One or the other: the clip replaces the catalogue voice outright.
        assertNull(body["voice_id"])
        // The recorded request must NOT embed the reference recording.
        assertTrue("UkVGLUFVRElP" !in (result.request?.body ?: ""))
        assertTrue("[redacted]" in (result.request?.body ?: ""))
    }

    @Test
    fun `unsupported knobs warn and an unknown format clamps to mp3`() = runTest {
        val server = speechServer()

        val result = provider(server).speechModel("voxtral-mini-tts-2603").doGenerate(
            SpeechCallOptions(
                text = "Hello!",
                outputFormat = "aac",
                instructions = "whisper it",
                speed = 1.5,
                language = "fr",
            ),
        )

        assertEquals("mp3", server.request().bodyJson()["response_format"].string())
        val unsupported = result.warnings.filterIsInstance<Warning.Unsupported>().map { it.feature }
        assertTrue("outputFormat" in unsupported)
        assertTrue("instructions" in unsupported)
        assertTrue("speed" in unsupported)
        assertTrue("language" in unsupported)
    }

    @Test
    fun `a documented format is forwarded as-is`() = runTest {
        val server = speechServer()

        provider(server).speechModel("voxtral-mini-tts-2603").doGenerate(
            SpeechCallOptions(text = "Hello!", outputFormat = "wav"),
        )

        assertEquals("wav", server.request().bodyJson()["response_format"].string())
    }

    // --- Transcription -------------------------------------------------------------------------------

    private fun transcriptionServer() = TestServer(TestServer.json(MistralAudioFixtures.TRANSCRIPTION))

    @Test
    fun `transcription is multipart with the model, a named file, and seconds-based segments`() = runTest {
        val server = transcriptionServer()

        val result = provider(server).transcriptionModel("voxtral-mini-latest").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Bytes(byteArrayOf(1, 2, 3)),
                mediaType = "audio/mpeg",
            ),
        )

        val call = server.request()
        assertEquals("v1/audio/transcriptions", call.path)
        call.assertMultipartField("model", "voxtral-mini-latest")
        // Named with a real extension — Mistral infers the container from the name, and `mpeg` is not
        // a container it recognises.
        call.assertMultipartFile("file", fileName = "audio.mp3", contentType = "audio/mpeg")

        assertTrue(result.text.startsWith("Galileo was an American robotic space program"))
        assertEquals(4, result.segments.size)
        assertEquals(0.1, result.segments[0].startSecond)
        assertEquals(8.2, result.segments[0].endSecond)
        // Duration comes from usage.prompt_audio_seconds, not the last segment's end (35.9).
        assertEquals(36.0, result.durationInSeconds)
        // The fixture's top-level language is JSON null.
        assertNull(result.language)
        assertEquals("voxtral-mini-latest", result.response.modelId)
    }

    @Test
    fun `usage and speaker ids ride the metadata in camelCase`() = runTest {
        val server = transcriptionServer()

        val result = provider(server).transcriptionModel("voxtral-mini-latest").doGenerate(
            TranscriptionCallOptions(audio = BinaryData.Base64("AQID"), mediaType = "audio/wav"),
        )

        val metadata = result.providerMetadata?.forProvider(MISTRAL_PROVIDER_ID)
        val usage = metadata?.get("usage")?.jsonObject
        assertEquals(13, usage?.get("promptTokens").int())
        assertEquals(151, usage?.get("completionTokens").int())
        assertEquals(164, usage?.get("totalTokens").int())
        assertEquals(36, usage?.get("promptAudioSeconds").int())
        assertEquals(1, usage?.get("requestCount").int())
        val segments = metadata?.get("segments")?.jsonArray
        assertEquals(4, segments?.size)
        assertEquals("speaker_1", segments?.first()?.jsonObject?.get("speakerId").string())
    }

    @Test
    fun `camelCase options reach the wire in snake_case, arrays appended per item`() = runTest {
        val server = transcriptionServer()

        provider(server).transcriptionModel("voxtral-mini-latest").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Base64("AQID"),
                mediaType = "audio/wav",
                providerOptions = mapOf(
                    MISTRAL_PROVIDER_ID to buildJsonObject {
                        putJsonArray("timestampGranularities") { add(JsonPrimitive("segment")) }
                        put("diarize", true)
                        put("temperature", 0.2)
                        putJsonArray("contextBias") {
                            add(JsonPrimitive("Galileo"))
                            add(JsonPrimitive("STS-34"))
                        }
                    },
                ),
            ),
        )

        val call = server.request()
        call.assertMultipartField("timestamp_granularities", "segment")
        call.assertMultipartField("diarize", "true")
        call.assertMultipartField("temperature", "0.2")
        // Two items, two parts — a joined string is one value the server does not parse.
        assertEquals(2, Regex("name=\"context_bias\"").findAll(call.bodyText).count())
        assertFalse("timestampGranularities" in call.bodyText)
    }

    @Test
    fun `language and timestampGranularities together are rejected before any request`() = runTest {
        val server = transcriptionServer()

        assertFailsWith<InvalidArgumentError> {
            provider(server).transcriptionModel("voxtral-mini-latest").doGenerate(
                TranscriptionCallOptions(
                    audio = BinaryData.Base64("AQID"),
                    mediaType = "audio/wav",
                    providerOptions = mapOf(
                        MISTRAL_PROVIDER_ID to buildJsonObject {
                            put("language", "en")
                            putJsonArray("timestampGranularities") { add(JsonPrimitive("word")) }
                        },
                    ),
                ),
            )
        }
        assertEquals(0, server.callCount)
    }
}

/** `mistral/src/__fixtures__/mistral-transcription.json`, copied byte for byte. */
internal object MistralAudioFixtures {

    const val TRANSCRIPTION: String = """{
  "model": "voxtral-mini-latest",
  "text": "Galileo was an American robotic space program that studied the planet Jupiter and its moons, as well as several other solar system bodies. Named after the Italian astronomer Galileo Galilei, the Galileo spacecraft consisted of an orbiter and an atmospheric entry probe. It was delivered into Earth orbit on October 18, 1989, by Space Shuttle Atlantis on the STS-34 mission, and arrived at Jupiter on December 7, 1995, after gravity assist flybys of Venus and Earth, and became the first spacecraft to orbit Jupiter.",
  "language": null,
  "segments": [
    {
      "type": "transcription_segment",
      "text": "Galileo was an American robotic space program that studied the planet Jupiter and its moons, as well as several other solar system bodies.",
      "start": 0.1,
      "end": 8.2,
      "speaker_id": "speaker_1"
    },
    {
      "type": "transcription_segment",
      "text": " Named after the Italian astronomer Galileo Galilei, the Galileo spacecraft consisted of an orbiter and an atmospheric entry probe.",
      "start": 9.1,
      "end": 17.3,
      "speaker_id": "speaker_1"
    },
    {
      "type": "transcription_segment",
      "text": " It was delivered into Earth orbit on October 18, 1989, by Space Shuttle Atlantis on the STS-34 mission, and arrived at Jupiter on December 7, 1995,",
      "start": 18.1,
      "end": 29.9,
      "speaker_id": "speaker_1"
    },
    {
      "type": "transcription_segment",
      "text": " after gravity assist flybys of Venus and Earth, and became the first spacecraft to orbit Jupiter.",
      "start": 30.2,
      "end": 35.9,
      "speaker_id": "speaker_1"
    }
  ],
  "usage": {
    "prompt_audio_seconds": 36,
    "prompt_tokens": 13,
    "completion_tokens": 151,
    "total_tokens": 164,
    "request_count": 1
  }
}"""
}
