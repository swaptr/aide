package com.sabreware.aide.data.speech.cloud

import com.sabreware.aide.core.domain.audio.AudioSynthesisEngine
import com.sabreware.aide.core.domain.audio.AudioSynthesisOptions
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SpeechSynthesizerEngine
import com.sabreware.aide.core.domain.speech.TtsOptions
import com.sabreware.aide.core.domain.speech.TtsStreamEvent
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.speech.audio.WavCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Text to speech over a cloud synthesis endpoint, in the shape the speech ladder speaks.
 *
 * The vendor answers with one finished recording; the players want a stream of float PCM chunks. This
 * asks for the PCM form the vendor documents ([CloudTtsFormat]), decodes it, and emits it in ~40 ms
 * chunks so the first sample reaches the player as soon as the body has been decoded rather than after
 * one giant write. [supportsStreamingPcm] is therefore true: the voice output channel routes these
 * chunks through the audio player, exactly as it does Sherpa's.
 *
 * **The shared voice preference is ignored.** `SpeechPrefs.PreferredTtsVoiceId` holds whatever the
 * ACTIVE engine understands — a Sherpa speaker number, an Android `Voice.getName()` — and forwarding
 * either to a vendor is a 400 on every sentence. Until a per-provider voice preference exists, [voice]
 * is the vendor's own default, injected by whoever wires the vendor.
 */
class CloudTtsEngine(
    private val synthesis: AudioSynthesisEngine,
    private val modelName: suspend () -> String,
    private val format: CloudTtsFormat,
    private val voice: String?,
    private val chunkMs: Int = DEFAULT_CHUNK_MS,
) : SpeechSynthesizerEngine {

    override val loadedModelId: String? get() = null
    override val supportsStreamingPcm: Boolean = true

    override suspend fun load(spec: SpeechAssetSpec) = Unit

    override fun synthesize(text: String, options: TtsOptions): Flow<TtsStreamEvent> = flow {
        var outcome: SpeechStreamOutcome = SpeechStreamOutcome.Done
        try {
            val audio = synthesis.synthesize(
                modelName = modelName(),
                text = text,
                options = AudioSynthesisOptions(
                    voice = voice,
                    outputFormat = format.outputFormat,
                    // The vendor's natural pace is its default; sending 1.0 explicitly makes some of them
                    // emit an empty settings object that overrides the voice's own tuning.
                    speed = options.rate.toDouble().takeIf { options.rate != NATURAL_RATE },
                ),
            )
            when (format.container) {
                // Decoded window by window straight from the vendor's bytes: a ten-second sentence is a
                // megabyte of floats, and materialising it before the first chunk doubles the memory and
                // delays the first sample by the whole decode.
                PcmContainer.Pcm16 -> emitPcm16Chunks(audio.bytes, format.sampleRate)
                // No vendor wired today answers in a container; the decode is one pass and rare.
                PcmContainer.Wav -> {
                    val decoded = WavCodec.decode(audio.bytes)
                    emitChunks(decoded.samples, decoded.sampleRate)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AideLog.w(TAG, "cloud synthesize() failed", t)
            outcome = SpeechStreamOutcome.Error(t.message ?: "synthesize() failed", t)
        }
        emit(TtsStreamEvent.End(outcome))
    }

    private suspend fun FlowCollector<TtsStreamEvent>.emitPcm16Chunks(bytes: ByteArray, sampleRate: Int) {
        val usable = bytes.size - bytes.size % WavCodec.PCM16_BYTES_PER_SAMPLE
        if (usable == 0) throw IllegalStateException("The voice returned no audio")
        val stepBytes = samplesPerChunk(sampleRate) * WavCodec.PCM16_BYTES_PER_SAMPLE
        var offset = 0
        while (offset < usable) {
            val end = minOf(offset + stepBytes, usable)
            emit(TtsStreamEvent.AudioChunk(WavCodec.pcm16ToFloat(bytes, offset, end), sampleRate))
            offset = end
        }
    }

    private suspend fun FlowCollector<TtsStreamEvent>.emitChunks(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) throw IllegalStateException("The voice returned no audio")
        val step = samplesPerChunk(sampleRate)
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + step, samples.size)
            emit(TtsStreamEvent.AudioChunk(samples.copyOfRange(offset, end), sampleRate))
            offset = end
        }
    }

    private fun samplesPerChunk(sampleRate: Int): Int = (sampleRate * chunkMs / MS_PER_SECOND).coerceAtLeast(1)

    override suspend fun close() = Unit

    private companion object {
        const val TAG = "CloudTts"
        const val DEFAULT_CHUNK_MS = 40
        const val MS_PER_SECOND = 1000
        const val NATURAL_RATE = 1.0f
    }
}
