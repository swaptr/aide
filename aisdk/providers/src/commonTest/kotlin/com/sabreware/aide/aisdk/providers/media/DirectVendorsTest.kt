package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.cartesia.CARTESIA_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.cartesia.CartesiaProvider
import com.sabreware.aide.aisdk.providers.hume.HumeProvider
import com.sabreware.aide.aisdk.providers.lmnt.LmntProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertNoWarningAbout
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The remaining direct (non-queued) vendors, ported from the reference's own source rather than from
 * memory — which is why each one's oddity is encoded rather than guessed at.
 */
class DirectVendorsTest {

    private fun audio() = TestServer(TestServer.bytes("AUDIO".encodeToByteArray(), "audio/mpeg"))

    // --- LMNT -----------------------------------------------------------------------------------

    @Test
    fun `LMNT authenticates with x-api-key and defaults its own voice`() = runTest {
        val server = audio()
        val provider = LmntProvider(server.http(), "k", LmntProvider.DEFAULT_BASE_URL, emptyMap())

        val result = provider.speechModel("blizzard").doGenerate(SpeechCallOptions(text = "hi"))

        val call = server.request()
        call.assertHeader("x-api-key", "k")
        call.assertNoHeader("Authorization")
        call.assertBodyKeys("model", "text", "voice", "response_format")
        call.assertBodyJson {
            assertEquals("blizzard", it["model"].string())
            assertEquals("hi", it["text"].string())
            assertEquals("ava", it["voice"].string())
            assertEquals("mp3", it["response_format"].string())
        }
        assertEquals("AUDIO", (result.audio as BinaryData.Bytes).value.decodeToString())
        assertEquals("/v1/ai/speech/bytes", "/" + call.path)
    }

    @Test
    fun `an unsupported LMNT format is clamped with a warning rather than sent`() = runTest {
        val server = audio()
        val provider = LmntProvider(server.http(), "k", LmntProvider.DEFAULT_BASE_URL, emptyMap())

        val result = provider.speechModel("blizzard")
            .doGenerate(SpeechCallOptions(text = "hi", outputFormat = "opus"))

        // Sending it is a 400; clamping at least produces audio and says what happened.
        server.request().assertBodyJson { assertEquals("mp3", it["response_format"].string()) }
        result.warnings.assertUnsupported("outputFormat", "Unsupported output format: opus. Using mp3 instead.")
    }

    @Test
    fun `a supported LMNT format passes through`() = runTest {
        val server = audio()
        val provider = LmntProvider(server.http(), "k", LmntProvider.DEFAULT_BASE_URL, emptyMap())

        val result = provider.speechModel("blizzard")
            .doGenerate(SpeechCallOptions(text = "hi", outputFormat = "wav"))

        server.request().assertBodyJson { assertEquals("wav", it["response_format"].string()) }
        result.warnings.assertNoWarningAbout("outputFormat")
    }

    // --- Hume -----------------------------------------------------------------------------------

    private fun hume(server: TestServer) =
        HumeProvider(server.http(), "k", HumeProvider.DEFAULT_BASE_URL, emptyMap())

    @Test
    fun `Hume wraps the text in an utterance with a voice object`() = runTest {
        val server = audio()

        hume(server).speechModel("octave").doGenerate(SpeechCallOptions(text = "hello"))

        val call = server.request()
        call.assertHeader("X-Hume-Api-Key", "k")
        call.assertBodyKeys("utterances", "format")
        // Utterances, not a text field; and the voice is an object with an id, not a bare string —
        // carrying `provider`, without which the id is resolved against the caller's own cloned voices
        // and a built-in voice is reported as not found.
        call.assertBodyJson {
            val utterance = it.arr("utterances")!!.single().jsonObject
            assertEquals("hello", utterance["text"].string())
            assertEquals(HumeProvider.DEFAULT_VOICE_ID, utterance.obj("voice")!!["id"].string())
            assertEquals("HUME_AI", utterance.obj("voice")!!["provider"].string())
        }
    }

