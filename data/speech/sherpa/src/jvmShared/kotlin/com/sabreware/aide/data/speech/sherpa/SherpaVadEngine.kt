package com.sabreware.aide.data.speech.sherpa

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.VadEngine
import com.sabreware.aide.core.domain.speech.VadEvent
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.speech.SpeechAssetStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Window size locked to 512 samples (32 ms @ 16 kHz) — matches Silero v5 default and
// AudioCapturer's frame size, so no buffering is required.
class SherpaVadEngine(
    private val storage: SpeechAssetStorage,
    private val ioDispatcher: CoroutineDispatcher,
) : VadEngine {

    private val mutex = Mutex()
    private val dispatcher = ioDispatcher.limitedParallelism(1)
    @Volatile private var vad: Vad? = null
    @Volatile private var _loaded: String? = null

    override val loadedModelId: String? get() = _loaded
    override val frameSize: Int = WINDOW_SIZE

    override suspend fun load(spec: SpeechAssetSpec) {
        require(spec.modality == Modality.Vad) { "expected VAD spec, got ${spec.modality}" }
        if (_loaded == spec.id && vad != null) return
        withContext(dispatcher) {
            mutex.withLock {
                releaseLocked()
                val onnx = storage.assetFile(spec).toFile()
                require(onnx.exists()) { "VAD model missing: ${onnx.absolutePath}" }
                val cfg = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = onnx.absolutePath,
                        // 0.5 thr avoids splitting on breath (hallucination triggers).
                        // 0.8s silence allows mid-sentence breathing; 0.05s catches "yes"/"no".
                        threshold = 0.5f,
                        minSilenceDuration = 0.8f,
                        minSpeechDuration = 0.05f,
                        windowSize = WINDOW_SIZE,
                        // MUST be set: sherpa-onnx 1.13.2 defaults this to 5s, which force-ends the
                        // segment mid-sentence and cuts dictation off at five seconds.
                        maxSpeechDuration = MAX_SPEECH_SECONDS,
                    ),
                    sampleRate = 16_000,
                    numThreads = 1,
                    provider = "cpu",
                )
                vad = Vad(assetManager = null, config = cfg)
                _loaded = spec.id
                AideLog.i(TAG, "loaded ${spec.id}")
            }
        }
    }

    override fun process(audio: Flow<FloatArray>): Flow<VadEvent> = flow {
        val v = vad ?: run {
            return@flow
        }
        var lastSpeech = false
        audio.collect { samples ->
            v.acceptWaveform(samples)
            val speech = v.isSpeechDetected()
            if (speech && !lastSpeech) emit(VadEvent.SpeechStart)
            else if (!speech && lastSpeech) emit(VadEvent.SpeechEnd)
            else if (speech) emit(VadEvent.SpeechContinues)
            lastSpeech = speech
        }
    }.flowOn(dispatcher)

    @Volatile private var feedLastSpeech: Boolean = false

    suspend fun feed(samples: FloatArray): VadEvent? = withContext(dispatcher) {
        mutex.withLock {
            val v = vad ?: return@withLock null
            v.acceptWaveform(samples)
            val speech = v.isSpeechDetected()
            val event = when {
                speech && !feedLastSpeech -> VadEvent.SpeechStart
                !speech && feedLastSpeech -> VadEvent.SpeechEnd
                speech -> VadEvent.SpeechContinues
                else -> null
            }
            feedLastSpeech = speech
            event
        }
    }

    fun resetFeed() {
        feedLastSpeech = false
        runCatching { vad?.reset() }
    }

    /**
     * Runs on the engine's single-permit dispatcher, under the same [mutex] every load and decode takes.
     *
     * This used to free the native handles straight off the caller's thread. The class comment above
     * already stated the invariant — "every call runs on a single-permit IO dispatcher; concurrent
     * callers race through mutex" — and close was the one call that did neither, so a keepAlive expiry
     * or a memory-trim eviction could `release()` a recogniser while a decode was still inside it.
     * That is a SIGSEGV, not an exception.
     */
    override suspend fun close() = withContext(dispatcher) {
        mutex.withLock { releaseLocked() }
    }

    private fun releaseLocked() {
        runCatching { vad?.release() }
        vad = null
        _loaded = null
    }

    companion object {
        private const val TAG = "SherpaVad"
        private const val WINDOW_SIZE = 512
        // A whole dictated utterance, not sherpa's 5s default.
        private const val MAX_SPEECH_SECONDS = 30f
    }
}
