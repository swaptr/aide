package com.swaptr.aide.domain.usecase

import android.view.inputmethod.EditorInfo
import com.swaptr.aide.data.speech.SpeechEngineRepository
import com.swaptr.aide.domain.speech.MissingMicPermissionException
import com.swaptr.aide.domain.speech.SttOptions
import com.swaptr.aide.domain.speech.SttStreamEvent
import com.swaptr.aide.domain.speech.audio.AudioCapturer
import com.swaptr.aide.domain.speech.audio.SensitiveAudioPolicy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject

class StartDictationUseCase @Inject constructor(
    private val capturer: AudioCapturer,
    private val speech: SpeechEngineRepository,
    private val sensitive: SensitiveAudioPolicy,
) {
    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Error(val message: String, val sensitive: Boolean = false) : Event
        data object Done : Event
    }

    fun invoke(
        editorInfo: EditorInfo? = null,
        options: SttOptions = SttOptions(),
    ): Flow<Event> = channelFlow {
        if (!sensitive.isMicAllowed(editorInfo)) {
            trySend(Event.Error("Mic disabled for this field", sensitive = true))
            trySend(Event.Done)
            return@channelFlow
        }
        // Self-capturing engines (Android SpeechRecognizer) reject a parallel AudioRecord —
        // it steals the mic and the service reports ERROR_NO_MATCH. Skip openSession for them.
        val engineOwnsAudio = speech.resolvedSttOwnsAudioInput()
        // Sherpa path: open mic before recognizer subscribes so the SharedFlow's ~500 ms
        // preroll covers the JNI model-load window — otherwise the first 200-500 ms is lost.
        val session = if (engineOwnsAudio) {
            null
        } else {
            try {
                capturer.openSession(this)
            } catch (t: MissingMicPermissionException) {
                trySend(Event.Error("Microphone permission required"))
                trySend(Event.Done)
                return@channelFlow
            } catch (t: Throwable) {
                trySend(Event.Error(t.message ?: "Failed to open microphone"))
                trySend(Event.Done)
                return@channelFlow
            }
        }
        val frames: Flow<FloatArray> = session?.frames ?: emptyFlow()

        var emittedFinal = false
        try {
            speech.recognize(frames, options).collect { ev ->
                when (ev) {
                    is SttStreamEvent.Partial -> trySend(Event.Partial(normalize(ev.text, options.locale)))
                    is SttStreamEvent.Final -> {
                        emittedFinal = true
                        trySend(Event.Final(normalize(ev.text, options.locale)))
                    }
                    is SttStreamEvent.Endpoint -> {
                        if (emittedFinal) {
                            trySend(Event.Done)
                            close()
                        }
                    }
                    is SttStreamEvent.Error -> {
                        trySend(Event.Error(ev.message))
                        trySend(Event.Done)
                        close()
                    }
                    SttStreamEvent.Completed -> {
                        if (!emittedFinal) trySend(Event.Final(""))
                        trySend(Event.Done)
                        close()
                    }
                }
            }
        } finally {
            session?.stop()
        }
        awaitClose { session?.stop() }
    }

    // Sherpa Parakeet TDT / NeMo CTC English emit ALL-CAPS no-punctuation tokens — fold
    // to lowercase + capitalise first letter for Latin scripts so it doesn't look like shouting.
    private fun normalize(text: String, locale: String): String {
        if (text.isEmpty()) return text
        if (!isLatinScriptLocale(locale)) return text
        val hasLower = text.any { it.isLowerCase() }
        val hasUpper = text.any { it.isUpperCase() }
        if (hasLower || !hasUpper) return text
        val lowered = text.lowercase()
        val first = lowered.indexOfFirst { it.isLetter() }
        return if (first < 0) lowered
        else lowered.substring(0, first) +
            lowered[first].uppercaseChar() +
            lowered.substring(first + 1)
    }

    private fun isLatinScriptLocale(locale: String): Boolean {
        val tag = locale.lowercase().substringBefore('-').substringBefore('_')
        return tag in LATIN_LOCALES
    }

    companion object {
        private val LATIN_LOCALES = setOf(
            "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da", "fi",
            "pl", "cs", "sk", "ro", "hu", "tr", "id", "ms", "vi", "ca", "eu",
            "gl", "hr", "sr", "sl", "et", "lv", "lt", "is", "ga", "cy", "mt",
            "sq", "az", "uz",
        )
    }
}
