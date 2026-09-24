package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.providers.deepgram.DEEPGRAM_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.deepgram.DeepgramProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertCompatibility
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deepgram, ported from the reference's `deepgram-speech-model.test.ts` and
 * `deepgram-transcription-model.test.ts`.
 *
 * Both halves put everything in the query string, which is why every case here reads the URL: an option
 * that never made it into the query is an option the vendor never saw, and a per-key read of the ones we
 * do send cannot tell the difference.
 */
class DeepgramConformanceTest {

    private fun deepgram(server: TestServer) =
        DeepgramProvider(client = HttpClient(server.engine()), apiKey = "test-api-key")

    private fun audioServer() = TestServer(TestServer.bytes(ByteArray(100), "audio/mp3"))

    private fun transcriptServer() = TestServer(TestServer.json(DeepgramFixtures.TRANSCRIPTION))

    private fun vendor(build: JsonObjectBuilder.() -> Unit) =
        mapOf(DEEPGRAM_PROVIDER_ID to buildJsonObject(build))

    private fun transcribe() = TranscriptionCallOptions(
        audio = BinaryData.Bytes("AUDIO".encodeToByteArray()),
        mediaType = "audio/wav",
    )

    private fun speech(voice: String? = "helena") =
        SpeechCallOptions(text = "Hello, welcome to Deepgram!", voice = voice)

    // --- Transcription ---------------------------------------------------------------------------

    @Test
    fun `the audio is the request body and the model is the query`() = runTest {
        val server = transcriptServer()

        deepgram(server).transcriptionModel("nova-3").doGenerate(transcribe())

        val call = server.request()
        assertEquals("v1/listen", call.path)
        assertEquals("AUDIO", call.bodyText)
        // Multipart here does not fail cleanly: Deepgram transcribes the MIME envelope as if it were
        // audio, so the caller gets a transcript of nothing rather than an error.
        assertEquals(emptyMap(), call.multipart)
        call.assertHeader("Content-Type", "audio/wav")
        call.assertHeader("Authorization", "Token test-api-key")
    }

    @Test
    fun `nothing beyond the model is sent unless the caller asked for it`() = runTest {
        val server = transcriptServer()

        deepgram(server).transcriptionModel("nova-3").doGenerate(transcribe())

        // `utterances` and `detect_language` were once pinned on here, and both are billed features:
        // every caller paid for word-level diarization and language detection whether they read it or
        // not. The whole query, not a lookup, is what can see that.
        assertEquals(mapOf("model" to "nova-3"), server.request().query)
    }

    @Test
    fun `diarization is off unless asked for, and reaches the query when it is`() = runTest {
        val off = transcriptServer()
        deepgram(off).transcriptionModel("nova-3").doGenerate(transcribe())
        assertNull(off.request().query["diarize"])

        val on = transcriptServer()
        deepgram(on).transcriptionModel("nova-3")
            .doGenerate(transcribe().copy(providerOptions = vendor { put("diarize", true) }))
        assertEquals("true", on.request().query["diarize"])
    }

    @Test
    fun `an option Deepgram spells with an underscore is renamed, the rest pass through`() = runTest {
        val server = transcriptServer()

        deepgram(server).transcriptionModel("nova-3").doGenerate(
            transcribe().copy(
                providerOptions = vendor {
                    put("detectLanguage", true)
                    put("keyterm", "galileo")
                    put("paragraphs", true)
                    put("intents", true)
                    put("sentiment", true)
                    put("redact", "numbers")
                    put("replace", "[redacted]")
                },
            ),
        )

        // `detectLanguage` is renamed because Deepgram spells it `detect_language`; the other six are
        // already its own spelling, and an unrecognised key goes out under its own name so a parameter
        // Deepgram adds tomorrow is reachable without a release here.
        assertEquals(
            mapOf(
                "model" to "nova-3",
                "detect_language" to "true",
                "keyterm" to "galileo",
                "paragraphs" to "true",
                "intents" to "true",
                "sentiment" to "true",
                "redact" to "numbers",
                "replace" to "[redacted]",
            ),
            server.request().query,
        )
    }

