package com.sabreware.aide.app.speech.io

import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.io.ReasonEvent
import com.sabreware.aide.core.domain.speech.SpeechPrefs
import com.sabreware.aide.app.speech.VoiceLoopState
import com.sabreware.aide.app.speech.fakes.FakeAudioPlayer
import com.sabreware.aide.app.speech.fakes.FakeSpeechEngineRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 3 — locks the reason→speech transform extracted from `VoicePipeline`: it emits Thinking, splits
 * the streamed reply into spoken sentences, and ends Idle. The streaming-PCM path drives [AudioPlayer];
 * the system-TTS path does not. With replies muted, nothing speaks. The channel must touch no residency
 * or audio focus — only the player it's given.
 */
class VoiceOutputChannelTest {

    private val reason = flowOf(
        ReasonEvent.TextDelta("Hello world. "),
        ReasonEvent.Done("Hello world."),
    )

    @Test
    fun systemTtsPathSpeaksSentencesWithoutTheAudioPlayer() = runTest {
        val player = FakeAudioPlayer()
        val out = VoiceOutputChannel(
            FakeSpeechEngineRepository(ttsStreamingPcm = false),
            player,
            FakePreferenceStore(),
        ).render(reason).toList()

        assertEquals(VoiceLoopState.Thinking(""), out.first())
        assertEquals(VoiceLoopState.Idle, out.last())
        assertTrue(
            "should speak the completed sentence",
            out.any { it is VoiceLoopState.Speaking && it.sentenceInFlight == "Hello world." },
        )
        assertEquals("system TTS plays out-of-band — never the streaming player", 0, player.playCalls)
    }

    @Test
    fun streamingPcmPathDrivesTheAudioPlayer() = runTest {
        val player = FakeAudioPlayer()
        VoiceOutputChannel(
            FakeSpeechEngineRepository(ttsStreamingPcm = true),
            player,
            FakePreferenceStore(),
        ).render(reason).toList()

        assertTrue("streaming-PCM engine must play through AudioPlayer", player.playCalls >= 1)
    }

    @Test
    fun mutedRepliesNeitherSpeakNorPlay() = runTest {
        val player = FakeAudioPlayer()
        val out = VoiceOutputChannel(
            FakeSpeechEngineRepository(ttsStreamingPcm = true),
            player,
            FakePreferenceStore(SpeechPrefs.SpeakAssistantReplies to false),
        ).render(reason).toList()

        assertEquals(0, player.playCalls)
        assertTrue("no Speaking states when replies are muted", out.none { it is VoiceLoopState.Speaking })
        assertEquals(VoiceLoopState.Idle, out.last())
    }
}
