package com.sabreware.aide.app.speech.system

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SpeechSynthesizerEngine
import com.sabreware.aide.core.domain.speech.TtsOptions
import com.sabreware.aide.core.domain.speech.TtsStreamEvent
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

// TextToSpeech requires an OnInitListener callback on the main thread before speak()
// works — first synthesize() suspends until init completes.
class SystemTtsEngine(
    private val appContext: Context,
    private val mainImmediateDispatcher: CoroutineDispatcher,
) : SpeechSynthesizerEngine {

    override val loadedModelId: String = "android-system"
    override val supportsStreamingPcm: Boolean = false

    @Volatile private var tts: TextToSpeech? = null
    private val initialised = AtomicBoolean(false)

    /**
     * Serializes everything that touches the shared engine.
     *
     * `TextToSpeech` has ONE progress listener, so two concurrent syntheses are not merely racy — the
     * second call's listener replaces the first's, and the first flow never sees its terminal event. The
     * voice loop then sits in Speaking forever. Two concurrent calls also each constructed an engine and
     * leaked whichever lost.
     */
    private val engineMutex = Mutex()

    override suspend fun load(spec: SpeechAssetSpec) = Unit

    override fun synthesize(text: String, options: TtsOptions): Flow<TtsStreamEvent> =
        flow {
            // Held for the whole utterance, not just for init: the listener is per-engine, so overlapping
            // utterances cannot both be observed.
            engineMutex.withLock { emitAll(speakOnce(text, options)) }
        }.flowOn(mainImmediateDispatcher)

    private fun speakOnce(text: String, options: TtsOptions): Flow<TtsStreamEvent> =
        callbackFlow {
            val engine = obtainOrInit()
            if (engine == null) {
                trySend(TtsStreamEvent.End(SpeechStreamOutcome.Error("TextToSpeech failed to initialise")))
                close()
                return@callbackFlow
            }
            engine.language = runCatching { Locale.forLanguageTag(options.locale) }
                .getOrDefault(Locale.US)
            engine.setSpeechRate(options.rate)
            engine.setPitch(options.pitch)
            options.voiceId?.let { id ->
                runCatching {
                    val voice = engine.voices.firstOrNull { it.name == id }
                    if (voice != null) engine.voice = voice
                }
            }
            val utteranceId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                // Word boundaries were emitted but consumed by nobody (B2) — listener kept, body dropped.
                override fun onRangeStart(uId: String, start: Int, end: Int, frame: Int) {}
                override fun onDone(utteranceId: String?) {
                    trySend(TtsStreamEvent.End(SpeechStreamOutcome.Done))
                    close()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    trySend(TtsStreamEvent.End(SpeechStreamOutcome.Error("System TTS error")))
                    close()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    trySend(TtsStreamEvent.End(SpeechStreamOutcome.Error("System TTS error $errorCode")))
                    close()
                }
            })
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                trySend(TtsStreamEvent.End(SpeechStreamOutcome.Error("speak() returned $result")))
                close()
                return@callbackFlow
            }
            awaitClose {
                runCatching { engine.stop() }
                // Drop the listener with the flow that installed it, so a late callback from a stopped
                // utterance can never be attributed to the next one.
                runCatching { engine.setOnUtteranceProgressListener(null) }
            }
        }

    override suspend fun close() = engineMutex.withLock {
        runCatching { tts?.shutdown() }
        tts = null
        initialised.set(false)
    }

    /**
     * Bind the platform engine, once, with a deadline.
     *
     * The status arrives on a [CompletableDeferred] rather than through `suspendCoroutine` for two reasons:
     * awaiting it is cancellable, so a collector that goes away while the TTS service is still binding no
     * longer strands the continuation; and the engine reference is resolved after the constructor returns,
     * rather than being read out of a field the constructor callback might beat.
     *
     * The deadline covers the engine that never calls back at all — a disabled default, an OEM engine that
     * fails silently. Without it the voice loop parked in Speaking for the rest of the session.
     *
     * Callers hold [engineMutex].
     */
    private suspend fun obtainOrInit(): TextToSpeech? {
        tts?.takeIf { initialised.get() }?.let { return it }
        val status = CompletableDeferred<Int>()
        val engine = TextToSpeech(appContext) { status.complete(it) }
        val result = withTimeoutOrNull(INIT_TIMEOUT_MS) { status.await() }
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech init failed (status=${result ?: "timed out"})")
            runCatching { engine.shutdown() }
            return null
        }
        tts = engine
        initialised.set(true)
        return engine
    }

    companion object {
        private const val TAG = "SystemTtsEngine"
        // Binding a TTS service is normally tens of milliseconds; ten seconds is "it is never coming".
        private const val INIT_TIMEOUT_MS = 10_000L
    }
}
