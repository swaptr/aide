package com.swaptr.aide.domain.speech.system

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.domain.speech.SpeechRecognizerEngine
import com.swaptr.aide.domain.speech.SttOptions
import com.swaptr.aide.domain.speech.SttStreamEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

// SpeechRecognizer.createSpeechRecognizer + all listener callbacks must run on the main
// thread — we hop onto Dispatchers.Main.immediate.
@Singleton
class SystemSttEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : SpeechRecognizerEngine {

    override val loadedModelId: String = "android-system"
    override val isStreaming: Boolean = true
    override val ownsAudioInput: Boolean = true

    override suspend fun load(spec: SpeechAssetSpec) = Unit

    override fun recognize(audio: Flow<FloatArray>, options: SttOptions): Flow<SttStreamEvent> =
        callbackFlow {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                trySend(SttStreamEvent.Error("No speech recognition service available"))
                trySend(SttStreamEvent.Completed)
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
                    trySend(SttStreamEvent.Endpoint)
                }
                override fun onError(error: Int) {
                    trySend(SttStreamEvent.Error("System STT error $error (${describeError(error)})"))
                    trySend(SttStreamEvent.Completed)
                    close()
                }
                override fun onEndOfSpeech() { trySend(SttStreamEvent.Endpoint) }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
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
                trySend(SttStreamEvent.Error("startListening: ${t.message}"))
                trySend(SttStreamEvent.Completed)
                close()
                return@callbackFlow
            }
            awaitClose {
                runCatching { recognizer.stopListening() }
                runCatching { recognizer.destroy() }
            }
        }.flowOn(Dispatchers.Main.immediate)

    override fun close() = Unit

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
    }
}
