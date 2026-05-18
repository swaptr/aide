package com.swaptr.aide.domain.speech.sherpa

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.speech.SpeechAssetCatalog
import com.swaptr.aide.data.speech.SpeechAssetFamily
import com.swaptr.aide.data.speech.SpeechAssetKind
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.data.speech.SpeechAssetStorage
import com.swaptr.aide.domain.speech.SpeechRecognizerEngine
import com.swaptr.aide.domain.speech.SttOptions
import com.swaptr.aide.domain.speech.SttStreamEvent
import com.swaptr.aide.domain.speech.VadEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Sherpa recognisers + streams are NOT thread-safe — every call runs on a single-permit
// IO dispatcher; concurrent load/recognize callers race through mutex.
@Singleton
class SherpaSttEngine @Inject constructor(
    private val storage: SpeechAssetStorage,
    private val prefs: UserPreferencesRepository,
    private val vadEngine: SherpaVadEngine,
) : SpeechRecognizerEngine {

    private val mutex = Mutex()
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var online: OnlineRecognizer? = null
    @Volatile private var offline: OfflineRecognizer? = null
    @Volatile private var _loaded: String? = null
    @Volatile private var loadedFamily: SpeechAssetFamily? = null

    override val loadedModelId: String? get() = _loaded
    override val isStreaming: Boolean
        get() = loadedFamily?.streaming == true

    override suspend fun load(spec: SpeechAssetSpec) {
        require(spec.kind == SpeechAssetKind.STT) { "expected STT spec, got ${spec.kind}" }
        if (_loaded == spec.id && (online != null || offline != null)) return
        withContext(dispatcher) {
            mutex.withLock {
                releaseLocked()
                val dir = storage.extractedDir(spec)
                require(dir.isDirectory) { "STT bundle not extracted: ${dir.absolutePath}" }
                val files = SherpaBundleResolver.resolveStt(dir, spec.family)
                when (spec.family) {
                    SpeechAssetFamily.ZIPFORMER_STREAMING -> online = buildZipformer(files)
                    SpeechAssetFamily.WHISPER -> offline = buildWhisper(files)
                    SpeechAssetFamily.MOONSHINE -> offline = buildMoonshine(files)
                    SpeechAssetFamily.SENSE_VOICE -> offline = buildSenseVoice(files)
                    SpeechAssetFamily.NEMO_CTC -> offline = buildNemoCtc(files)
                    SpeechAssetFamily.NEMO_TRANSDUCER -> offline = buildNemoTransducer(files)
                    SpeechAssetFamily.CANARY -> offline = buildCanary(files)
                    else -> error("Unsupported STT family: ${spec.family}")
                }
                _loaded = spec.id
                loadedFamily = spec.family
                Log.i(TAG, "loaded ${spec.id} family=${spec.family}")
            }
        }
    }

    private fun buildZipformer(f: SherpaBundleResolver.SttFiles): OnlineRecognizer {
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = f.encoder!!.absolutePath,
                    decoder = f.decoder!!.absolutePath,
                    joiner = f.joiner!!.absolutePath,
                ),
                tokens = f.tokens.absolutePath,
                numThreads = 2,
                modelType = "zipformer2",
                provider = "cpu",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.4f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
            enableEndpoint = true,
        )
        return OnlineRecognizer(assetManager = null, config = cfg)
    }

    private fun buildWhisper(f: SherpaBundleResolver.SttFiles): OfflineRecognizer =
        OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = f.encoder!!.absolutePath,
                        decoder = f.decoder!!.absolutePath,
                        language = "",
                        task = "transcribe",
                        tailPaddings = 1000,
                        enableTokenTimestamps = false,
                        enableSegmentTimestamps = false,
                    ),
                    tokens = f.tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )

    private fun buildMoonshine(f: SherpaBundleResolver.SttFiles): OfflineRecognizer =
        OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        preprocessor = f.preprocessor!!.absolutePath,
                        encoder = f.encoder!!.absolutePath,
                        uncachedDecoder = f.uncachedDecoder!!.absolutePath,
                        cachedDecoder = f.cachedDecoder!!.absolutePath,
                        mergedDecoder = "",
                    ),
                    tokens = f.tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )

    private fun buildSenseVoice(f: SherpaBundleResolver.SttFiles): OfflineRecognizer =
        OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = f.model!!.absolutePath,
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = f.tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )

    private fun buildNemoCtc(f: SherpaBundleResolver.SttFiles): OfflineRecognizer =
        OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = f.model!!.absolutePath),
                    tokens = f.tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )

    private fun buildNemoTransducer(f: SherpaBundleResolver.SttFiles): OfflineRecognizer =
        OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = f.encoder!!.absolutePath,
                        decoder = f.decoder!!.absolutePath,
                        joiner = f.joiner!!.absolutePath,
                    ),
                    tokens = f.tokens.absolutePath,
                    numThreads = 2,
                    modelType = "nemo_transducer",
                    provider = "cpu",
                ),
            ),
        )

    private fun buildCanary(f: SherpaBundleResolver.SttFiles): OfflineRecognizer =
        OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    canary = OfflineCanaryModelConfig(
                        encoder = f.encoder!!.absolutePath,
                        decoder = f.decoder!!.absolutePath,
                        srcLang = "en",
                        tgtLang = "en",
                        usePnc = true,
                    ),
                    tokens = f.tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )

    override suspend fun warmUp() {
        runCatching { loadActiveIfNeeded() }
        runCatching { ensureVadLoaded() }
    }

    override fun recognize(audio: Flow<FloatArray>, options: SttOptions): Flow<SttStreamEvent> = flow {
        loadActiveIfNeeded()
        val streaming = online
        val batch = offline
        when {
            streaming != null -> recognizeOnline(streaming, audio).collect { emit(it) }
            batch != null -> recognizeOffline(batch, audio).collect { emit(it) }
            else -> {
                emit(SttStreamEvent.Error("Sherpa STT failed to load"))
                emit(SttStreamEvent.Completed)
            }
        }
    }.flowOn(dispatcher)

    private suspend fun loadActiveIfNeeded() {
        val activeId = prefs.activeSttModelIdFlow.first()
        val pick = activeId?.let(SpeechAssetCatalog::findById)?.takeIf { storage.hasExtracted(it) }
            ?: SpeechAssetCatalog.sttAssets.firstOrNull { storage.hasExtracted(it) }
            ?: SpeechAssetCatalog.zipformerEnStt
        if (_loaded == pick.id && (online != null || offline != null)) return
        load(pick)
    }

    private fun recognizeOnline(live: OnlineRecognizer, audio: Flow<FloatArray>): Flow<SttStreamEvent> = flow {
        val stream = live.createStream("")
        try {
            audio.collect { samples ->
                stream.acceptWaveform(samples, 16_000)
                while (live.isReady(stream)) live.decode(stream)
                val result = live.getResult(stream)
                if (result.text.isNotEmpty()) emit(SttStreamEvent.Partial(result.text, false))
                if (live.isEndpoint(stream)) {
                    emit(SttStreamEvent.Final(result.text))
                    emit(SttStreamEvent.Endpoint)
                    live.reset(stream)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recognize() streaming failed", t)
            emit(SttStreamEvent.Error(t.message ?: "recognize() failed", t))
        } finally {
            runCatching { stream.release() }
            emit(SttStreamEvent.Completed)
        }
    }

    // finally runs the terminal decode under NonCancellable so release-to-stop still produces a Final.
    private fun recognizeOffline(batch: OfflineRecognizer, audio: Flow<FloatArray>): Flow<SttStreamEvent> = flow {
        ensureVadLoaded()
        vadEngine.resetFeed()
        val buffer = ArrayList<FloatArray>(256)
        var total = 0
        var inSpeech = false
        var emittedFinal = false

        suspend fun decodeAndEmit(reason: String) {
            if (emittedFinal) return
            emittedFinal = true
            if (total == 0) {
                emit(SttStreamEvent.Final(""))
                emit(SttStreamEvent.Endpoint)
                return
            }
            val combined = FloatArray(total)
            var offset = 0
            for (c in buffer) {
                System.arraycopy(c, 0, combined, offset, c.size)
                offset += c.size
            }
            val stream = batch.createStream()
            try {
                stream.acceptWaveform(combined, 16_000)
                batch.decode(stream)
                val text = batch.getResult(stream).text
                Log.d(TAG, "offline decode ($reason): ${'$'}{text.length} chars from ${'$'}{total} samples")
                emit(SttStreamEvent.Final(text))
                emit(SttStreamEvent.Endpoint)
            } finally {
                runCatching { stream.release() }
            }
        }

        try {
            audio.collect { samples ->
                buffer += samples
                total += samples.size
                val event = vadEngine.feed(samples)
                when (event) {
                    VadEvent.SpeechStart -> inSpeech = true
                    VadEvent.SpeechEnd -> {
                        decodeAndEmit("vad_end")
                        // Throw to unwind the audio Flow's upstream pump.
                        throw StopRecognize
                    }
                    VadEvent.SpeechContinues -> if (!inSpeech) inSpeech = true
                    else -> Unit
                }
            }
        } catch (_: StopRecognize) {
        } catch (t: Throwable) {
            Log.w(TAG, "recognize() offline buffer failed", t)
            emit(SttStreamEvent.Error(t.message ?: "recognize() failed", t))
        } finally {
            withContext(NonCancellable) { decodeAndEmit("flow_close") }
            emit(SttStreamEvent.Completed)
        }
    }

    private suspend fun ensureVadLoaded() {
        val spec = SpeechAssetCatalog.sileroVad
        if (!storage.hasFile(spec)) {
            throw IllegalStateException(
                "Silero VAD not downloaded — required for offline STT endpoint detection",
            )
        }
        if (vadEngine.loadedModelId != spec.id) vadEngine.load(spec)
    }

    override fun close() {
        runCatching { releaseLocked() }
    }

    private fun releaseLocked() {
        runCatching { online?.release() }
        runCatching { offline?.release() }
        online = null
        offline = null
        _loaded = null
        loadedFamily = null
    }

    private object StopRecognize : RuntimeException() {
        @Suppress("unused")
        private fun readResolve(): Any = StopRecognize
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        private const val TAG = "SherpaStt"
    }
}
