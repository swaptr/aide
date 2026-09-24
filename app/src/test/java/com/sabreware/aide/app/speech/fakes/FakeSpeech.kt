package com.sabreware.aide.app.speech.fakes

import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.speech.AudioCapturer
import com.sabreware.aide.core.domain.speech.AudioSession
import com.sabreware.aide.core.domain.speech.SpeechAvailability
import com.sabreware.aide.core.domain.speech.SpeechEngineRepository
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SttOptions
import com.sabreware.aide.core.domain.speech.SttStreamEvent
import com.sabreware.aide.core.domain.speech.TtsOptions
import com.sabreware.aide.core.domain.speech.TtsStreamEvent
import com.sabreware.aide.core.domain.speech.VadEvent
import com.sabreware.aide.app.speech.audio.AudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Reusable speech-stack fakes (no mock library in this project). */

class FakeAudioSession : AudioSession {
    var stopped = false
    override val frames: Flow<FloatArray> = emptyFlow()
    override fun stop() { stopped = true }
}

class FakeAudioCapturer : AudioCapturer {
    var openCalls = 0
    val lastSession = FakeAudioSession()
    override fun openSession(scope: CoroutineScope, prerollMs: Long): AudioSession {
        openCalls++
        return lastSession
    }
}

class FakeAudioPlayer : AudioPlayer {
    var playCalls = 0
    var stopCalls = 0
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun play(chunks: Flow<TtsStreamEvent.AudioChunk>) {
        playCalls++
        chunks.collect { }
    }
    override fun stop() { stopCalls++ }
}

/**
 * Drives both halves of the speech stack: [recognize] replays [sttEvents]; [synthesize] replays
 * [ttsEvents]; the streaming-PCM and engine-owns-audio flags are configurable. Unused roles throw.
 */
class FakeSpeechEngineRepository(
    private val sttEvents: List<SttStreamEvent> = emptyList(),
    private val ttsStreamingPcm: Boolean = false,
    private val ownsAudio: Boolean = true,
    private val ttsEvents: List<TtsStreamEvent> = listOf(
        TtsStreamEvent.AudioChunk(FloatArray(4), sampleRate = 16_000),
        TtsStreamEvent.End(SpeechStreamOutcome.Done),
    ),
) : SpeechEngineRepository {
    override val currentProviderFlow: StateFlow<ProviderId> = MutableStateFlow(ProviderId("test"))
    override fun recognize(audio: Flow<FloatArray>, options: SttOptions): Flow<SttStreamEvent> =
        sttEvents.asFlow()
    override suspend fun resolvedSttOwnsAudioInput(): Boolean = ownsAudio
    override fun synthesize(text: String, options: TtsOptions): Flow<TtsStreamEvent> =
        if (ttsEvents.isEmpty()) flowOf(TtsStreamEvent.End(SpeechStreamOutcome.Done)) else ttsEvents.asFlow()
    override suspend fun resolvedTtsSupportsStreamingPcm(): Boolean = ttsStreamingPcm
    override suspend fun warmUpStt() = Unit
    override suspend fun resolve(role: SpeechEngineRepository.Role): SpeechProvider = unsupported()
    // Dictation holds the STT model for the length of an utterance, so this has to answer rather than
    // refuse — a throw here surfaces as an Error event and the turn looks blank.
    override suspend fun acquire(role: SpeechEngineRepository.Role): ResidencyHandle =
        object : ResidencyHandle {
            override suspend fun release(keepAliveMs: Long) = Unit
        }
    override fun vad(audio: Flow<FloatArray>): Flow<VadEvent> = unsupported()
    override suspend fun availability(role: SpeechEngineRepository.Role): SpeechAvailability = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
}
