package com.sabreware.aide.data.speech.cloud

import com.sabreware.aide.core.domain.audio.AudioTranscriptionEngine
import com.sabreware.aide.core.domain.audio.TranscriptionOptions
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.SpeechRecognizerEngine
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SttOptions
import com.sabreware.aide.core.domain.speech.SttStreamEvent
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.speech.audio.WavCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Speech to text over a cloud transcription endpoint, in the shape the speech ladder speaks.
 *
 * The conversation-shaped port wants a live PCM stream in and a `Final` out; the vendor wants one finished
 * recording. This is the adapter between them: buffer the capturer's 16 kHz frames, let the [Endpointer]
 * say when the utterance is over, upload it as WAV, emit exactly one [SttStreamEvent.Final] and then the
 * terminal [SttStreamEvent.End] — the contract Sherpa's offline recognisers already honour, so every
 * consumer of the repository sees the same stream whichever engine answered.
 *
 * Nothing is loaded and nothing is resident: `load()` is a no-op like the system engine's, and
 * [loadedModelId] is null because there is no handle to report. [modelName] is asked per utterance, so a
 * model picked in Settings takes effect on the next tap rather than the next launch.
 *
 * Cancellation makes NO network call. A capturer's frame flow never completes on its own (`stop()`
 * cancels the pump), so a stop-tap reaches this engine as cancellation of the collector — and by then the
 * consumer has already been resumed, so a transcript produced under `NonCancellable` would be billed and
 * delivered to nobody.
 */
class CloudSttEngine(
    private val transcription: AudioTranscriptionEngine,
    private val modelName: suspend () -> String,
    private val micActivity: MicActivityMonitor,
    private val endpointer: () -> Endpointer = { Endpointer() },
) : SpeechRecognizerEngine {

    override val loadedModelId: String? get() = null
    override val isStreaming: Boolean = false

    override suspend fun load(spec: SpeechAssetSpec) = Unit

    override fun recognize(audio: Flow<FloatArray>, options: SttOptions): Flow<SttStreamEvent> = flow {
        val detector = endpointer()
        val buffer = ArrayList<FloatArray>(INITIAL_FRAMES)
        var total = 0
        val maxSamples = options.maxDurationMs?.let { it * SAMPLE_RATE / MS_PER_SECOND }
        var outcome: SpeechStreamOutcome = SpeechStreamOutcome.Done
        try {
            try {
                audio.collect { frame ->
                    buffer += frame
                    total += frame.size
                    val verdict = detector.accept(frame)
                    // Our verdict is the better meter signal than the capturer's per-frame energy: it
                    // carries the hangover, so the bars do not flicker between syllables.
                    micActivity.onVoiceActivity(
                        verdict == Endpointer.Verdict.SpeechStart || verdict == Endpointer.Verdict.Speech,
                    )
                    val capped = maxSamples != null && total >= maxSamples
                    // Throw to unwind the audio flow's upstream pump; the transcription follows outside
                    // the collect so the mic is released before the network round trip starts.
                    if (verdict == Endpointer.Verdict.Utterance || capped) throw UtteranceComplete
                }
            } catch (_: UtteranceComplete) {
                // Expected: the endpointer or the cap ended the utterance.
            }
            // A capped recording in which nobody spoke — the user walked away from the assistant — is
            // not uploaded: paying a vendor to transcribe a minute of room tone answers nothing.
            emit(if (detector.speaking) transcribe(buffer, total, options) else SttStreamEvent.Final(""))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AideLog.w(TAG, "cloud recognize() failed", t)
            outcome = SpeechStreamOutcome.Error(t.message ?: "recognize() failed", t)
        }
        emit(SttStreamEvent.End(outcome))
    }

    private suspend fun transcribe(buffer: List<FloatArray>, total: Int, options: SttOptions): SttStreamEvent.Final {
        if (total == 0) return SttStreamEvent.Final("")
        val samples = FloatArray(total)
        var offset = 0
        for (frame in buffer) {
            frame.copyInto(samples, offset)
            offset += frame.size
        }
        val transcript = transcription.transcribe(
            modelName = modelName(),
            audio = WavCodec.encode(samples, SAMPLE_RATE),
            mediaType = WAV_MEDIA_TYPE,
            // The full tag: the vendor-facing engine shortens it for the vendors that take ISO-639-1 and
            // keeps it for the one that wants BCP-47. A hint spares the detection pass most of them bill.
            options = TranscriptionOptions(language = options.locale.takeIf { it.isNotBlank() }),
        )
        AideLog.d(TAG, "cloud transcript: ${transcript.text.length} chars from $total samples")
        return SttStreamEvent.Final(transcript.text.trim())
    }

    override suspend fun close() = Unit

    /** Control flow, not an error: unwinds the audio flow's upstream pump once the utterance is complete. */
    private object UtteranceComplete : RuntimeException()

    private companion object {
        const val TAG = "CloudStt"
        const val SAMPLE_RATE = 16_000
        const val MS_PER_SECOND = 1000
        const val INITIAL_FRAMES = 256
        const val WAV_MEDIA_TYPE = "audio/wav"
    }
}
