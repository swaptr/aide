package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.providers.fishaudio.FISH_AUDIO_OPTIONS_KEY
import com.sabreware.aide.aisdk.providers.fishaudio.FishAudioProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
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
 * Fish Audio, ported from the reference's `fish-audio-speech-model.test.ts` and
 * `fish-audio-transcription-model.test.ts`.
 *
 * The transcription half is where an absent flag cost the most: Fish Audio defaults `ignore_timestamps`
 * to true, so a client that does not send it gets a response with no `segments` at all — a contract
 * field that looks supported and is empty on every call, with nothing in the request to explain it.
 */
class FishAudioConformanceTest {

    private fun fishAudio(server: TestServer) = FishAudioProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
    )

    private fun audioServer() = TestServer(TestServer.bytes(ByteArray(100), "audio/mp3"))

    private val transcript = """
        {"language":"English","language_code":"en","text":"Hello, world!","duration":2.5,
         "segments":[{"text":"Hello,","start":0,"end":1.2},{"text":"world!","start":1.2,"end":2.5}]}
    """.trimIndent()

    private fun transcriptServer() = TestServer(TestServer.json(transcript))

    private fun vendor(build: JsonObjectBuilder.() -> Unit) =
        mapOf(FISH_AUDIO_OPTIONS_KEY to buildJsonObject(build))

    private fun speech() = SpeechCallOptions(text = "Hello, world!")

    private fun transcribe() = TranscriptionCallOptions(
        audio = BinaryData.Bytes(byteArrayOf(0, 1, 2, 3, 4)),
        mediaType = "audio/mpeg",
    )

    // --- Speech --------------------------------------------------------------------------------------

    @Test
    fun `the body is the text and the format, and nothing a caller did not ask for`() = runTest {
        val server = audioServer()

        fishAudio(server).speechModel("s1").doGenerate(speech())

        val call = server.request()
        assertEquals("v1/tts", call.path)
        // The WHOLE body. `reference_id` is a cloned-voice handle rather than a name from a catalogue,
        // so there is no default worth inventing and sending an empty one selects nothing.
        call.assertBodyKeys("text", "format")
        assertEquals("mp3", call.bodyJson()["format"]?.toString()?.trim('"'))
    }

    @Test
    fun `the model is chosen by header here, not by a body field`() = runTest {
        val server = audioServer()

        fishAudio(server).speechModel("s2.1-pro").doGenerate(speech())

        val call = server.request()
        call.assertHeader("model", "s2.1-pro")
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertBodyMissing("model")
    }

    @Test
    fun `a voice becomes reference_id, and the vendor option outranks it`() = runTest {
        val plain = audioServer()
        fishAudio(plain).speechModel("s1")
            .doGenerate(speech().copy(voice = "test-reference-id"))
        assertEquals(
            "test-reference-id",
            plain.request().bodyJson()["reference_id"]?.toString()?.trim('"'),
        )

        val overridden = audioServer()
        fishAudio(overridden).speechModel("s1").doGenerate(
            speech().copy(
                voice = "ignored-voice",
                providerOptions = vendor {
                    put("referenceId", buildJsonArray { add("speaker-a"); add("speaker-b") })
                },
            ),
        )
        // The vendor option wins so a multi-speaker array is expressible at all — the contract's `voice`
        // is one string, and Fish Audio takes a list.
        assertEquals(
            buildJsonArray { add("speaker-a"); add("speaker-b") },
            overridden.request().bodyJson()["reference_id"],
        )
    }

    @Test
    fun `each supported format passes through untouched and warns about nothing`() = runTest {
        for (format in listOf("wav", "pcm", "mp3", "opus")) {
            val server = audioServer()

            val result = fishAudio(server).speechModel("s1")
                .doGenerate(speech().copy(outputFormat = format))

            assertEquals(format, server.request().bodyJson()["format"]?.toString()?.trim('"'))
            result.warnings.assertNoWarnings()
        }
    }

    @Test
    fun `a format Fish Audio does not serve falls back to mp3 with a warning naming both`() =
        runTest {
            val server = audioServer()

            val result = fishAudio(server).speechModel("s1")
                .doGenerate(speech().copy(outputFormat = "flac"))

            assertEquals("mp3", server.request().bodyJson()["format"]?.toString()?.trim('"'))
            result.warnings.assertUnsupported(
                feature = "outputFormat",
                details = "Fish Audio does not support the output format \"flac\". " +
                    "Falling back to mp3. Supported formats are wav, pcm, mp3, opus.",
            )
            assertEquals(1, result.warnings.size)
        }

    @Test
    fun `speed is prosody, and one outside the documented range is dropped rather than clamped`() =
        runTest {
            val inRange = audioServer()
            val ok = fishAudio(inRange).speechModel("s1").doGenerate(speech().copy(speed = 1.5))
            assertEquals(
                buildJsonObject { put("speed", 1.5) },
                inRange.request().bodyJson().obj("prosody"),
            )
            ok.warnings.assertNoWarnings()

            val outOfRange = audioServer()
            val warned = fishAudio(outOfRange).speechModel("s1")
                .doGenerate(speech().copy(speed = 3.0))
            // Clamping to 2 would return audio at a speed the caller never chose and never hears about.
            outOfRange.request().assertBodyMissing("prosody")
            warned.warnings.assertUnsupported(
                feature = "speed",
                details = "Fish Audio speed must be between 0.5 and 2. The speed option was ignored.",
            )
        }

    @Test
    fun `volume and loudness normalization join speed in the one prosody object`() = runTest {
        val server = audioServer()

        val result = fishAudio(server).speechModel("s2-pro").doGenerate(
            speech().copy(
                speed = 1.2,
                providerOptions = vendor {
                    put("volume", -3)
                    put("normalizeLoudness", false)
                },
            ),
        )

        assertEquals(
            buildJsonObject {
                put("speed", 1.2)
                put("volume", -3.0)
                put("normalize_loudness", false)
            },
            server.request().bodyJson().obj("prosody"),
        )
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `loudness normalization reaches the S2 family, which actually applies it`() = runTest {
        val server = audioServer()

        val result = fishAudio(server).speechModel("s2.1-pro")
            .doGenerate(speech().copy(providerOptions = vendor { put("normalizeLoudness", true) }))

        assertEquals(
            buildJsonObject { put("normalize_loudness", true) },
            server.request().bodyJson().obj("prosody"),
        )
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `s1 accepts loudness normalization and ignores it, so it is dropped with a warning`() =
        runTest {
            val server = audioServer()

            val result = fishAudio(server).speechModel("s1")
                .doGenerate(speech().copy(providerOptions = vendor { put("normalizeLoudness", true) }))

            // Accepted and ignored is indistinguishable from working unless you measure the output,
            // which is the case a warning is for.
            server.request().assertBodyMissing("prosody")
            result.warnings.assertUnsupported(
                feature = "providerOptions.fishAudio.normalizeLoudness",
                details = "Fish Audio ignores normalizeLoudness on s1. " +
                    "It is supported by the S2 family (s2-pro, s2.1-pro).",
            )
        }

    @Test
    fun `language and instructions are each warned about, in that order`() = runTest {
        val server = audioServer()

        val result = fishAudio(server).speechModel("s1")
            .doGenerate(speech().copy(language = "en", instructions = "Speak slowly"))

        result.warnings.assertUnsupported(
            feature = "language",
            details = "Fish Audio infers the language from the input text and the selected voice, " +
                "and has no language parameter. The language option was ignored.",
        )
        result.warnings.assertUnsupported(
            feature = "instructions",
            details = "Fish Audio does not support instructions. The instructions option was ignored.",
        )
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun `every generation option reaches the body under Fish Audio's own spelling`() = runTest {
        val server = audioServer()

        fishAudio(server).speechModel("s1").doGenerate(
            speech().copy(
                providerOptions = vendor {
                    put("sampleRate", 44_100)
                    put("mp3Bitrate", 192)
                    put("latency", "balanced")
                    put("temperature", 0.5)
                    put("topP", 0.9)
                    put("chunkLength", 200)
                    put("minChunkLength", 20)
                    put("normalize", false)
                    put("maxNewTokens", 2048)
                    put("repetitionPenalty", 1.5)
                    put("conditionOnPreviousChunks", false)
                    put("earlyStopThreshold", 0.8)
                    put("features", buildJsonArray { add("quality-guard") })
                },
            ),
        )

        // The whole body compared at once: an option that silently never left is exactly what a
        // per-key read of the options that DID cannot see.
        assertEquals(
            buildJsonObject {
                put("text", "Hello, world!")
                put("format", "mp3")
                put("sample_rate", 44_100)
                put("mp3_bitrate", 192)
                put("latency", "balanced")
                put("temperature", 0.5)
                put("top_p", 0.9)
                put("chunk_length", 200)
                put("min_chunk_length", 20)
                put("normalize", false)
                put("max_new_tokens", 2048)
                put("repetition_penalty", 1.5)
                put("condition_on_previous_chunks", false)
                put("early_stop_threshold", 0.8)
                put("features", buildJsonArray { add("quality-guard") })
            },
            server.request().bodyJson(),
        )
    }

    @Test
    fun `a bitrate for the codec that is not in use is dropped with a warning naming both`() =
        runTest {
            val mp3ForOpus = audioServer()
            val a = fishAudio(mp3ForOpus).speechModel("s1").doGenerate(
                speech().copy(
                    outputFormat = "opus",
                    providerOptions = vendor { put("mp3Bitrate", 192) },
                ),
            )
            mp3ForOpus.request().assertBodyMissing("mp3_bitrate")
            a.warnings.assertUnsupported(
                feature = "providerOptions.fishAudio.mp3Bitrate",
                details = "mp3Bitrate only applies to mp3 output. " +
                    "The option was ignored for opus output.",
            )

            val opusForMp3 = audioServer()
            val b = fishAudio(opusForMp3).speechModel("s1")
                .doGenerate(speech().copy(providerOptions = vendor { put("opusBitrate", 48_000) }))
            opusForMp3.request().assertBodyMissing("opus_bitrate")
            b.warnings.assertUnsupported(
                feature = "providerOptions.fishAudio.opusBitrate",
                details = "opusBitrate only applies to opus output. " +
                    "The option was ignored for mp3 output.",
            )
        }

    @Test
    fun `an opus bitrate reaches opus output, negative sentinel and all`() = runTest {
        val server = audioServer()

        val result = fishAudio(server).speechModel("s1").doGenerate(
            speech().copy(
                outputFormat = "opus",
                providerOptions = vendor { put("opusBitrate", -1000) },
            ),
        )

        // -1000 is Fish Audio's own "let the encoder decide"; range-checking it here would refuse the
        // vendor's documented value.
        val body = server.request().bodyJson()
        assertEquals("opus", body["format"]?.toString()?.trim('"'))
        assertEquals("-1000", body["opus_bitrate"]?.toString())
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `the audio, the model and the request body all come back on the result`() = runTest {
        val server = TestServer(
            TestServer.bytes(ByteArray(100), "audio/mp3")
                .withHeaders("x-request-id" to "test-request-id"),
        )

        val result = fishAudio(server).speechModel("s1").doGenerate(speech())

        assertEquals(100, (result.audio as BinaryData.Bytes).value.size)
        assertEquals("s1", result.response.modelId)
        assertEquals("test-request-id", result.response.headers?.get("x-request-id"))
        assertEquals("""{"text":"Hello, world!","format":"mp3"}""", result.request?.body)
    }

    @Test
    fun `a Fish Audio failure carries the vendor's own message`() = runTest {
        val server = TestServer(
            TestServer.error(402, """{"status":402,"message":"No payment -- see charging schemes"}"""),
        )

        val error = assertFailsWith<APICallError> {
            fishAudio(server).speechModel("s1").doGenerate(speech())
        }

        assertContains(assertNotNull(error.message), "No payment -- see charging schemes")
        assertEquals(402, error.statusCode)
    }

    // --- Transcription ---------------------------------------------------------------------------

    @Test
    fun `the audio is a multipart file part named audio, with its declared type`() = runTest {
        val server = transcriptServer()

        fishAudio(server).transcriptionModel("transcribe-1").doGenerate(transcribe())

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("v1/asr", call.path)
        // The field NAME is what the endpoint keys on: a file under any other name is a 422 about a
        // missing `audio`, from a request that carried the audio.
        call.assertMultipartFile("audio", contentType = "audio/mpeg")
        call.assertHeader("Authorization", "Bearer test-api-key")
    }

    @Test
    fun `base64 audio is decoded to bytes rather than uploaded as text`() = runTest {
        val server = transcriptServer()

        fishAudio(server).transcriptionModel("transcribe-1")
            .doGenerate(TranscriptionCallOptions(BinaryData.Base64("AAECAwQ="), "audio/mpeg"))

        server.request().assertMultipartFile("audio", contentType = "audio/mpeg")
    }

    @Test
    fun `timestamps are asked for by default, because the vendor's default returns none`() =
        runTest {
            val server = transcriptServer()

            fishAudio(server).transcriptionModel("transcribe-1").doGenerate(transcribe())

            // Fish Audio defaults `ignore_timestamps` to TRUE. Not sending it is a response with an
            // empty `segments` on every call, and nothing in the request to explain why.
            assertEquals(mapOf("ignore_timestamps" to "false"), server.request().multipart)
        }

    @Test
    fun `a caller can trade the timestamps back for the latency`() = runTest {
        val server = transcriptServer()

        fishAudio(server).transcriptionModel("transcribe-1")
            .doGenerate(transcribe().copy(providerOptions = vendor { put("ignoreTimestamps", true) }))

        server.request().assertMultipartField("ignore_timestamps", "true")
    }

    @Test
    fun `a language hint is sent when given and absent when not`() = runTest {
        val given = transcriptServer()
        fishAudio(given).transcriptionModel("transcribe-1")
            .doGenerate(transcribe().copy(providerOptions = vendor { put("language", "en") }))
        given.request().assertMultipartField("language", "en")

        val omitted = transcriptServer()
        fishAudio(omitted).transcriptionModel("transcribe-1").doGenerate(transcribe())
        assertNull(omitted.request().multipart["language"])
    }

    @Test
    fun `the transcript, its segments and its duration are all read`() = runTest {
        val server = transcriptServer()

        val result =
            fishAudio(server).transcriptionModel("transcribe-1").doGenerate(transcribe())

        assertEquals("Hello, world!", result.text)
        assertEquals(2.5, result.durationInSeconds)
        assertEquals(
            listOf(
                TranscriptionResult.Segment("Hello,", 0.0, 1.2),
                TranscriptionResult.Segment("world!", 1.2, 2.5),
            ),
            result.segments,
        )
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `the DETECTED language is reported, even when the caller asked for another`() = runTest {
        val server = transcriptServer()

        val result = fishAudio(server).transcriptionModel("transcribe-1")
            .doGenerate(transcribe().copy(providerOptions = vendor { put("language", "ja") }))

        // Fish Audio's detection overrides the hint: asking for `ja` on English audio still reports
        // English, and echoing the request back would tell the caller their hint was honoured.
        assertEquals("en", result.language)
    }

    @Test
    fun `the human-readable language is namespaced, because its form is not guaranteed`() = runTest {
        val server = transcriptServer()

        val result =
            fishAudio(server).transcriptionModel("transcribe-1").doGenerate(transcribe())

        // `language` is the ISO code to branch on; `English` is for showing a person, and putting it
        // in the contract's `language` would make a `when` over language codes miss.
        assertEquals(
            buildJsonObject { put("language", "English") },
            result.providerMetadata?.get(FISH_AUDIO_OPTIONS_KEY),
        )
    }

    @Test
    fun `a response that omits the language reports none rather than an empty namespace`() =
        runTest {
            val server = TestServer(TestServer.json("""{"text":"Hello, world!","duration":1}"""))

            val result =
                fishAudio(server).transcriptionModel("transcribe-1").doGenerate(transcribe())

            assertNull(result.language)
            assertNull(result.providerMetadata)
        }

    @Test
    fun `a response with no segments transcribes to text with no segments`() = runTest {
        val server = TestServer(TestServer.json("""{"text":"Hello, world!","duration":1}"""))

        val result =
            fishAudio(server).transcriptionModel("transcribe-1").doGenerate(transcribe())

        assertEquals(emptyList(), result.segments)
        assertEquals(1.0, result.durationInSeconds)
    }

    @Test
    fun `a missing duration is null rather than zero`() = runTest {
        val server = TestServer(TestServer.json("""{"text":"Hello, world!","segments":[]}"""))

        val result =
            fishAudio(server).transcriptionModel("transcribe-1").doGenerate(transcribe())

        // Zero is a duration; "we were not told" is not, and a UI that draws a scrubber cannot tell
        // them apart once they are the same number.
        assertNull(result.durationInSeconds)
    }

    @Test
    fun `a transcription failure carries the vendor's own message`() = runTest {
        val server = TestServer(
            TestServer.error(
                401,
                """{"status":401,"message":"No permission -- see authorization schemes"}""",
            ),
        )

        val error = assertFailsWith<APICallError> {
            fishAudio(server).transcriptionModel("transcribe-1").doGenerate(transcribe())
        }

        assertContains(assertNotNull(error.message), "No permission -- see authorization schemes")
    }
}
