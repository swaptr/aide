package com.swaptr.aide.domain.speech.sherpa

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.swaptr.aide.data.speech.SpeechAssetKind
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.data.speech.SpeechAssetStorage
import com.swaptr.aide.domain.speech.VadEngine
import com.swaptr.aide.domain.speech.VadEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Window size locked to 512 samples (32 ms @ 16 kHz) — matches Silero v5 default and
// AudioCapturer's frame size, so no buffering is required.
@Singleton
class SherpaVadEngine @Inject constructor(
    private val storage: SpeechAssetStorage,
) : VadEngine {

    private val mutex = Mutex()
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var vad: Vad? = null
    @Volatile private var _loaded: String? = null

    override val loadedModelId: String? get() = _loaded
    override val frameSize: Int = WINDOW_SIZE

    override suspend fun load(spec: SpeechAssetSpec) {
        require(spec.kind == SpeechAssetKind.VAD) { "expected VAD spec, got ${spec.kind}" }
        if (_loaded == spec.id && vad != null) return
        withContext(dispatcher) {
            mutex.withLock {
                releaseLocked()
                val onnx = storage.assetFile(spec)
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
                    ),
                    sampleRate = 16_000,
                    numThreads = 1,
                    provider = "cpu",
                )
                vad = Vad(assetManager = null, config = cfg)
                _loaded = spec.id
                Log.i(TAG, "loaded ${spec.id}")
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

    override fun close() {
        runCatching { releaseLocked() }
    }

    private fun releaseLocked() {
        runCatching { vad?.release() }
        vad = null
        _loaded = null
    }

    companion object {
        private const val TAG = "SherpaVad"
        private const val WINDOW_SIZE = 512
    }
}
