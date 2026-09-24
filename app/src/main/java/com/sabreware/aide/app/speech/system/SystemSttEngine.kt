package com.sabreware.aide.app.speech.system

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.SpeechRecognizerEngine
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SttOptions
import com.sabreware.aide.core.domain.speech.SttStreamEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

// SpeechRecognizer.createSpeechRecognizer + all listener callbacks must run on the main
// thread — we hop onto Dispatchers.Main.immediate.
class SystemSttEngine(
    private val appContext: Context,
    private val micActivity: MicActivityMonitor,
    private val mainImmediateDispatcher: CoroutineDispatcher,
) : SpeechRecognizerEngine {

    override val loadedModelId: String = "android-system"
    override val isStreaming: Boolean = true
    override val ownsAudioInput: Boolean = true

    override suspend fun load(spec: SpeechAssetSpec) = Unit

    override fun recognize(audio: Flow<FloatArray>, options: SttOptions): Flow<SttStreamEvent> =
        callbackFlow {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                trySend(SttStreamEvent.End(SpeechStreamOutcome.Error("No speech recognition service available")))
                close()
                return@callbackFlow
            }
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle) {
                    val text = partialResults
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) trySend(SttStreamEvent.Partial(text, isStable = false))
                }
                override fun onResults(results: Bundle) {
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    trySend(SttStreamEvent.Final(text))
                    trySend(SttStreamEvent.End(SpeechStreamOutcome.Done))
                    close()
                }
                override fun onError(error: Int) {
                    trySend(
                        SttStreamEvent.End(
                            SpeechStreamOutcome.Error("System STT error $error (${describeError(error)})"),
                        ),
                    )
                    close()
                }
                // This engine owns the mic, so AudioCapturer never runs and the level meter would sit
                // frozen. These three callbacks ARE its mic signal — the recogniser's own VAD included.
                override fun onEndOfSpeech() { micActivity.onVoiceActivity(false) }
                override fun onReadyForSpeech(params: Bundle?) { micActivity.begin() }
                override fun onBeginningOfSpeech() { micActivity.onVoiceActivity(true) }
                override fun onRmsChanged(rmsdB: Float) { micActivity.onEngineRms(rmsdB, RmsCallbackMs) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, options.locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, options.partialResults)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
            try {
                recognizer.startListening(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startListening failed", t)
                trySend(SttStreamEvent.End(SpeechStreamOutcome.Error("startListening: ${t.message}")))
                close()
                return@callbackFlow
            }
            awaitClose {
                runCatching { recognizer.stopListening() }
                runCatching { recognizer.destroy() }
                micActivity.end()
            }
        }.flowOn(mainImmediateDispatcher)

    override suspend fun close() = Unit

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
        else -> "unknown"
    }

    companion object {
        private const val TAG = "SystemSttEngine"
        // onRmsChanged documents neither a rate nor a reference level ("the new RMS dB value";
        // "there is no guarantee that this method will be called"), so this is only a nominal cadence
        // for the detector's ms-based smoothing — the level itself is derived scale-free.
        private const val RmsCallbackMs = 50
    }
}
