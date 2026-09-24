package com.sabreware.aide.core.domain.io

import com.sabreware.aide.core.domain.chat.AidePart
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
import com.sabreware.aide.core.domain.usecase.StartDictationUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Slice 2 — locks the recognition-core → content-IR mapping that the dictation path and the assistant
 * loop now share. Partials and the final transcript become content-IR [AidePart.Text] parts; a
 * recognition error surfaces as [InputEvent.Error]. The normalization itself is delegated to (and
 * separately locked by) `StartDictationUseCaseTest`.
 */
class VoiceInputChannelTest {

    private fun channel(speech: SpeechEngineRepository, capturer: AudioCapturer) =
        VoiceInputChannel(StartDictationUseCase(capturer, speech))

    @Test
    fun transcribeMapsPartialAndFinalAsTextParts() = runTest {
        val capturer = FakeCapturer()
        val speech = FakeSpeech(
            ownsAudio = true,
            events = listOf(
                SttStreamEvent.Partial("HELLO", isStable = false),
                SttStreamEvent.Final("HELLO"),
                SttStreamEvent.End(SpeechStreamOutcome.Done),
            ),
        )
        val out = channel(speech, capturer).capture(InputOptions()).toList()

        assertEquals(InputEvent.Partial(listOf(AidePart.Text("Hello"))), out[0])
        assertEquals(InputEvent.Final(listOf(AidePart.Text("Hello"))), out[1])
        assertEquals(2, out.size)
        assertEquals(0, capturer.openCalls, "self-capturing engine must not open the mic")
    }

    @Test
    fun recognitionErrorMapsToInputEventError() = runTest {
        val out = channel(FakeSpeech(ownsAudio = true, events = emptyList()), FakeCapturer())
            .capture(InputOptions(micAllowed = false))
            .toList()

        assertEquals(listOf(InputEvent.Error("Mic disabled for this field")), out)
    }
}

private class FakeAudioSession : AudioSession {
    override val frames: Flow<FloatArray> = emptyFlow()
    override fun stop() = Unit
}

private class FakeCapturer : AudioCapturer {
    var openCalls = 0
    override fun openSession(scope: CoroutineScope, prerollMs: Long): AudioSession {
        openCalls++
        return FakeAudioSession()
    }
}

private class FakeSpeech(
    private val ownsAudio: Boolean,
    private val events: List<SttStreamEvent>,
) : SpeechEngineRepository {
    override val currentProviderFlow: StateFlow<ProviderId> = MutableStateFlow(ProviderId("test"))
    override suspend fun resolve(role: SpeechEngineRepository.Role): SpeechProvider = unsupported()
    // Dictation holds the STT model for the length of an utterance (nested acquires of the same key just
    // bump the refcount), so this fake hands back a no-op hold rather than refusing.
    override suspend fun acquire(role: SpeechEngineRepository.Role): ResidencyHandle =
        object : ResidencyHandle {
            override suspend fun release(keepAliveMs: Long) = Unit
        }
    override fun recognize(audio: Flow<FloatArray>, options: SttOptions): Flow<SttStreamEvent> =
        events.asFlow()
    override suspend fun resolvedSttOwnsAudioInput(): Boolean = ownsAudio
    override suspend fun warmUpStt() = Unit
    override fun synthesize(text: String, options: TtsOptions): Flow<TtsStreamEvent> = unsupported()
    override fun vad(audio: Flow<FloatArray>): Flow<VadEvent> = unsupported()
    override suspend fun availability(role: SpeechEngineRepository.Role): SpeechAvailability = unsupported()
    override suspend fun resolvedTtsSupportsStreamingPcm(): Boolean = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
}
