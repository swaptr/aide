package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.core.domain.image.ImageEngine
import com.sabreware.aide.core.domain.image.ImageProvider
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.speech.SpeechAvailability
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.core.domain.speech.SpeechRecognizerEngine
import com.sabreware.aide.core.domain.speech.SpeechSynthesizerEngine
import com.sabreware.aide.core.domain.speech.VadEngine

// ---------------------------------------------------------------------------------------------------
// The non-chat modalities of one connection.
//
// A vendor that speaks exposes the conversation-shaped port the mic, the voice loop and read-aloud consume
// ([SpeechProvider]) as an adapter over its recording-shaped engines — `CloudSttEngine` / `CloudTtsEngine`
// in `:data:speech` — so one vendor call serves both, and the speech ladder can hold a cloud rung without a
// second wire. Built per connection by the vendors in `com.sabreware.aide.data.llm.vendor`.
// ---------------------------------------------------------------------------------------------------

/**
 * An OpenAI-compatible connection's image and speech — or, more precisely, those of whatever server its
 * base URL points at: the `Vendors` row `CompatVendors` picks decides which of them actually exist.
 *
 * [speechAvailability] therefore asks the resolved row rather than merely "is a key stored": an Ollama or
 * Groq endpoint serves no audio at all, and a ladder that picked it for dictation would fail at the first
 * mic tap with "serves no transcription models".
 */
class OpenAiModalityProvider(
    override val id: ProviderId,
    override val image: ImageEngine,
    override val stt: SpeechRecognizerEngine,
    override val tts: SpeechSynthesizerEngine,
    private val speechAvailability: () -> SpeechAvailability,
) : ImageProvider, SpeechProvider {
    override val vad: VadEngine? = null
    override suspend fun availability(): SpeechAvailability = speechAvailability()
}

/** An ElevenLabs connection: voices (text-to-speech) and Scribe (speech-to-text) as a speech-ladder rung. */
class ElevenLabsSpeechProvider(
    override val id: ProviderId,
    override val stt: SpeechRecognizerEngine,
    override val tts: SpeechSynthesizerEngine,
    private val speechAvailability: () -> SpeechAvailability,
) : SpeechProvider {
    override val vad: VadEngine? = null
    override suspend fun availability(): SpeechAvailability = speechAvailability()
}
