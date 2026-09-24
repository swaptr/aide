package com.sabreware.aide.data.speech.cloud

import com.sabreware.aide.core.domain.audio.AudioTranscriptionEngine
import com.sabreware.aide.core.domain.audio.Transcript
import com.sabreware.aide.core.domain.audio.TranscriptionOptions
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SttOptions
import com.sabreware.aide.core.domain.speech.SttStreamEvent
import com.sabreware.aide.data.speech.audio.WavCodec
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

private const val FRAME = 512

class CloudSttEngineTest {

    private class RecordingTranscription(
        private val answer: String = "hello there",
        private val fail: Throwable? = null,
    ) : AudioTranscriptionEngine {
        var calls = 0
        var lastModel: String? = null
        var lastAudio: ByteArray? = null
        var lastMediaType: String? = null
        var lastOptions: TranscriptionOptions? = null

        override suspend fun transcribe(
            modelName: String,
            audio: ByteArray,
            mediaType: String,
            options: TranscriptionOptions,
        ): Transcript {
            calls++
            lastModel = modelName
            lastAudio = audio
            lastMediaType = mediaType
            lastOptions = options
            fail?.let { throw it }
            return Transcript(text = " $answer ")
        }
    }

    private fun silence() = FloatArray(FRAME) { 0.0005f * if (it % 2 == 0) 1 else -1 }
    private fun speech() = FloatArray(FRAME) { (0.4 * sin(2 * PI * it / 40)).toFloat() }

    /** 30 frames of room tone, 20 of speech, then 40 of silence — a complete utterance. */
    private fun utterance(): List<FloatArray> =
        List(30) { silence() } + List(20) { speech() } + List(40) { silence() }

    private fun engine(transcription: AudioTranscriptionEngine, model: String = "gpt-4o-mini-transcribe") =
        CloudSttEngine(transcription, { model }, MicActivityMonitor())

    @Test
    fun `an utterance is uploaded as 16 kHz WAV and answered with one Final then End`() = runTest {
        val transcription = RecordingTranscription()

        val events = engine(transcription).recognize(utterance().asFlow(), SttOptions(locale = "en-US")).toList()

        assertEquals(listOf(SttStreamEvent.Final("hello there"), SttStreamEvent.End(SpeechStreamOutcome.Done)), events)
        assertEquals(1, transcription.calls)
        assertEquals("gpt-4o-mini-transcribe", transcription.lastModel)
        assertEquals("audio/wav", transcription.lastMediaType)
        // The full tag: the vendor-facing engine shortens it for the ISO-639-1 vendors.
        assertEquals("en-US", transcription.lastOptions?.language)
        val decoded = WavCodec.decode(transcription.lastAudio!!)
        assertEquals(16_000, decoded.sampleRate)
        // Only the frames up to the endpoint were sent: the 40 trailing silent frames end at the hangover.
        assertTrue(decoded.samples.size in 50 * FRAME..(50 + 30) * FRAME, "sent ${decoded.samples.size} samples")
    }

    @Test
    fun `the mic is released before the upload starts`() = runTest {
        val transcription = RecordingTranscription()
        var framesPulled = 0
        val endless = flow {
            utterance().forEach { emit(it); framesPulled++ }
            // A real capturer never completes; keep feeding silence until the engine unsubscribes.
            while (true) { emit(silence()); framesPulled++; yield() }
        }

        engine(transcription).recognize(endless, SttOptions()).toList()

        assertEquals(1, transcription.calls)
        assertTrue(framesPulled < 200, "kept pulling the mic after the endpoint: $framesPulled frames")
    }

    @Test
    fun `the duration cap ends an utterance the endpointer never would`() = runTest {
        val transcription = RecordingTranscription()
        // A few frames of room tone first, as the capturer's preroll guarantees: the detector seeds its
        // noise floor from the opening frames, and speech from the very first sample would never read
        // as speech against a floor set at speech level.
        val endlessSpeech: Flow<FloatArray> = flow {
            repeat(5) { emit(silence()) }
            while (true) { emit(speech()); yield() }
        }

        val events = engine(transcription)
            .recognize(endlessSpeech, SttOptions(maxDurationMs = 500))
            .toList()

        assertEquals(SttStreamEvent.End(SpeechStreamOutcome.Done), events.last())
        val sent = WavCodec.decode(transcription.lastAudio!!).samples.size
        assertTrue(sent in 8_000..(8_000 + FRAME), "500 ms is 8000 samples; sent $sent")
    }

    @Test
    fun `a capped recording in which nobody spoke is not uploaded`() = runTest {
        val transcription = RecordingTranscription()
        val roomTone: Flow<FloatArray> = flow { while (true) { emit(silence()); yield() } }

        val events = engine(transcription)
            .recognize(roomTone, SttOptions(maxDurationMs = 500))
            .toList()

        // The user walked away: a minute of room tone is not something to pay a vendor to transcribe.
        assertEquals(listOf(SttStreamEvent.Final(""), SttStreamEvent.End(SpeechStreamOutcome.Done)), events)
        assertEquals(0, transcription.calls)
    }

    @Test
    fun `an empty capture answers an empty Final without calling the vendor`() = runTest {
        val transcription = RecordingTranscription()

        val events = engine(transcription).recognize(emptyList<FloatArray>().asFlow(), SttOptions()).toList()

        assertEquals(listOf(SttStreamEvent.Final(""), SttStreamEvent.End(SpeechStreamOutcome.Done)), events)
        assertEquals(0, transcription.calls)
    }

    @Test
    fun `a vendor failure ends the stream as an Error with no Final`() = runTest {
        val transcription = RecordingTranscription(fail = IllegalStateException("401 invalid key"))

        val events = engine(transcription).recognize(utterance().asFlow(), SttOptions()).toList()

        assertEquals(1, events.size)
        val outcome = (events.single() as SttStreamEvent.End).outcome as SpeechStreamOutcome.Error
        assertEquals("401 invalid key", outcome.message)
    }

    @Test
    fun `cancellation mid-capture makes no network call`() = runTest {
        val transcription = RecordingTranscription()
        val endless: Flow<FloatArray> = flow { while (true) { emit(silence()); yield() } }
        val job = launch {
            engine(transcription).recognize(endless, SttOptions()).collect { }
        }
        yield()

        job.cancel()
        job.join()

        assertEquals(0, transcription.calls)
    }

    @Test
    fun `cancellation during the upload propagates rather than becoming an Error`() = runTest {
        val cancelling = object : AudioTranscriptionEngine {
            override suspend fun transcribe(
                modelName: String,
                audio: ByteArray,
                mediaType: String,
                options: TranscriptionOptions,
            ): Transcript = throw CancellationException("stopped")
        }

        assertFailsWith<CancellationException> {
            engine(cancelling).recognize(utterance().asFlow(), SttOptions()).toList()
        }
    }

    @Test
    fun `nothing is resident`() {
        val engine = engine(RecordingTranscription())
        assertNull(engine.loadedModelId)
        assertEquals(false, engine.isStreaming)
        assertEquals(false, engine.ownsAudioInput)
    }
}
