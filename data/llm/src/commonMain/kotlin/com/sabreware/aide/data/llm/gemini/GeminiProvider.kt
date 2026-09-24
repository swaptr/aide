package com.sabreware.aide.data.llm.gemini

import com.sabreware.aide.core.domain.image.ImageEngine
import com.sabreware.aide.core.domain.image.ImageProvider
import com.sabreware.aide.core.domain.llm.ChatProvider
import com.sabreware.aide.core.domain.llm.LlmEngine
import com.sabreware.aide.core.domain.llm.Manageable
import com.sabreware.aide.core.domain.llm.ProviderManagement
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.speech.SpeechAvailability
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.core.domain.speech.SpeechRecognizerEngine
import com.sabreware.aide.core.domain.speech.SpeechSynthesizerEngine
import com.sabreware.aide.core.domain.speech.VadEngine

/**
 * Google Gemini — every modality one key reaches.
 *
 * Chat and its catalog ride the shared remote layer ([com.sabreware.aide.data.llm.aisdk.AiSdkLlmEngine] /
 * [com.sabreware.aide.data.llm.remote.RemoteProviderManagement]) over :aisdk's native Google provider —
 * native rather than the OpenAI-compat path, which drops `thought_signature`. The same provider serves
 * image generation (a Gemini model asked to answer with a picture), speech (Gemini TTS) and transcription
 * (Gemini's unary transcribe model), so this one class fills every slot of a Gemini connection's runtime:
 * one instance per connection, one credential, no per-modality copies to keep in step.
 *
 * Standalone rather than a [com.sabreware.aide.data.llm.remote.RemoteChatProvider] subclass: the base is
 * for vendors whose only capability is chat, and inheriting it here would make the class's shape a
 * consequence of which capability was wired first. Built per connection by
 * [com.sabreware.aide.data.llm.vendor.GeminiVendor].
 */
class GeminiProvider(
    override val id: ProviderId,
    override val chat: LlmEngine,
    override val management: ProviderManagement,
    override val image: ImageEngine,
    override val stt: SpeechRecognizerEngine,
    override val tts: SpeechSynthesizerEngine,
    private val speechAvailability: () -> SpeechAvailability,
) : ChatProvider, Manageable, ImageProvider, SpeechProvider {
    override val vad: VadEngine? = null
    override suspend fun availability(): SpeechAvailability = speechAvailability()
}
