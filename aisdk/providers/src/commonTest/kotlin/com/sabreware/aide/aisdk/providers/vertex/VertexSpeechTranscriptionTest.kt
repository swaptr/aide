package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The three speech/transcription wires one Vertex provider routes between: Cloud Text-to-Speech for
 * `chirp*`, Gemini TTS (reused from the google package) for everything else, Cloud Speech-to-Text v2
 * for non-Gemini transcription, and Vertex `generateContent` for Gemini transcription — each with a
 * different host or path, which is exactly what these tests pin.
 */
class VertexSpeechTranscriptionTest {

    private fun provider(server: TestServer) = VertexProvider(
        client = HttpClient(server.engine()),
        projectId = "test-project",
        location = "us-central1",
        accessToken = { "test-token" },
    )

    // --- Cloud TTS (chirp) ---------------------------------------------------------------------------

    @Test
    fun `a bare voice name is composed into the qualified Chirp form`() = runTest {
        val server = TestServer(TestServer.json("""{"audioContent":"QUJD"}"""))

        val result = provider(server).speechModel("chirp-3-hd")
            .doGenerate(SpeechCallOptions(text = "Hello!", voice = "Aoede", language = "de-DE"))

        val call = server.request()
        // A single worldwide host — not the regional Vertex host everything else here uses.
        assertEquals("https://texttospeech.googleapis.com/v1/text:synthesize", call.url)
        call.assertBodyEquals(
            """{"input":{"text":"Hello!"},
                "voice":{"languageCode":"de-DE","name":"de-DE-Chirp3-HD-Aoede"},
                "audioConfig":{"audioEncoding":"LINEAR16"}}""",
        )
        assertEquals(BinaryData.Base64("QUJD"), result.audio)
        assertEquals(
            "audio/wav",
            result.providerMetadata?.get(VERTEX_PROVIDER_ID)?.get("mimeType").string(),
        )
    }

    @Test
    fun `a qualified Chirp voice passes through verbatim and supplies the language`() = runTest {
        val server = TestServer(TestServer.json("""{"audioContent":"QUJD"}"""))

        provider(server).speechModel("chirp-3-hd")
            .doGenerate(SpeechCallOptions(text = "Hi", voice = "en-GB-Chirp3-HD-Kore", speed = 1.25))

        server.request().assertBodyEquals(
            """{"input":{"text":"Hi"},
                "voice":{"languageCode":"en-GB","name":"en-GB-Chirp3-HD-Kore"},
                "audioConfig":{"audioEncoding":"LINEAR16","speakingRate":1.25}}""",
        )
    }

    @Test
    fun `instructions and a non-wav format warn, and empty audio is loud`() = runTest {
        val server = TestServer(
            TestServer.json("""{"audioContent":"QUJD"}"""),
            TestServer.json("""{}"""),
        )
        val model = provider(server).speechModel("chirp-3-hd")

        val result = model.doGenerate(
            SpeechCallOptions(text = "Hi", instructions = "cheerfully", outputFormat = "mp3"),
        )
        result.warnings.assertUnsupported("instructions")
        result.warnings.assertUnsupported("outputFormat")

        assertFailsWith<NoContentGeneratedError> {
            model.doGenerate(SpeechCallOptions(text = "Hi"))
        }
    }

    // --- Gemini TTS reuse ----------------------------------------------------------------------------

