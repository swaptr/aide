package com.swaptr.aide.data.speech

import android.util.Log
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.domain.speech.SpeechAvailability
import com.swaptr.aide.domain.speech.SpeechProvider
import com.swaptr.aide.domain.speech.SpeechProviderId
import com.swaptr.aide.domain.speech.SttOptions
import com.swaptr.aide.domain.speech.SttStreamEvent
import com.swaptr.aide.domain.speech.TtsOptions
import com.swaptr.aide.domain.speech.TtsStreamEvent
import com.swaptr.aide.domain.speech.VadEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

// Resolution: pinned-if-capable → Sherpa → Android System (always present). lifecycleLock
// serialises load/unload across roles so two recognisers never go resident.
@Singleton
class SpeechEngineRepository @Inject constructor(
    private val providers: Map<SpeechProviderId, @JvmSuppressWildcards SpeechProvider>,
    private val prefs: UserPreferencesRepository,
) {
    /** Serialises load/unload across providers. Mirrors the LLM repository. */
    val lifecycleLock: Mutex = Mutex()

    private val _currentProvider = MutableStateFlow(SpeechProviderId.ANDROID_SYSTEM)
    val currentProviderFlow: StateFlow<SpeechProviderId> = _currentProvider.asStateFlow()

    enum class Role { STT, TTS, VAD }

    suspend fun resolve(role: Role): SpeechProvider {
        val pinned = prefs.speechProviderPreferenceFlow.first()
        if (pinned != null) {
            val provider = providers[pinned]
            if (provider != null && capable(provider.availability(), role)) {
                _currentProvider.value = pinned
                return provider
            }
        }
        val sherpa = providers[SpeechProviderId.SHERPA_ONNX]
        if (sherpa != null && capable(sherpa.availability(), role)) {
            _currentProvider.value = SpeechProviderId.SHERPA_ONNX
            return sherpa
        }
        val system = providers[SpeechProviderId.ANDROID_SYSTEM]
            ?: throw IllegalStateException("System speech provider not registered")
        _currentProvider.value = SpeechProviderId.ANDROID_SYSTEM
        return system
    }

    fun recognize(audio: Flow<FloatArray>, options: SttOptions = SttOptions()): Flow<SttStreamEvent> = flow {
        val provider = resolve(Role.STT)
        val stt = provider.stt
        if (stt == null) {
            emit(SttStreamEvent.Error("Provider ${provider.id} cannot transcribe"))
            emit(SttStreamEvent.Completed)
            return@flow
        }
        try {
            stt.recognize(audio, options).collect { emit(it) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "recognize() failed", t)
            emit(SttStreamEvent.Error(t.message ?: "recognize() failed", t))
            emit(SttStreamEvent.Completed)
        }
    }

    // Callers must NOT open AudioCapturer when true — two VOICE_RECOGNITION clients race
    // and the system reports NO_SPEECH_DETECTED.
    suspend fun resolvedSttOwnsAudioInput(): Boolean =
        resolve(Role.STT).stt?.ownsAudioInput == true

    /** Resolves the STT provider and loads its active model. Fire-and-forget from
     *  surface-show callbacks so the first mic tap has the model already in memory. */
    suspend fun warmUpStt() {
        runCatching {
            val provider = resolve(Role.STT)
            provider.stt?.warmUp()
        }
    }

    fun synthesize(text: String, options: TtsOptions = TtsOptions()): Flow<TtsStreamEvent> = flow {
        val provider = resolve(Role.TTS)
        val tts = provider.tts
        if (tts == null) {
            emit(TtsStreamEvent.Error("Provider ${provider.id} cannot synthesise"))
            return@flow
        }
        try {
            tts.synthesize(text, options).collect { emit(it) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "synthesize() failed", t)
            emit(TtsStreamEvent.Error(t.message ?: "synthesize() failed", t))
        }
    }

    fun vad(audio: Flow<FloatArray>): Flow<VadEvent> = flow {
        val provider = resolve(Role.VAD)
        val vad = provider.vad ?: return@flow
        vad.process(audio).collect { emit(it) }
    }

    suspend fun availability(role: Role): SpeechAvailability {
        val provider = resolve(role)
        return provider.availability()
    }

    // True for Sherpa (streaming PCM); false for system TTS (out-of-band playback).
    // Callers route to AudioPlayer only when true.
    suspend fun resolvedTtsSupportsStreamingPcm(): Boolean {
        val provider = resolve(Role.TTS)
        return provider.tts?.supportsStreamingPcm == true
    }

    private fun capable(a: SpeechAvailability, role: Role): Boolean = when (role) {
        Role.STT -> a.canStt
        Role.TTS -> a.canTts
        Role.VAD -> a.canVad
    }

    companion object {
        private const val TAG = "SpeechEngine"
    }
}
