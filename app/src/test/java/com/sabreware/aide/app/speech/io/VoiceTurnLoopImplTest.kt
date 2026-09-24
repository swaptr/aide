package com.sabreware.aide.app.speech.io

import com.sabreware.aide.core.domain.fakes.FakeModelRegistryRepository
import com.sabreware.aide.core.domain.fakes.FakeModelSelectionStore
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.io.ReasonEvent
import com.sabreware.aide.core.domain.io.VoiceInputChannel
import com.sabreware.aide.core.domain.io.VoiceReasoner
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ModelBackend
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.RemoteLlmModel
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.SttStreamEvent
import com.sabreware.aide.app.speech.VoiceLoopState
import com.sabreware.aide.app.speech.VoiceTurnPolicy
import com.sabreware.aide.core.domain.usecase.StartDictationUseCase
import com.sabreware.aide.app.speech.fakes.FakeAudioCapturer
import com.sabreware.aide.app.speech.fakes.FakeAudioPlayer
import com.sabreware.aide.app.speech.fakes.FakeSpeechEngineRepository
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 4 — locks the assistant turn composition (input → reason → output, looped by policy) without the
 * heavy `SendChatMessageUseCase` (faked behind [VoiceReasoner]). A spoken utterance reasons once and
 * speaks the reply; a blank utterance never reasons and the idle-silence policy stops the loop.
 */
class VoiceTurnLoopImplTest {

    private val spec = RemoteLlmModel(
        id = "m1", displayName = "M1", family = "f", params = "1B", quantization = "q4",
        remoteName = "m1", cloud = false, minRamGb = 1, recommendedRamGb = 2,
        capabilities = ChatCapabilities(maxContext = 4096, maxOutput = 1024),
        defaultBackend = ModelBackend.CPU,
        licenseName = "l", licenseUrl = "https://x/l", sourceUrl = "https://x/s",
        provider = ProviderId("openai-test01"),
    )

    private fun loop(sttEvents: List<SttStreamEvent>, reasoner: VoiceReasoner): VoiceTurnLoopImpl {
        val capturer = FakeAudioCapturer()
        val speech = FakeSpeechEngineRepository(sttEvents = sttEvents)
        val player = FakeAudioPlayer()
        val prefs = FakePreferenceStore()
        return VoiceTurnLoopImpl(
            voiceInput = VoiceInputChannel(StartDictationUseCase(capturer, speech)),
            voiceOutput = VoiceOutputChannel(speech, player, prefs),
            reasoner = reasoner,
            micActivity = MicActivityMonitor(),
            player = player,
            registry = FakeModelRegistryRepository(mapOf("m1" to spec)),
            selection = FakeModelSelectionStore(ModelSelection(lastUsedModelId = "m1")),
        )
    }

    @Test
    fun spokenUtteranceReasonsThenSpeaksThenStopsAfterReply() = runTest {
        var reasonCalls = 0
        val reasoner = VoiceReasoner { _, _, _, _ ->
            reasonCalls++
            flowOf(
                ReasonEvent.TextDelta("hi. "),
                ReasonEvent.Done("hi."),
            )
        }
        val states = loop(listOf(SttStreamEvent.Final("hello"), SttStreamEvent.End(SpeechStreamOutcome.Done)), reasoner)
            .run(VoiceTurnLoopImpl.DefaultVoiceSession(), VoiceTurnPolicy.OffAfterReply)
            .toList()

        assertEquals("exactly one reasoning turn", 1, reasonCalls)
        assertTrue("listens before reasoning", states.any { it is VoiceLoopState.Listening })
        assertTrue(
            "speaks the reply sentence",
            states.any { it is VoiceLoopState.Speaking && it.sentenceInFlight == "hi." },
        )
        assertEquals(VoiceLoopState.Idle, states.last())
    }

    @Test
    fun blankUtteranceStopsViaIdleSilenceWithoutReasoning() = runTest {
        var reasonCalls = 0
        val reasoner = VoiceReasoner { _, _, _, _ -> reasonCalls++; emptyFlow() }
        val states = loop(listOf(SttStreamEvent.Final(""), SttStreamEvent.End(SpeechStreamOutcome.Done)), reasoner)
            .run(VoiceTurnLoopImpl.DefaultVoiceSession(), VoiceTurnPolicy.OffAfterIdleSilence(0))
            .toList()

        assertEquals("blank turns never reason", 0, reasonCalls)
        assertEquals(VoiceLoopState.Idle, states.last())
    }
}
