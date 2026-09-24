package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.TranscriptionStreamOptions
import com.sabreware.aide.aisdk.TranscriptionStreamPart
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.elevenlabs.ELEVENLABS_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.elevenlabs.ElevenLabsProvider
import com.sabreware.aide.aisdk.providers.elevenlabs.ElevenLabsRealtimeEvent
import com.sabreware.aide.aisdk.providers.elevenlabs.ElevenLabsRealtimeMapper
import com.sabreware.aide.aisdk.providers.elevenlabs.elevenLabsAudioChunk
import com.sabreware.aide.aisdk.providers.elevenlabs.elevenLabsCommit
import com.sabreware.aide.aisdk.providers.elevenlabs.elevenLabsRealtimeFormat
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ElevenLabs live transcription, ported from the `doStream` half of the reference's
 * `elevenlabs-transcription-model.test.ts`.
 *
 * Ktor's `MockEngine` does not implement the WebSocket upgrade, so no harness in this module can serve
 * this protocol. What the reference's mock socket actually pins, though, is two pure things: the URL and
 * frames the provider SENDS, and the mapping from a recorded frame sequence to stream parts. Both are
 * reachable here because the port already split the mapper out from the socket — which is the whole
 * reason it was split.
 *
 * The partial/final distinction is why the streaming contract exists at all: a live transcriber revises
 * what it said a moment ago, and a consumer that treats every emission as settled renders text that
 * jumps. Every recorded sequence below exists to pin one way that distinction can be got wrong.
 */
class ElevenLabsRealtimeTest {

