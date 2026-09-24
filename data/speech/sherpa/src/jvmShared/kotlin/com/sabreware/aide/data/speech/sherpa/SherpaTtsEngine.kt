package com.sabreware.aide.data.speech.sherpa

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.activeModelFor
import com.sabreware.aide.core.domain.speech.SpeechAssetFamily
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SpeechSynthesizerEngine
import com.sabreware.aide.core.domain.speech.TtsOptions
import com.sabreware.aide.core.domain.speech.TtsStreamEvent
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.speech.SpeechAssetCatalog
import com.sabreware.aide.data.speech.SpeechAssetStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SherpaTtsEngine(
    private val storage: SpeechAssetStorage,
    private val selection: ModelSelectionStore,
    private val ioDispatcher: CoroutineDispatcher,
) : SpeechSynthesizerEngine {

    private val mutex = Mutex()
    private val dispatcher = ioDispatcher.limitedParallelism(1)
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var _loaded: String? = null
    @Volatile private var sampleRate: Int = 16_000

    override val loadedModelId: String? get() = _loaded
    override val supportsStreamingPcm: Boolean = true

    override suspend fun load(spec: SpeechAssetSpec) {
        require(spec.modality == Modality.Tts) { "expected TTS spec, got ${spec.modality}" }
        if (_loaded == spec.id && tts != null) return
        withContext(dispatcher) {
            mutex.withLock {
                releaseLocked()
                val dir = storage.extractedDir(spec).toFile()
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
                AideLog.i(TAG, "loaded ${spec.id} family=${spec.family} sampleRate=$sampleRate")
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
            trySend(TtsStreamEvent.End(SpeechStreamOutcome.Error("Sherpa TTS failed to load")))
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
                AideLog.d(TAG, "synthesize() start sid=$sid rate=$rate chars=${text.length}")
                engine.generateWithCallback(text, sid, rate, ::onSamples)
                AideLog.d(TAG, "synthesize() done")
                trySend(TtsStreamEvent.End(SpeechStreamOutcome.Done))
            } catch (t: Throwable) {
                AideLog.w(TAG, "synthesize() failed", t)
                trySend(TtsStreamEvent.End(SpeechStreamOutcome.Error(t.message ?: "synthesize() failed", t)))
            } finally {
                close()
            }
        }
        awaitClose { job.cancel() }
    }.flowOn(dispatcher)

    private suspend fun loadActiveIfNeeded() {
        val activeId = selection.activeModelFor(Modality.Tts).first()
        val pick = activeId?.let(SpeechAssetCatalog::findById)?.takeIf { storage.hasExtracted(it) }
            ?: SpeechAssetCatalog.ttsAssets.firstOrNull { storage.hasExtracted(it) }
            ?: SpeechAssetCatalog.piperEnAmyTts
        if (_loaded == pick.id && tts != null) return
        load(pick)
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
        runCatching { tts?.release() }
        tts = null
        _loaded = null
    }

    companion object {
        private const val TAG = "SherpaTts"
    }
}
