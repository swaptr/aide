package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.speech.AudioCapturer
import com.sabreware.aide.core.domain.speech.MissingMicPermissionException
import com.sabreware.aide.core.domain.speech.SpeechEngineRepository
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SttOptions
import com.sabreware.aide.core.domain.speech.SttStreamEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

class StartDictationUseCase(
    private val capturer: AudioCapturer,
    private val speech: SpeechEngineRepository,
) {
    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Error(val message: String, val sensitive: Boolean = false) : Event
        data object Done : Event
    }

    fun invoke(
        micAllowed: Boolean = true,
        options: SttOptions = SttOptions(),
    ): Flow<Event> = channelFlow {
        if (!micAllowed) {
            trySend(Event.Error("Mic disabled for this field", sensitive = true))
            trySend(Event.Done)
            return@channelFlow
        }
        // A hold on the STT model for the whole dictation. Without one the recogniser's refcount stayed at
        // zero, which made it a legal eviction target for the entire utterance: the keepAlive timer armed by
        // an earlier voice session, or a memory-trim (the Android load policy broadcasts TRIM_MEMORY_COMPLETE
        // before every local LLM load), could free the native recogniser mid-decode. Every other consumer of
        // the speech stack already takes a hold; dictation is the most-used mic path and took none.
        val sttHold = try {
            speech.acquire(SpeechEngineRepository.Role.STT)
        } catch (t: Throwable) {
            trySend(Event.Error(t.message ?: "Speech model unavailable"))
            trySend(Event.Done)
            return@channelFlow
        }
        try {
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
                        // A Final is a complete utterance; dictation wants exactly one, so finish the turn
                        // now (the former Endpoint that signaled this was 1:1 with Final).
                        trySend(Event.Done)
                        close()
                    }
                    is SttStreamEvent.End -> {
                        when (val outcome = ev.outcome) {
                            is SpeechStreamOutcome.Error -> trySend(Event.Error(outcome.message))
                            // Done/Cancelled with no transcript → emit an empty Final so the caller
                            // always sees one before Done.
                            else -> if (!emittedFinal) trySend(Event.Final(""))
                        }
                        trySend(Event.Done)
                        close()
                    }
                }
            }
        } finally {
            session?.stop()
        }
        awaitClose { session?.stop() }
        } finally {
            // NonCancellable so a cancelled dictation still drops its hold — a leaked refcount pins the
            // model resident for the life of the process.
            withContext(NonCancellable) { sttHold.release() }
        }
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