    private fun provider(server: TestServer) = ElevenLabsProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
    )

    private fun model(server: TestServer = TestServer(TestServer.json("{}"))) =
        provider(server).transcriptionModel("scribe_v2_realtime")

    private fun streamOptions(
        rate: Int? = 16_000,
        type: String = "audio/pcm",
        streaming: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
        vendor: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
    ) = TranscriptionStreamOptions(
        audio = emptyFlow(),
        inputAudioFormat = AudioFormat(type = type, rate = rate),
        providerOptions = if (streaming == null && vendor == null) {
            null
        } else {
            mapOf(
                ELEVENLABS_PROVIDER_ID to buildJsonObject {
                    vendor?.invoke(this)
                    streaming?.let { put("streaming", buildJsonObject(it)) }
                },
            )
        },
    )

    /** The session URL, which `doStream` reports as the request body because there is no request body. */
    private suspend fun sessionUrl(options: TranscriptionStreamOptions): String =
        assertNotNull(assertNotNull(model().doStream(options)).request?.body)

    private fun query(url: String): List<Pair<String, String>> =
        url.substringAfter('?').split('&').filter { it.isNotBlank() }
            .map { it.substringBefore('=') to it.substringAfter('=', "") }

    // --- The session, which is configured entirely by query string ---------------------------------

    @Test
    fun `every realtime option is a query parameter on the session URL`() = runTest {
        val url = sessionUrl(
            streamOptions(
                vendor = { put("languageCode", "en") },
                streaming = {
                    put("commitStrategy", "manual")
                    put("enableLogging", false)
                    put("includeLanguageDetection", true)
                    put("includeTimestamps", true)
                    put("minSilenceDurationMs", 200)
                    put("minSpeechDurationMs", 150)
                    put("noVerbatim", true)
                    put("previousText", "Earlier context")
                    put("vadSilenceThresholdSecs", 1.2)
                    put("vadThreshold", 0.5)
                    put("keyterms", buildJsonArray { add("Vercel"); add("AI SDK") })
                    put("secondaryLanguages", buildJsonArray { add("es"); add("fr") })
                },
            ),
        )

        assertTrue(
            url.startsWith("wss://api.elevenlabs.io/v1/speech-to-text/realtime?"),
            "the socket URL is derived from the HTTP base, not configured separately: $url",
        )
        val parameters = query(url)
        assertEquals(
            listOf(
                "model_id" to "scribe_v2_realtime",
                "audio_format" to "pcm_16000",
                "commit_strategy" to "manual",
                "enable_logging" to "false",
                "include_language_detection" to "true",
                "include_timestamps" to "true",
                "language_code" to "en",
                "min_silence_duration_ms" to "200",
                "min_speech_duration_ms" to "150",
                "no_verbatim" to "true",
                "vad_silence_threshold_secs" to "1.2",
                "vad_threshold" to "0.5",
            ),
            parameters.filter { it.first !in setOf("keyterms", "secondary_languages") },
        )
        // Repeated under one name rather than comma-joined: ElevenLabs reads the comma as part of the
        // term, so a joined list is one nonsense keyterm instead of two real ones.
        assertEquals(
            listOf("Vercel", "AI%20SDK"),
            parameters.filter { it.first == "keyterms" }.map { it.second },
        )
        assertEquals(
            listOf("es", "fr"),
            parameters.filter { it.first == "secondary_languages" }.map { it.second },
        )
        // There is no `previous_text` parameter: it rides the first audio frame instead.
        assertTrue(parameters.none { it.first == "previous_text" })
    }

    @Test
    fun `language detection forces timestamps on, because it arrives on that event`() = runTest {
        val url = sessionUrl(
            streamOptions(
                streaming = {
                    put("includeLanguageDetection", true)
                    put("includeTimestamps", false)
                },
            ),
        )

        // ElevenLabs delivers the detected language on the timestamp-bearing commit alone. Honouring
        // `includeTimestamps: false` literally gives a caller who asked for the language a stream that
        // never reports one.
        assertContains(query(url), "include_language_detection" to "true")
        assertContains(query(url), "include_timestamps" to "true")
    }

    @Test
    fun `an explicitly null realtime option is absent rather than sent as its default`() = runTest {
        val url = sessionUrl(
            streamOptions(
                streaming = {
                    listOf(
                        "commitStrategy", "enableLogging", "filterBackgroundAudio",
                        "includeLanguageDetection", "includeTimestamps", "keyterms",
                        "minSilenceDurationMs", "minSpeechDurationMs", "noVerbatim",
                        "previousText", "secondaryLanguages", "vadSilenceThresholdSecs",
                        "vadThreshold",
                    ).forEach { put(it, JsonNull) }
                },
            ),
        )

        // Null means omit, never "send the default". Sending `include_timestamps=false` explicitly is a
        // different session from not asking, and the vendor's own default is the one the caller wanted.
        assertEquals(
            listOf("model_id" to "scribe_v2_realtime", "audio_format" to "pcm_16000"),
            query(url),
        )
    }

    // --- Input formats ------------------------------------------------------------------------------

    @Test
    fun `mu-law input is named at the one rate ElevenLabs serves it at`() = runTest {
        assertContains(
            query(sessionUrl(streamOptions(type = "audio/pcmu", rate = 8_000))),
            "audio_format" to "ulaw_8000",
        )
        assertEquals("ulaw_8000", elevenLabsRealtimeFormat(AudioFormat("audio/pcmu")).audioFormat)
    }

    @Test
    fun `PCM with no rate declared defaults to 16 kHz rather than failing`() = runTest {
        assertContains(
            query(sessionUrl(streamOptions(rate = null))),
            "audio_format" to "pcm_16000",
        )
    }

    @Test
    fun `a sample rate ElevenLabs does not accept fails here, not at the handshake`() = runTest {
        val error = assertFailsWith<InvalidArgumentError> {
            model().doStream(streamOptions(rate = 32_000))
        }

        // The handshake rejection names neither the rate nor the format, from a socket that never
        // carried a frame, so the caller learns only that "it did not connect".
        assertContains(assertNotNull(error.message), "ElevenLabs realtime transcription supports")
        assertEquals("inputAudioFormat", error.argument)
    }

    @Test
    fun `mu-law at any rate but 8 kHz is refused rather than silently resampled`() = runTest {
        val error = assertFailsWith<InvalidArgumentError> {
            model().doStream(streamOptions(type = "audio/pcmu", rate = 16_000))
        }
        assertContains(assertNotNull(error.message), "8000 Hz")
    }

    @Test
    fun `a model that is not the realtime one cannot open a session`() = runTest {
        assertFailsWith<UnsupportedFunctionalityError> {
            provider(TestServer(TestServer.json("{}"))).transcriptionModel("scribe_v2")
                .doStream(streamOptions())
        }
    }

    // --- Combinations ElevenLabs refuses --------------------------------------------------------------

    @Test
    fun `background filtering and timestamps are refused together`() = runTest {
        val error = assertFailsWith<InvalidArgumentError> {
            model().doStream(
                streamOptions(
                    streaming = {
                        put("filterBackgroundAudio", true)
                        put("includeTimestamps", true)
                    },
                ),
            )
        }
        // Sending the pair yields a session that silently reports neither, so the failure has to happen
        // before the socket opens.
        assertContains(assertNotNull(error.message), "cannot be combined")
    }

    @Test
    fun `background filtering and language detection are refused together`() = runTest {
        val error = assertFailsWith<InvalidArgumentError> {
            model().doStream(
                streamOptions(
                    streaming = {
                        put("filterBackgroundAudio", true)
                        put("includeLanguageDetection", true)
                    },
                ),
            )
        }
        assertContains(assertNotNull(error.message), "cannot be combined")
    }

    @Test
    fun `the warnings a session was configured with ride the stream's first part`() {
        // What the reference pins here is that `diarize` and `numSpeakers` — which configure the batch
        // decoder, not the one the socket runs — are each named in a warning. Which warnings `doStream`
        // chose is not observable without opening a session, so what is checked here is the half that
        // is: whatever the session was configured with reaches the caller as `stream-start` rather than
        // as a field on a result they have to remember to read. See the report for the gap.
        val mapper = mapperWith(
            warnings = listOf(
                Warning.Unsupported(
                    "providerOptions.elevenlabs.diarize",
                    "ElevenLabs realtime transcription does not support diarize.",
                ),
                Warning.Unsupported(
                    "providerOptions.elevenlabs.numSpeakers",
                    "ElevenLabs realtime transcription does not support numSpeakers.",
                ),
            ),
        )

        val start = mapper.on(sessionStarted).single() as TranscriptionStreamPart.StreamStart

        start.warnings.assertUnsupported(
            "providerOptions.elevenlabs.diarize",
            "ElevenLabs realtime transcription does not support diarize.",
        )
        start.warnings.assertUnsupported(
            "providerOptions.elevenlabs.numSpeakers",
            "ElevenLabs realtime transcription does not support numSpeakers.",
        )
    }

    @Test
    fun `configuring a session with batch-only options still opens one`() = runTest {
        val result = model().doStream(
            streamOptions(vendor = { put("diarize", true); put("numSpeakers", 2) }),
        )

        // The options are warned about, not refused: they are wrong for this endpoint but they do not
        // make the session invalid.
        assertNotNull(result)
    }

    // --- The frames the provider sends ---------------------------------------------------------------

    @Test
    fun `audio is base64 in a JSON envelope, with the rate repeated on every chunk`() {
        // ElevenLabs multiplexes the commit flag onto the audio message, so audio and "that was the end
        // of an utterance" share one channel and a binary frame cannot carry both.
        assertEquals(
            buildJsonObject {
                put("message_type", "input_audio_chunk")
                put("audio_base_64", "AQID")
                put("commit", false)
                put("sample_rate", 16_000)
                put("previous_text", "Earlier context")
            },
            elevenLabsAudioChunk(byteArrayOf(1, 2, 3), 16_000, "Earlier context"),
        )
        // `previous_text` rides the FIRST chunk only: it is context for the first transcript, and
        // repeating it re-primes the decoder against text the speaker has long since moved past.
        assertEquals(
            buildJsonObject {
                put("message_type", "input_audio_chunk")
                put("audio_base_64", "BAUG")
                put("commit", false)
                put("sample_rate", 16_000)
            },
            elevenLabsAudioChunk(byteArrayOf(4, 5, 6), 16_000),
        )
    }

    @Test
    fun `the end of input is an empty chunk with the commit flag, not a socket close`() {
        // ElevenLabs finalizes on the commit, not on the close, so closing instead loses everything
        // said after the last automatic commit.
        assertEquals(
            buildJsonObject {
                put("message_type", "input_audio_chunk")
                put("audio_base_64", "")
                put("commit", true)
                put("sample_rate", 16_000)
            },
            elevenLabsCommit(16_000),
        )
    }

    // --- The recorded frame sequences ----------------------------------------------------------------

    private fun mapperWith(
        warnings: List<Warning> = emptyList(),
        includeTimestamps: Boolean = false,
        includeLanguageDetection: Boolean = false,
        includeRawChunks: Boolean = false,
        language: String? = null,
    ) = ElevenLabsRealtimeMapper(
        warnings = warnings,
        includeTimestamps = includeTimestamps,
        includeLanguageDetection = includeLanguageDetection,
        includeRawChunks = includeRawChunks,
        detectedLanguage = language,
    )

    private fun frame(text: String) = ElevenLabsRealtimeEvent.Frame(text)

    private fun ElevenLabsRealtimeMapper.play(vararg events: ElevenLabsRealtimeEvent) =
        events.flatMap { on(it) }

    private val sessionStarted =
        frame("""{"message_type":"session_started","session_id":"session-1","config":{}}""")

    @Test
    fun `a partial is superseded by the commit that settles it, under the same id`() {
        val mapper = mapperWith(includeTimestamps = true, includeLanguageDetection = true)

        val parts = mapper.play(
            sessionStarted,
            ElevenLabsRealtimeEvent.InputCommitted,
            frame("""{"message_type":"partial_transcript","text":"Hello wor"}"""),
            frame("""{"message_type":"committed_transcript","text":"Hello world."}"""),
            frame(
                """{"message_type":"committed_transcript_with_timestamps","text":"Hello world.",
                   "language_code":"en","words":[
                     {"text":"Hello","start":0,"end":0.4,"type":"word"},
                     {"text":" ","start":0.4,"end":0.45,"type":"spacing"},
                     {"text":"world.","start":0.45,"end":0.9,"type":"word"}]}""",
            ),
            ElevenLabsRealtimeEvent.GraceElapsed,
        )

        // The id is what tells a consumer the final REPLACES the partial rather than following it. Two
        // ids here would render "Hello wor" and "Hello world." one after the other.
        assertEquals(
            listOf(
                TranscriptionStreamPart.StreamStart(),
                TranscriptionStreamPart.TranscriptPartial(text = "Hello wor", id = "session-1:0"),
                TranscriptionStreamPart.TranscriptFinal(text = "Hello world.", id = "session-1:0"),
                TranscriptionStreamPart.Finish(
                    text = "Hello world.",
                    segments = listOf(
                        TranscriptionResult.Segment("Hello", 0.0, 0.4),
                        TranscriptionResult.Segment(" ", 0.4, 0.45),
                        TranscriptionResult.Segment("world.", 0.45, 0.9),
                    ),
                    language = "en",
                    durationInSeconds = 0.9,
                ),
            ),
            parts,
        )
    }

    @Test
    fun `the detected language survives even when the caller wanted no timestamps`() {
        val mapper = mapperWith(includeLanguageDetection = true)

        val parts = mapper.play(
            sessionStarted,
            ElevenLabsRealtimeEvent.InputCommitted,
            frame("""{"message_type":"committed_transcript","text":"Hola"}"""),
            frame(
                """{"message_type":"committed_transcript_with_timestamps","text":"Hola",
                   "language_code":"es","words":[{"text":"Hola","start":0,"end":0.4,"type":"word"}]}""",
            ),
            ElevenLabsRealtimeEvent.GraceElapsed,
        )

        // The timings arrived, because the language cannot be had without them, and are still dropped —
        // the caller did not ask for word timings and did not pay to have them rendered.
        assertEquals(
            TranscriptionStreamPart.Finish(text = "Hola", segments = emptyList(), language = "es"),
            parts.last(),
        )
    }

    @Test
    fun `a timestamped commit that arrives alone is still the transcript`() {
        val mapper = mapperWith(includeTimestamps = true)

        val parts = mapper.play(
            sessionStarted,
            ElevenLabsRealtimeEvent.InputCommitted,
            frame(
                """{"message_type":"committed_transcript_with_timestamps","text":"Hello",
                   "language_code":"en","words":[{"text":"Hello","start":0,"end":0.4,"type":"word"}]}""",
            ),
            ElevenLabsRealtimeEvent.GraceElapsed,
        )

        // Normally this event is the twin of a plain commit whose text is already final. When the server
        // sends only the timestamped form, dropping it as a duplicate loses the only copy of the text.
        assertEquals(
            listOf(
                TranscriptionStreamPart.StreamStart(),
                TranscriptionStreamPart.TranscriptFinal(
                    text = "Hello",
                    id = "session-1:0",
                    startSecond = 0.0,
                    endSecond = 0.4,
                ),
                TranscriptionStreamPart.Finish(
                    text = "Hello",
                    segments = listOf(TranscriptionResult.Segment("Hello", 0.0, 0.4)),
                    language = "en",
                    durationInSeconds = 0.4,
                ),
            ),
            parts,
        )
    }

    @Test
    fun `a commit arriving after the input ended does not finish the stream early`() {
        val mapper = mapperWith()

        val parts = mapper.play(
            sessionStarted,
            ElevenLabsRealtimeEvent.InputCommitted,
            frame("""{"message_type":"partial_transcript","text":"First"}"""),
            frame("""{"message_type":"committed_transcript","text":" First  "}"""),
            frame("""{"message_type":"partial_transcript","text":"Second"}"""),
            frame("""{"message_type":"committed_transcript","text":" Second "}"""),
            ElevenLabsRealtimeEvent.GraceElapsed,
        )

        // A server-initiated VAD commit can land just before the explicit final one. Finishing on the
        // first drops the second utterance entirely, and each segment gets its own id so a consumer can
        // tell "revise the last line" from "start a new one".
        assertEquals(
            listOf(
                TranscriptionStreamPart.StreamStart(),
                TranscriptionStreamPart.TranscriptPartial(text = "First", id = "session-1:0"),
                TranscriptionStreamPart.TranscriptFinal(text = "First", id = "session-1:0"),
                TranscriptionStreamPart.TranscriptPartial(text = "Second", id = "session-1:1"),
                TranscriptionStreamPart.TranscriptFinal(text = "Second", id = "session-1:1"),
                TranscriptionStreamPart.Finish(text = "First Second"),
            ),
            parts,
        )
    }

    @Test
    fun `the legacy final_transcript spelling is not followed by a timestamped twin`() {
        val mapper = mapperWith(includeTimestamps = true)

        val parts = mapper.play(
            sessionStarted,
            ElevenLabsRealtimeEvent.InputCommitted,
            frame("""{"message_type":"final_transcript","text":"Legacy final"}"""),
            frame(
                """{"message_type":"final_transcript_with_timestamps","text":"Legacy final",
                   "language_code":"en",
                   "words":[{"text":"Legacy final","start":0,"end":0.5,"type":"word"}]}""",
            ),
            ElevenLabsRealtimeEvent.GraceElapsed,
        )

        // Waiting for a twin that the legacy spelling never sends hangs the stream until the caller
        // gives up on a transcript that was already complete.
        assertEquals(
            listOf(
                TranscriptionStreamPart.StreamStart(),
                TranscriptionStreamPart.TranscriptFinal(text = "Legacy final", id = "session-1:0"),
                TranscriptionStreamPart.Finish(
                    text = "Legacy final",
                    segments = listOf(TranscriptionResult.Segment("Legacy final", 0.0, 0.5)),
                    language = "en",
                    durationInSeconds = 0.5,
                ),
            ),
            parts,
        )
    }

    @Test
    fun `a refusal of the final empty commit finishes the transcript instead of voiding it`() {
        val mapper = mapperWith()

        val parts = mapper.play(
            sessionStarted,
            frame("""{"message_type":"committed_transcript","text":"Already done"}"""),
            ElevenLabsRealtimeEvent.InputCommitted,
            frame("""{"message_type":"insufficient_audio_activity","error":"not enough audio"}"""),
        )

        // The commit that answers our end-of-input is an EMPTY chunk, so a recording that ended in
        // silence draws a refusal. Treating it as a failure voids a complete transcript because of how
        // the recording happened to end.
        assertEquals(
            listOf(
                TranscriptionStreamPart.StreamStart(),
                TranscriptionStreamPart.TranscriptFinal(text = "Already done", id = "session-1:0"),
                TranscriptionStreamPart.Finish(text = "Already done"),
            ),
            parts,
        )
    }

    @Test
    fun `a refusal before anything was committed is a real failure`() {
        val mapper = mapperWith()
        mapper.on(sessionStarted)

        val error = assertFailsWith<Throwable> {
            mapper.on(frame("""{"message_type":"quota_exceeded","error":"quota exhausted"}"""))
        }

        // The vendor's own wording, not a generic one: "quota exhausted" tells the caller to top up,
        // where "realtime transcription error" sends them to read logs.
        assertEquals("quota exhausted", error.message)
    }

    @Test
    fun `there is no error envelope, so an unknown message type is not silently awaited`() {
        val mapper = mapperWith()
        mapper.on(sessionStarted)

        // The failure IS the message type here. A switch that knows only the transcript types treats a
        // quota refusal as an unrecognized frame and waits forever for a transcript that is not coming.
        val error = assertFailsWith<Throwable> {
            mapper.on(frame("""{"message_type":"session_time_limit_exceeded"}"""))
        }
        assertContains(assertNotNull(error.message), "session_time_limit_exceeded")
    }

    @Test
    fun `a frame that is not JSON is skipped rather than discarding the transcript`() {
        val mapper = mapperWith()

        val parts = mapper.play(sessionStarted, frame("not json at all"))

        assertEquals(listOf(TranscriptionStreamPart.StreamStart()), parts)
    }

    @Test
    fun `a raw chunk is emitted before the part it was mapped into, when asked for`() {
        val mapper = mapperWith(includeRawChunks = true)

        val parts = mapper.play(sessionStarted)

        assertEquals(2, parts.size)
        assertTrue(parts.first() is TranscriptionStreamPart.Raw)
        assertTrue(parts.last() is TranscriptionStreamPart.StreamStart)
    }

    @Test
    fun `a close before any commit is an error, because audio was transcribed and thrown away`() {
        val mapper = mapperWith()
        mapper.on(sessionStarted)

        val error = assertFailsWith<Throwable> { mapper.onClose() }

        assertContains(assertNotNull(error.message), "closed before completion")
    }

    @Test
    fun `a close after the commit that answered our input keeps the transcript`() {
        val mapper = mapperWith(includeTimestamps = true)
        mapper.play(
            sessionStarted,
            ElevenLabsRealtimeEvent.InputCommitted,
            frame("""{"message_type":"committed_transcript","text":"Hello"}"""),
        )

        // The timestamped twin never arrived — the socket closed first. The committed text is complete
        // and settled, so discarding it because its word timings are missing loses the transcript over
        // an ornament the caller may not even have asked for.
        assertEquals(
            listOf(TranscriptionStreamPart.Finish(text = "Hello")),
            mapper.onClose(),
        )
    }

    @Test
    fun `finishing is idempotent, so a close after a finish adds nothing`() {
        val mapper = mapperWith()
        mapper.play(
            sessionStarted,
            ElevenLabsRealtimeEvent.InputCommitted,
            frame("""{"message_type":"committed_transcript","text":"Hi"}"""),
            ElevenLabsRealtimeEvent.GraceElapsed,
        )

        // A second `finish` carrying the same text is text a consumer appends.
        assertEquals(emptyList(), mapper.onClose())
    }
}
