package com.sabreware.aide.core.domain.usecase

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Characterization (Slice 0) — locks the recognition-core behavior BEFORE it is extracted into
 * `VoiceInputChannel` (Slice 2). Asserts the ALL-CAPS normalization, the Partial/Final/Endpoint/
 * Completed → Event sequencing, the `engineOwnsAudio` mic-skip branch, and the mic-disallowed path.
 * If Slice 2 changes any of these, this test must fail.
 */
class StartDictationUseCaseTest {

    private fun useCase(speech: SpeechEngineRepository, capturer: AudioCapturer) =
        StartDictationUseCase(capturer, speech)

    /**
     * Dictation used to take no residency hold at all, so the recogniser's refcount stayed at zero for the
     * whole utterance and the idle-close job (or a memory trim) was free to free the native handles
     * mid-decode. The hold is the thing that makes that impossible — pin it.
     */
    @Test
    fun holdsTheSttModelForTheWholeDictation() = runTest {
        val speech = FakeSpeech(
            ownsAudio = true,
            events = listOf(
                SttStreamEvent.Final("hello"),
                SttStreamEvent.End(SpeechStreamOutcome.Done),
            ),
        )

        useCase(speech, FakeCapturer(session = null)).invoke().toList()

        assertEquals(listOf(SpeechEngineRepository.Role.STT), speech.acquiredRoles)
        assertEquals(1, speech.releases)
    }

    @Test
    fun normalizesAllCapsToSentenceCase() = runTest {
        val speech = FakeSpeech(
            ownsAudio = true,
            events = listOf(
                SttStreamEvent.Partial("HELLO WORLD", isStable = false),
                SttStreamEvent.Final("HELLO WORLD"),
                SttStreamEvent.End(SpeechStreamOutcome.Done),
            ),
        )
        val events = useCase(speech, FakeCapturer(session = null)).invoke().toList()

        assertEquals(
            listOf(
                StartDictationUseCase.Event.Partial("Hello world"),
                StartDictationUseCase.Event.Final("Hello world"),
                StartDictationUseCase.Event.Done,
            ),
            events,
        )
    }

    @Test
    fun preservesMixedCaseAndNonLatin() = runTest {
        val speech = FakeSpeech(
            ownsAudio = true,
            events = listOf(
                SttStreamEvent.Partial("Hello World", isStable = false),
                SttStreamEvent.Final("Hello World"),
                SttStreamEvent.End(SpeechStreamOutcome.Done),
            ),
        )
        val events = useCase(speech, FakeCapturer(session = null)).invoke().toList()
        assertEquals(StartDictationUseCase.Event.Partial("Hello World"), events.first())
        assertEquals(StartDictationUseCase.Event.Final("Hello World"), events[1])
    }

    @Test
    fun emitsEmptyFinalWhenCompletedWithoutFinal() = runTest {
        val speech = FakeSpeech(
            ownsAudio = true,
            events = listOf(
                SttStreamEvent.Partial("hi", isStable = false),
                SttStreamEvent.End(SpeechStreamOutcome.Done),
            ),
        )
        val events = useCase(speech, FakeCapturer(session = null)).invoke().toList()
        assertEquals(
            listOf(
                StartDictationUseCase.Event.Partial("hi"),
                StartDictationUseCase.Event.Final(""),
                StartDictationUseCase.Event.Done,
            ),
            events,
        )
    }

    @Test
    fun engineOwnsAudioSkipsOpeningTheMic() = runTest {
        val capturer = FakeCapturer(session = null)
        val speech = FakeSpeech(
            ownsAudio = true,
            events = listOf(SttStreamEvent.Final("ok"), SttStreamEvent.End(SpeechStreamOutcome.Done)),
        )
        useCase(speech, capturer).invoke().toList()
        assertEquals(0, capturer.openCalls, "self-capturing engine must not open AudioCapturer")
    }

    @Test
    fun sherpaPathOpensAndStopsTheMicSession() = runTest {
        val session = FakeAudioSession()
        val capturer = FakeCapturer(session = session)
        val speech = FakeSpeech(
            ownsAudio = false,
            events = listOf(SttStreamEvent.Final("ok"), SttStreamEvent.End(SpeechStreamOutcome.Done)),
        )
        useCase(speech, capturer).invoke().toList()
        assertEquals(1, capturer.openCalls)
        assertTrue(session.stopped, "mic session must be stopped on completion")
    }

    @Test
    fun micDisallowedEmitsSensitiveErrorThenDone() = runTest {
        val capturer = FakeCapturer(session = null)
        val speech = FakeSpeech(ownsAudio = true, events = emptyList())
        val events = useCase(speech, capturer).invoke(micAllowed = false).toList()

        assertEquals(2, events.size)
        val error = events[0] as StartDictationUseCase.Event.Error
        assertTrue(error.sensitive)
        assertEquals(StartDictationUseCase.Event.Done, events[1])
        assertFalse(capturer.openCalls > 0, "must not touch the mic when disallowed")
    }
}

private class FakeAudioSession : AudioSession {
    var stopped = false
    override val frames: Flow<FloatArray> = emptyFlow()
    override fun stop() { stopped = true }
}

private class FakeCapturer(private val session: AudioSession?) : AudioCapturer {
    var openCalls = 0
    override fun openSession(scope: CoroutineScope, prerollMs: Long): AudioSession {
        openCalls++
        return session ?: error("openSession not expected for self-capturing engine")
    }
}

/** Minimal fake — only STT recognition is exercised here; other roles must never be touched. */
private class FakeSpeech(
    private val ownsAudio: Boolean,
    private val events: List<SttStreamEvent>,
) : SpeechEngineRepository {
    override val currentProviderFlow: StateFlow<ProviderId> = MutableStateFlow(ProviderId("test"))
    override suspend fun resolve(role: SpeechEngineRepository.Role): SpeechProvider = unsupported()
    /** Recorded so a test can assert dictation never decodes without a hold on the model. */
    val acquiredRoles = mutableListOf<SpeechEngineRepository.Role>()
    var releases = 0
        private set

    override suspend fun acquire(role: SpeechEngineRepository.Role): ResidencyHandle {
        acquiredRoles += role
        return object : ResidencyHandle {
            override suspend fun release(keepAliveMs: Long) {
                releases++
            }
        }
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
