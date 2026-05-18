package com.swaptr.aide.domain.speech.system

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.domain.speech.SpeechSynthesizerEngine
import com.swaptr.aide.domain.speech.TtsOptions
import com.swaptr.aide.domain.speech.TtsStreamEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// TextToSpeech requires an OnInitListener callback on the main thread before speak()
// works — first synthesize() suspends until init completes.
@Singleton
class SystemTtsEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : SpeechSynthesizerEngine {

    override val loadedModelId: String = "android-system"
    override val supportsStreamingPcm: Boolean = false

    @Volatile private var tts: TextToSpeech? = null
    private val initialised = AtomicBoolean(false)

    override suspend fun load(spec: SpeechAssetSpec) = Unit

    override fun synthesize(text: String, options: TtsOptions): Flow<TtsStreamEvent> =
        callbackFlow {
            val engine = obtainOrInit()
            if (engine == null) {
                trySend(TtsStreamEvent.Error("TextToSpeech failed to initialise"))
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
                override fun onRangeStart(uId: String, start: Int, end: Int, frame: Int) {
                    if (start in text.indices && end <= text.length && end > start) {
                        trySend(
                            TtsStreamEvent.Boundary(
                                kind = TtsStreamEvent.BoundaryKind.WORD,
                                text = text.substring(start, end),
                                offsetChars = start,
                            ),
                        )
                    }
                }
                override fun onDone(utteranceId: String?) {
                    trySend(TtsStreamEvent.Completed)
                    close()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    trySend(TtsStreamEvent.Error("System TTS error"))
                    close()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    trySend(TtsStreamEvent.Error("System TTS error $errorCode"))
                    close()
                }
            })
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                trySend(TtsStreamEvent.Error("speak() returned $result"))
                close()
                return@callbackFlow
            }
            awaitClose { runCatching { engine.stop() } }
        }.flowOn(Dispatchers.Main.immediate)

    override fun close() {
        runCatching { tts?.shutdown() }
        tts = null
        initialised.set(false)
    }

    private suspend fun obtainOrInit(): TextToSpeech? {
        tts?.takeIf { initialised.get() }?.let { return it }
        return suspendCoroutine { cont ->
            val engine = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    initialised.set(true)
                    cont.resume(tts)
                } else {
                    Log.w(TAG, "TextToSpeech init failed status=$status")
                    cont.resume(null)
                }
            }
            tts = engine
        }
    }

    companion object {
        private const val TAG = "SystemTtsEngine"
    }
}