    @Test
    fun `a non-chirp id reuses the Gemini TTS wire on the Vertex base, with options re-filed`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"candidates":[{"content":{"role":"model","parts":[{"inlineData":""" +
                    """{"mimeType":"audio/L16;rate=24000","data":"AQIDBAUGBwg="}}]},""" +
                    """"finishReason":"STOP"}]}""",
            ),
        )

        provider(server).speechModel("gemini-2.5-flash-preview-tts").doGenerate(
            SpeechCallOptions(
                text = "Hello!",
                providerOptions = mapOf(
                    VERTEX_PROVIDER_ID to buildJsonObject {
                        putJsonObject("multiSpeakerVoiceConfig") { put("marker", "from-vertex") }
                    },
                ),
            ),
        )

        val call = server.request()
        assertTrue(
            call.url.startsWith(
                "https://us-central1-aiplatform.googleapis.com/v1/projects/test-project" +
                    "/locations/us-central1/publishers/google/models/gemini-2.5-flash-preview-tts:",
            ),
            "expected the Vertex publisher base, got ${call.url}",
        )
        // The option was filed under OUR provider id and still reached the google-package delegate —
        // the re-filing is the wrapper's whole job.
        assertEquals(
            "from-vertex",
            call.bodyJson().obj("generationConfig", "speechConfig", "multiSpeakerVoiceConfig")
                ?.get("marker").string(),
        )
    }

    // --- Cloud Speech-to-Text ------------------------------------------------------------------------

    private fun sttOptions() = TranscriptionCallOptions(
        audio = BinaryData.Base64("QUJD"),
        mediaType = "audio/wav",
    )

    @Test
    fun `non-gemini transcription posts to the regional Speech-to-Text host`() = runTest {
        val server = TestServer(TestServer.json(VertexFixtures.CLOUD_STT))

        val result = provider(server).transcriptionModel("chirp_2").doGenerate(sttOptions())

        val call = server.request()
        assertEquals(
            "https://us-central1-speech.googleapis.com/v2/projects/test-project" +
                "/locations/us-central1/recognizers/_:recognize",
            call.url,
        )
        call.assertBodyEquals(
            """{"config":{"model":"chirp_2","languageCodes":["auto"],"autoDecodingConfig":{},
                "features":{"enableWordTimeOffsets":true,"enableAutomaticPunctuation":true}},
                "content":"QUJD"}""",
        )
        assertEquals("Hello from Vertex.", result.text)
        assertEquals(
            TranscriptionResult.Segment("Hello", 0.1, 0.5),
            result.segments.first(),
        )
        assertEquals("en", result.language)
        assertEquals(2.0, result.durationInSeconds)
    }

    @Test
    fun `the Speech-to-Text region is its own knob, not the Vertex location`() = runTest {
        val server = TestServer(TestServer.json(VertexFixtures.CLOUD_STT))

        provider(server).transcriptionModel("chirp_2").doGenerate(
            sttOptions().copy(
                providerOptions = mapOf(
                    VERTEX_PROVIDER_ID to buildJsonObject { put("region", "global") },
                ),
            ),
        )

        // `global` drops the regional prefix — Speech-to-Text regions are a different menu from
        // Vertex's, which is why the region is overridable per call at all.
        assertTrue(server.request().url.startsWith("https://speech.googleapis.com/v2/projects/"))
    }

    // --- Gemini transcription on Vertex --------------------------------------------------------------

    @Test
    fun `gemini transcription rides generateContent with a camelCase config`() = runTest {
        val server = TestServer(TestServer.json(VertexFixtures.GEMINI_TRANSCRIPTION))

        val result = provider(server).transcriptionModel("gemini-3.5-transcribe").doGenerate(
            sttOptions().copy(
                providerOptions = mapOf(
                    VERTEX_PROVIDER_ID to buildJsonObject {
                        put("wordTimestamp", true)
                        put("mode", "VERBATIM")
                    },
                ),
            ),
        )

        val call = server.request()
        assertTrue(call.url.endsWith("/publishers/google/models/gemini-3.5-transcribe:generateContent"))
        // Camel case here; the Gemini API's own transcription surface (`/interactions`) is snake_case.
        // Same model id, two wires — the class KDocs on either side say why.
        call.assertBodyEquals(
            """{"contents":[{"role":"user","parts":[{"inlineData":{"mimeType":"audio/wav","data":"QUJD"}}]}],
                "generationConfig":{"audioTranscriptionConfig":{"wordTimestamp":true,"mode":"VERBATIM"}}}""",
        )
        assertEquals("Hello world.", result.text)
        assertEquals(2, result.segments.size)
        assertEquals(TranscriptionResult.Segment("Hello", 0.1, 0.4), result.segments[0])
        assertEquals("en-us", result.language)
        assertEquals(
            19,
            result.providerMetadata?.get(VERTEX_PROVIDER_ID)
                ?.obj("usageMetadata")?.get("totalTokenCount")?.string()?.toInt(),
        )
    }

    @Test
    fun `a live model id fails loudly before any request`() = runTest {
        val server = TestServer(TestServer.json("{}"))

        assertFailsWith<InvalidArgumentError> {
            provider(server).transcriptionModel("gemini-3.5-transcribe-live").doGenerate(sttOptions())
        }
        assertEquals(0, server.callCount)
    }
}