    @Test
    fun `the recorded response is read three levels down`() = runTest {
        val server = transcriptServer()

        val result = deepgram(server).transcriptionModel("nova-3").doGenerate(transcribe())

        assertContains(result.text, "galileo was an american robotic space program")
        assertContains(result.text, "became the first spacecraft to orbit jupiter")
        // The duration is in `metadata`, the transcript is under results → channels → alternatives, and
        // the detected language is on the CHANNEL rather than beside the transcript. Each level is a
        // place a client quietly reports an empty transcription as a success.
        assertEquals(36.744, result.durationInSeconds)
        assertEquals("en", result.language)
        assertEquals("nova-3", result.response.modelId)
    }

    @Test
    fun `segments are words, not utterances, because utterances have to be paid for`() = runTest {
        val server = transcriptServer()

        val result = deepgram(server).transcriptionModel("nova-3").doGenerate(transcribe())

        // A consumer comparing two vendors' segments has to be comparing the same unit.
        assertEquals(89, result.segments.size)
        assertEquals(
            TranscriptionResult.Segment("galileo", 0.16, 0.79999995),
            result.segments.first(),
        )
    }

    @Test
    fun `the detected language is read off the channel it is reported on`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"metadata":{"duration":1.0},"results":{"channels":[
                   {"detected_language":"sv","alternatives":[{"transcript":"hej","words":[]}]}]}}""",
            ),
        )

        val result = deepgram(server).transcriptionModel("nova-3")
            .doGenerate(transcribe().copy(providerOptions = vendor { put("detectLanguage", true) }))

        assertEquals("sv", result.language)
        assertEquals("hej", result.text)
    }

    @Test
    fun `no detected language is null rather than a guess at the audio's language`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"metadata":{"duration":1.0},"results":{"channels":[
                   {"alternatives":[{"transcript":"hello","words":[]}]}]}}""",
            ),
        )

        val result = deepgram(server).transcriptionModel("nova-3").doGenerate(transcribe())

        assertNull(result.language)
    }

    @Test
    fun `base64 audio is decoded before upload rather than transcribed as text`() = runTest {
        val server = transcriptServer()

        deepgram(server).transcriptionModel("nova-3")
            .doGenerate(TranscriptionCallOptions(BinaryData.Base64("QVVESU8="), "audio/wav"))

        assertEquals("AUDIO", server.request().bodyText)
    }

    // --- Speech: composing the model id ------------------------------------------------------------

    @Test
    fun `a voice family is composed into the real model id, with en as the default language`() =
        runTest {
            val server = audioServer()

            deepgram(server).speechModel("aura-2").doGenerate(speech(voice = "thalia"))

            val call = server.request()
            assertEquals("v1/speak", call.path)
            // A bare `aura-2` resolves to nothing at Deepgram; the real id is family-voice-language.
            assertEquals("aura-2-thalia-en", call.query["model"])
            // The text is the only thing in the body. Everything else is a query parameter.
            call.assertBodyKeys("text")
            assertEquals("Hello, welcome to Deepgram!", call.bodyJson()["text"].string())
        }

    @Test
    fun `a language named by the caller becomes the model id's suffix`() = runTest {
        val server = audioServer()

        val result = deepgram(server).speechModel("aura-2")
            .doGenerate(speech(voice = "celeste").copy(language = "es"))

        assertEquals("aura-2-celeste-es", server.request().query["model"])
        // Both options were consumed by the composition, so there is nothing to warn about.
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `the older aura family composes the same way`() = runTest {
        val server = audioServer()

        deepgram(server).speechModel("aura").doGenerate(speech(voice = "asteria"))

        assertEquals("aura-asteria-en", server.request().query["model"])
    }

    @Test
    fun `whitespace around a voice is trimmed rather than composed into the id`() = runTest {
        val server = audioServer()

        deepgram(server).speechModel("aura-2").doGenerate(speech(voice = " thalia "))

        assertEquals("aura-2-thalia-en", server.request().query["model"])
    }

    @Test
    fun `a family with no voice is refused here rather than 400ing at the vendor`() = runTest {
        val server = audioServer()

        val error = assertFailsWith<InvalidArgumentError> {
            deepgram(server).speechModel("aura-2").doGenerate(speech(voice = null))
        }

        // The vendor's 400 names a model id the caller never typed, which reads as the library being
        // broken rather than as a missing option.
        assertContains(
            assertNotNull(error.message),
            "Deepgram speech model \"aura-2\" requires a `voice` to be set",
        )
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a fully qualified model id is sent as it stands`() = runTest {
        val server = audioServer()

        deepgram(server).speechModel("aura-2-helena-en").doGenerate(speech(voice = null))

        assertEquals("aura-2-helena-en", server.request().query["model"])
    }

    @Test
    fun `a voice alongside a fully qualified model id is warned about, by name`() = runTest {
        val server = audioServer()

        val result = deepgram(server).speechModel("aura-2-helena-en")
            .doGenerate(speech(voice = "different-voice"))

        // Dropping it silently returns a different speaker than the caller asked for, which sounds
        // like a bug in their prompt rather than in their options.
        result.warnings.assertUnsupported(
            feature = "voice",
            details = "Deepgram TTS models embed the voice in the model ID. The voice parameter " +
                "\"different-voice\" was ignored. Use the model ID to select a voice " +
                "(e.g., \"aura-2-helena-en\").",
        )
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `a language alongside a fully qualified model id is warned about, by name`() = runTest {
        val server = audioServer()

        val result = deepgram(server).speechModel("aura-2-helena-en")
            .doGenerate(speech(voice = null).copy(language = "en"))

        result.warnings.assertUnsupported(
            feature = "language",
            details = "Deepgram TTS models are language-specific via the model ID. Language " +
                "parameter \"en\" was ignored. Select a model with the appropriate language " +
                "suffix (e.g., \"-en\" for English).",
        )
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `an auto language falls back to English and says so as a Compatibility warning`() = runTest {
        val server = audioServer()

        val result = deepgram(server).speechModel("aura-2")
            .doGenerate(speech(voice = "thalia").copy(language = "auto"))

        assertEquals("aura-2-thalia-en", server.request().query["model"])
        // Compatibility, not Unsupported: the call went through and produced audio, in a language the
        // caller did not choose. The variant is the difference between "we did something else" and
        // "we ignored you", and `toString().contains("language")` cannot tell them apart.
        result.warnings.assertCompatibility(
            feature = "language",
            details = "Deepgram TTS models do not support automatic language detection. " +
                "Language \"en\" was used instead.",
        )
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `instructions have nowhere to go on the REST endpoint`() = runTest {
        val server = audioServer()

        val result = deepgram(server).speechModel("aura-2")
            .doGenerate(speech().copy(instructions = "Speak slowly"))

        result.warnings.assertUnsupported(
            feature = "instructions",
            details = "Deepgram TTS REST API does not support instructions. " +
                "Instructions parameter was ignored.",
        )
    }

    @Test
    fun `speed is a query parameter and warns about nothing`() = runTest {
        val server = audioServer()

        val result = deepgram(server).speechModel("aura-2").doGenerate(speech().copy(speed = 1.5))

        assertEquals("1.5", server.request().query["speed"])
        // Not range-checked here: Deepgram accepts it for some languages and rejects it for others, and
        // its own message names the language, which a local check could not.
        result.warnings.assertNoWarnings()
    }

    // --- Speech: the parameters that constrain each other -------------------------------------------

    @Test
    fun `a format name is expanded into the encoding and container that agree`() = runTest {
        val server = audioServer()

        deepgram(server).speechModel("aura-2")
            .doGenerate(speech().copy(outputFormat = "wav"))

        // The two have to agree — mp3 with a container is rejected, opus outside ogg is rejected — so a
        // short name expands into both rather than travelling as one.
        assertEquals("wav", server.request().query["container"])
        assertEquals("linear16", server.request().query["encoding"])
    }

    @Test
    fun `the vendor's own encoding and bit rate win, and the container mp3 forbids is dropped`() =
        runTest {
            val server = audioServer()

            deepgram(server).speechModel("aura-2").doGenerate(
                speech().copy(
                    providerOptions = vendor {
                        put("encoding", "mp3")
                        put("bitRate", 48_000)
                        put("container", "wav")
                        put("callback", "https://example.com/callback")
                        put("callbackMethod", "POST")
                        put("mipOptOut", true)
                        put("tag", "test-tag")
                    },
                ),
            )

            val query = server.request().query
            assertEquals("mp3", query["encoding"])
            // The reference's audit found this one missing entirely: an mp3 bitrate that never reached
            // the query returns audio at the default rate, and every per-key assertion about the keys we
            // DID send passed.
            assertEquals("48000", query["bit_rate"])
            assertNull(query["container"])
            assertEquals("https://example.com/callback", query["callback"])
            assertEquals("POST", query["callback_method"])
            assertEquals("true", query["mip_opt_out"])
            assertEquals("test-tag", query["tag"])
        }

    @Test
    fun `a list of tags is comma-joined, because that is how Deepgram reads a repeated one`() =
        runTest {
            val server = audioServer()

            deepgram(server).speechModel("aura-2").doGenerate(
                speech().copy(
                    providerOptions = vendor {
                        put("tag", buildJsonArray { add("tag1"); add("tag2") })
                    },
                ),
            )

            assertEquals("tag1,tag2", server.request().query["tag"])
        }

    @Test
    fun `overriding the encoding removes the sample rate the format had chosen`() = runTest {
        val server = audioServer()

        deepgram(server).speechModel("aura-2").doGenerate(
            speech().copy(
                outputFormat = "linear16_16000",
                providerOptions = vendor { put("encoding", "mp3") },
            ),
        )

        // `linear16_16000` set encoding, container and sample rate together; mp3's rate is fixed by the
        // codec, so leaving the rate behind is a 400 naming `sample_rate` — a parameter the caller never
        // wrote, on a request they thought was about the encoding.
        val query = server.request().query
        assertEquals("mp3", query["encoding"])
        assertNull(query["sample_rate"])
        assertNull(query["container"])
    }

    @Test
    fun `switching to opus moves the container with it rather than leaving wav behind`() = runTest {
        val server = audioServer()

        deepgram(server).speechModel("aura-2").doGenerate(
            speech().copy(
                outputFormat = "linear16_16000",
                providerOptions = vendor { put("encoding", "opus") },
            ),
        )

        val query = server.request().query
        assertEquals("opus", query["encoding"])
        assertEquals("ogg", query["container"])
        assertNull(query["sample_rate"])
    }

    @Test
    fun `a bit rate the new encoding cannot carry is dropped rather than sent`() = runTest {
        val server = audioServer()

        val result = deepgram(server).speechModel("aura-2").doGenerate(
            speech().copy(
                outputFormat = "mp3",
                providerOptions = vendor {
                    put("encoding", "linear16")
                    put("bitRate", 48_000)
                },
            ),
        )

        val query = server.request().query
        assertEquals("linear16", query["encoding"])
        assertNull(query["bit_rate"])
        // Dropped WITH a warning: linear16 is uncompressed, so a bitrate is meaningless rather than
        // merely out of range, and a caller who set one has misunderstood the format they chose.
        result.warnings.assertUnsupported(feature = "providerOptions")
    }

    @Test
    fun `naming only a container changes the encoding it implies`() = runTest {
        val server = audioServer()

        deepgram(server).speechModel("aura-2").doGenerate(
            speech().copy(
                outputFormat = "linear16_16000",
                providerOptions = vendor { put("container", "ogg") },
            ),
        )

        // `ogg` means opus, and opus has a fixed sample rate — so a container alone invalidates the
        // rate the format chose, exactly as an explicit encoding would.
        val query = server.request().query
        assertEquals("opus", query["encoding"])
        assertEquals("ogg", query["container"])
        assertNull(query["sample_rate"])
    }

    // --- Speech: the response ------------------------------------------------------------------------

    @Test
    fun `the audio comes back as the bytes the endpoint returned`() = runTest {
        val server = TestServer(TestServer.bytes(ByteArray(100), "audio/mp3"))

        val result = deepgram(server).speechModel("aura-2").doGenerate(speech())

        assertEquals(100, (result.audio as BinaryData.Bytes).value.size)
        assertEquals("aura-2", result.response.modelId)
        assertEquals("audio/mp3", result.response.headers?.get("content-type"))
    }

    @Test
    fun `what Deepgram billed is read off the response headers, not the body`() = runTest {
        val server = TestServer(
            TestServer.bytes(ByteArray(100), "audio/mp3").withHeaders(
                "dg-model-name" to "aura-2-helena-en",
                "dg-model-uuid" to "4fa750e6-8ade-4394-849f-c104ced3741e",
                "dg-additional-model-uuids" to
                    "0ec06c9b-0aa0-44d0-a001-3ec57d32229e,2e5096c7-7bf1-435e-bbdd-f673f88d0ebd",
                "dg-char-count" to "69",
                "dg-breaks-applied" to "0",
                "dg-pronunciations-applied" to "2",
                "dg-pronunciation-warnings" to "1 unknown word",
                "dg-request-id" to "01a00436-34a3-7cb0-b491-53339eed8eb1",
                "dg-project-id" to "not-about-this-request",
            ),
        )

        val result = deepgram(server).speechModel("aura-2").doGenerate(speech())

        // A binary response has no body to put this in, so the headers are the only place a caller can
        // reconcile a charge against a request. `dg-project-id` is deliberately absent: it identifies
        // the account rather than the call, and this map is handed to whatever is logging.
        assertEquals(
            buildJsonObject {
                put("modelName", "aura-2-helena-en")
                put("modelUuid", "4fa750e6-8ade-4394-849f-c104ced3741e")
                put(
                    "additionalModelUuids",
                    buildJsonArray {
                        add("0ec06c9b-0aa0-44d0-a001-3ec57d32229e")
                        add("2e5096c7-7bf1-435e-bbdd-f673f88d0ebd")
                    },
                )
                put("charCount", 69)
                put("breaksApplied", 0)
                put("pronunciationsApplied", 2)
                put("pronunciationWarnings", "1 unknown word")
                put("requestId", "01a00436-34a3-7cb0-b491-53339eed8eb1")
            },
            result.providerMetadata?.get(DEEPGRAM_PROVIDER_ID),
        )
    }

    @Test
    fun `the request body is reported so a caller can replay what was sent`() = runTest {
        val server = audioServer()

        val result = deepgram(server).speechModel("aura-2").doGenerate(speech())

        assertEquals("""{"text":"Hello, welcome to Deepgram!"}""", result.request?.body)
    }

    @Test
    fun `a Deepgram failure carries its own err_msg rather than a body excerpt`() = runTest {
        val server = TestServer(
            TestServer.error(
                400,
                """{"err_code":"INVALID_QUERY_PARAMETER",
                   "err_msg":"Invalid 'model' value of 'aura-2-not-a-real-voice-en'.",
                   "request_id":"01a00450-5a52-70f0-9253-2fc492123595"}""",
            ),
        )

        val error = assertFailsWith<APICallError> {
            deepgram(server).speechModel("aura-2").doGenerate(speech(voice = "not-a-real-voice"))
        }

        // Deepgram's error shape shares no key with anyone else's — flat, and named `err_msg` — so the
        // default parser finds nothing and leaves the caller a raw body excerpt for what is a one-line,
        // actionable complaint about a query parameter.
        // The port's transport prefixes every APICallError with the status and URL, where the
        // reference's message is the vendor's alone. That prefix lives in `:aisdk:util` rather than in
        // this vendor's package, so the assertion is on the vendor's own sentence being there at all.
        assertContains(
            assertNotNull(error.message),
            "Invalid 'model' value of 'aura-2-not-a-real-voice-en'.",
        )
        assertEquals(400, error.statusCode)
    }
}