    @Test
    fun `Hume is the one vendor here with a place for delivery instructions`() = runTest {
        val server = audio()

        val result = hume(server).speechModel("octave")
            .doGenerate(SpeechCallOptions(text = "hi", instructions = "sound delighted"))

        // ElevenLabs and LMNT warn about instructions; Hume has an utterance description for them.
        server.request().assertBodyJson {
            assertEquals("sound delighted", it.arr("utterances")!!.single().jsonObject["description"].string())
        }
        result.warnings.assertNoWarningAbout("instructions")
    }

    @Test
    fun `Hume takes speed per utterance and warns only about the language it cannot select`() = runTest {
        val server = audio()

        val result = hume(server).speechModel("octave")
            .doGenerate(SpeechCallOptions(text = "hi", speed = 1.5, language = "en"))

        // `speed` is `utterances[0].speed` here. Warning about it — which this asserted until the wire
        // was read — tells a caller to work around a knob the vendor supports, and drops the value.
        server.request().assertBodyJson {
            assertEquals(1.5, it.arr("utterances")!!.single().jsonObject["speed"].double())
        }
        result.warnings.assertNoWarningAbout("speed")
        result.warnings.assertUnsupported(
            feature = "language",
            details = "Hume speech models do not support language selection. " +
                "Language parameter \"en\" was ignored.",
        )
        assertEquals(1, result.warnings.size, result.warnings.toString())
    }

    @Test
    fun `the format is an object with a type, not a bare string`() = runTest {
        val server = audio()

        hume(server).speechModel("octave").doGenerate(SpeechCallOptions(text = "hi", outputFormat = "wav"))

        server.request().assertBodyJson { assertEquals("wav", it.obj("format")!!["type"].string()) }
    }

    // --- Cartesia -------------------------------------------------------------------------------

    private fun cartesia(server: TestServer) = CartesiaProvider(
        server.http(),
        "k",
        CartesiaProvider.DEFAULT_BASE_URL,
        CartesiaProvider.DEFAULT_API_VERSION,
        emptyMap(),
    )

    /** Cartesia refuses to pick a voice for the caller, so every call below has to name one. */
    private fun speech(format: String? = null) =
        SpeechCallOptions(text = "hi", voice = "v1", outputFormat = format)

    @Test
    fun `Cartesia pins the API version by header, which is required not optional`() = runTest {
        val server = audio()

        cartesia(server).speechModel("sonic-3").doGenerate(speech())

        // Omitting it is rejected rather than defaulted, and that failure looks nothing like a missing
        // header.
        val call = server.request()
        call.assertHeader("Cartesia-Version", CartesiaProvider.DEFAULT_API_VERSION)
        call.assertHeader("Authorization", "Bearer k")
        assertEquals("/tts/bytes", "/" + call.path)
    }

    @Test
    fun `Cartesia calls the input transcript and takes a voice object`() = runTest {
        val server = audio()

        cartesia(server).speechModel("sonic-3")
            .doGenerate(SpeechCallOptions(text = "hello", voice = "v1"))

        val call = server.request()
        call.assertBodyKeys("model_id", "transcript", "voice", "output_format")
        call.assertBodyJson {
            assertEquals("hello", it["transcript"].string())
            assertEquals("id", it.obj("voice")!!["mode"].string())
            assertEquals("v1", it.obj("voice")!!["id"].string())
        }
    }

    @Test
    fun `the output format is expanded into a consistent container, encoding and rate`() = runTest {
        val server = audio()

        cartesia(server).speechModel("sonic-3").doGenerate(speech("wav"))

        // The three have to agree: wav pins pcm_s16le at 44.1kHz, and takes no bitrate. Getting one
        // wrong is a 400 or, worse, audio that plays as noise.
        server.request().assertBodyJson {
            val format = it.obj("output_format")!!
            assertEquals(setOf("container", "encoding", "sample_rate"), format.keys)
            assertEquals("wav", format["container"].string())
            assertEquals("pcm_s16le", format["encoding"].string())
            assertEquals(44_100, format["sample_rate"].int())
        }
    }

    @Test
    fun `mp3 carries a bit rate and no encoding, because each contradicts the other container`() = runTest {
        val server = audio()

        cartesia(server).speechModel("sonic-3").doGenerate(speech("mp3"))

        // The whole object, because a `bit_rate` that was never sent is exactly the kind of absence a
        // per-key read cannot see — and mp3 without it is the request Cartesia rejects.
        server.request().assertBodyJson {
            val format = it.obj("output_format")!!
            assertEquals(setOf("container", "sample_rate", "bit_rate"), format.keys)
            assertEquals("mp3", format["container"].string())
            assertEquals(44_100, format["sample_rate"].int())
            assertEquals(128_000, format["bit_rate"].int())
            assertNull(format["encoding"])
        }
    }

