package com.swaptr.aide.domain.speech.sherpa

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.speech.SpeechAssetCatalog
import com.swaptr.aide.data.speech.SpeechAssetFamily
import com.swaptr.aide.data.speech.SpeechAssetKind
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.data.speech.SpeechAssetStorage
import com.swaptr.aide.domain.speech.SpeechSynthesizerEngine
import com.swaptr.aide.domain.speech.TtsOptions
import com.swaptr.aide.domain.speech.TtsStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SherpaTtsEngine @Inject constructor(
    private val storage: SpeechAssetStorage,
    private val prefs: UserPreferencesRepository,
) : SpeechSynthesizerEngine {

    private val mutex = Mutex()
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var _loaded: String? = null
    @Volatile private var sampleRate: Int = 16_000

    override val loadedModelId: String? get() = _loaded
    override val supportsStreamingPcm: Boolean = true

    override suspend fun load(spec: SpeechAssetSpec) {
        require(spec.kind == SpeechAssetKind.TTS) { "expected TTS spec, got ${spec.kind}" }
        if (_loaded == spec.id && tts != null) return
        withContext(dispatcher) {
            mutex.withLock {
                releaseLocked()
                val dir = storage.extractedDir(spec)
                require(dir.isDirectory) { "TTS bundle not extracted: ${dir.absolutePath}" }
                val files = SherpaBundleResolver.resolveTts(dir, spec.family)
                val cfg = when (spec.family) {
                    SpeechAssetFamily.VITS -> buildVitsConfig(files)
                    SpeechAssetFamily.MATCHA -> buildMatchaConfig(files)
                    SpeechAssetFamily.KOKORO -> buildKokoroConfig(files)
                    SpeechAssetFamily.KITTEN -> buildKittenConfig(files)
                    else -> error("Unsupported TTS family: ${spec.family}")
                }
                val engine = OfflineTts(assetManager = null, config = cfg)
                tts = engine
                sampleRate = engine.sampleRate()
                _loaded = spec.id
                Log.i(TAG, "loaded ${spec.id} family=${spec.family} sampleRate=$sampleRate")
            }
        }
    }

    private fun buildVitsConfig(f: SherpaBundleResolver.TtsFiles) = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = f.model.absolutePath,
                lexicon = f.lexicon,
                tokens = f.tokens.absolutePath,
                dataDir = f.espeakDataDir,
                dictDir = f.dictDir,
            ),
            numThreads = 2,
            provider = "cpu",
        ),
    )

    private fun buildMatchaConfig(f: SherpaBundleResolver.TtsFiles) = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            matcha = OfflineTtsMatchaModelConfig(
                acousticModel = f.model.absolutePath,
                vocoder = f.vocoder!!.absolutePath,
                lexicon = f.lexicon,
                tokens = f.tokens.absolutePath,
                dataDir = f.espeakDataDir,
                dictDir = f.dictDir,
            ),
            numThreads = 2,
            provider = "cpu",
        ),
    )

    private fun buildKokoroConfig(f: SherpaBundleResolver.TtsFiles) = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = f.model.absolutePath,
                voices = f.voices!!.absolutePath,
                tokens = f.tokens.absolutePath,
                dataDir = f.espeakDataDir,
                lexicon = f.lexicon,
                lang = "",
                dictDir = f.dictDir,
                lengthScale = 1.0f,
            ),
            numThreads = 2,
            provider = "cpu",
        ),
    )

    private fun buildKittenConfig(f: SherpaBundleResolver.TtsFiles) = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            kitten = OfflineTtsKittenModelConfig(
                model = f.model.absolutePath,
                voices = f.voices!!.absolutePath,
                tokens = f.tokens.absolutePath,
                dataDir = f.espeakDataDir,
                lengthScale = 1.0f,
            ),
            numThreads = 2,
            provider = "cpu",
        ),
    )

    override fun synthesize(text: String, options: TtsOptions): Flow<TtsStreamEvent> = callbackFlow {
        loadActiveIfNeeded()
        val engine = tts ?: run {
            trySend(TtsStreamEvent.Error("Sherpa TTS failed to load"))
            close()
            return@callbackFlow
        }
        val sid = options.voiceId?.toIntOrNull() ?: 0
        val rate = options.rate
        val sr = sampleRate
        // Named local fn (not lambda): K2+R8 strips the boxed-Integer JNI bridge from lambdas,
        // crashing the Sherpa native callback with NoSuchMethodError. Method-ref pattern keeps it.
        fun onSamples(samples: FloatArray): Int {
            val sent = trySend(TtsStreamEvent.AudioChunk(samples.copyOf(), sr)).isSuccess
            return if (sent) 1 else 0
        }
        val job = launch(dispatcher) {
            try {
                Log.d(TAG, "synthesize() start sid=$sid rate=$rate chars=${text.length}")
                engine.generateWithCallback(text, sid, rate, ::onSamples)
                Log.d(TAG, "synthesize() done")
                trySend(TtsStreamEvent.Completed)
            } catch (t: Throwable) {
                Log.w(TAG, "synthesize() failed", t)
                trySend(TtsStreamEvent.Error(t.message ?: "synthesize() failed", t))
            } finally {
                close()
            }
        }
        awaitClose { job.cancel() }
    }.flowOn(dispatcher)

    private suspend fun loadActiveIfNeeded() {
        val activeId = prefs.activeTtsModelIdFlow.first()
        val pick = activeId?.let(SpeechAssetCatalog::findById)?.takeIf { storage.hasExtracted(it) }
            ?: SpeechAssetCatalog.ttsAssets.firstOrNull { storage.hasExtracted(it) }
            ?: SpeechAssetCatalog.piperEnAmyTts
        if (_loaded == pick.id && tts != null) return
        load(pick)
    }

    override fun close() {
        runCatching { releaseLocked() }
    }

    private fun releaseLocked() {
        runCatching { tts?.release() }
        tts = null
        _loaded = null
    }

    companion object {
        private const val TAG = "SherpaTts"
    }
}
