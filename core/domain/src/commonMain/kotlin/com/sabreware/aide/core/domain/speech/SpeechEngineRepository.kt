package com.sabreware.aide.core.domain.speech

import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.ResidencyHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain-facing speech engine contract. Resolution: pinned-if-capable → Sherpa → Android System.
 * Impl ([data.speech.SpeechEngineRepositoryImpl]) owns provider wiring + load/unload serialisation.
 * Bound via Hilt `@Binds`.
 */
interface SpeechEngineRepository {
    val currentProviderFlow: StateFlow<ProviderId>

    enum class Role { STT, TTS, VAD }

    suspend fun resolve(role: Role): SpeechProvider

    /**
     * Acquires the resolved provider's engine for [role] through the ResidencyManager so it stays
     * resident for the caller's session (never closed mid-use) and idle-releases on a keepAlive.
     * Sherpa engines refcount + evict; the android-system engine is a no-op handle.
     */
    suspend fun acquire(role: Role): ResidencyHandle

    fun recognize(audio: Flow<FloatArray>, options: SttOptions = SttOptions()): Flow<SttStreamEvent>

    /** True when the resolved STT provider opens the mic itself — callers must not also open it. */
    suspend fun resolvedSttOwnsAudioInput(): Boolean

    /** Resolves the STT provider and loads its model so the first mic tap is warm. */
    suspend fun warmUpStt()

    fun synthesize(text: String, options: TtsOptions = TtsOptions()): Flow<TtsStreamEvent>

    fun vad(audio: Flow<FloatArray>): Flow<VadEvent>

    suspend fun availability(role: Role): SpeechAvailability

    /** True for streaming-PCM TTS (Sherpa); false for out-of-band system TTS playback. */
    suspend fun resolvedTtsSupportsStreamingPcm(): Boolean
}