    @Test
    fun `mulaw pins the telephony sample rate rather than the default`() = runTest {
        val server = audio()

        cartesia(server).speechModel("sonic-3").doGenerate(speech("mulaw"))

        server.request().assertBodyJson {
            val format = it.obj("output_format")!!
            assertEquals(setOf("container", "encoding", "sample_rate"), format.keys)
            assertEquals("raw", format["container"].string())
            assertEquals("pcm_mulaw", format["encoding"].string())
            assertEquals(8_000, format["sample_rate"].int())
        }
    }

    @Test
    fun `a rate suffixed onto the format name is parsed, not swallowed`() = runTest {
        val server = audio()

        cartesia(server).speechModel("sonic-3").doGenerate(speech("pcm_24000"))

        // Looking the whole string up as a key finds nothing, falls back to mp3, and hands 44.1kHz
        // audio to a caller who asked for 24k — a mismatch that presents as a pitch shift, not an error.
        server.request().assertBodyJson {
            val format = it.obj("output_format")!!
            assertEquals("raw", format["container"].string())
            assertEquals("pcm_f32le", format["encoding"].string())
            assertEquals(24_000, format["sample_rate"].int())
        }
    }

    @Test
    fun `speed is generation_config, and one outside the vendor's range is dropped with a warning`() =
        runTest {
            val server = audio()

            val inRange = cartesia(server).speechModel("sonic-3")
                .doGenerate(SpeechCallOptions(text = "hi", voice = "v1", speed = 1.2))
            server.request().assertBodyJson {
                assertEquals(1.2, it.obj("generation_config")!!["speed"].double())
            }
            inRange.warnings.assertNoWarningAbout("speed")

            val outOfRange = cartesia(server).speechModel("sonic-3")
                .doGenerate(SpeechCallOptions(text = "hi", voice = "v1", speed = 3.0))
            server.request(1).assertBodyMissing("generation_config")
            outOfRange.warnings.assertUnsupported(
                feature = "speed",
                details = "Cartesia speed must be between 0.6 and 1.5. The speed option was ignored.",
            )
        }

    @Test
    fun `Cartesia transcription names the model in a field and calls a word a word`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"text":"hello there","language":"en","duration":2.5,
                    "words":[{"word":"hello","start":0.0,"end":1.0},
                             {"word":"there","start":1.0,"end":2.5}]}""",
            ),
        )

        val result = cartesia(server).transcriptionModel("ink-whisper").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Bytes("AUDIO".encodeToByteArray()),
                mediaType = "audio/wav",
                providerOptions = mapOf(
                    CARTESIA_PROVIDER_ID to buildJsonObject {
                        put(
                            "timestampGranularities",
                            buildJsonArray { add(JsonPrimitive("word")) },
                        )
                    },
                ),
            ),
        )

        val call = server.request()
        assertEquals("stt", call.path)
        call.assertMultipartField("model", "ink-whisper")
        // The brackets are part of the field name: Cartesia reads the repeated pair as a list, and one
        // comma-joined value is rejected as an unknown granularity.
        call.assertMultipartField("timestamp_granularities[]", "word")
        // The same version pin the speech half needs — the header is required, not defaulted.
        call.assertHeader("Cartesia-Version", CartesiaProvider.DEFAULT_API_VERSION)
        assertEquals("hello there", result.text)
        assertEquals(listOf("hello", "there"), result.segments.map { it.text })
        assertEquals(2.5, result.durationInSeconds)
        assertEquals("en", result.language)
    }

    @Test
    fun `the socket-only Cartesia family refuses a file rather than posting one`() = runTest {
        val server = TestServer(TestServer.json("{}"))

        assertFailsWith<UnsupportedFunctionalityError> {
            cartesia(server).transcriptionModel("ink-2")
                .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))
        }
        // Nothing was sent: `ink-2` has no batch endpoint, so a request would be a 4xx about a model
        // the caller believes is supported.
        assertEquals(0, server.callCount)
    }
}
