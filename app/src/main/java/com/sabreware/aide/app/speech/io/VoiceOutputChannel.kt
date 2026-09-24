package com.sabreware.aide.app.speech.io

import kotlinx.coroutines.channels.consumeEach
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.domain.io.OutputChannel
import com.sabreware.aide.core.domain.io.ReasonEvent
import com.sabreware.aide.core.domain.speech.SpeechEngineRepository
import com.sabreware.aide.core.domain.speech.SpeechPrefs
import com.sabreware.aide.core.domain.speech.SpeechStreamOutcome
import com.sabreware.aide.core.domain.speech.TtsStreamEvent
import com.sabreware.aide.app.speech.VoiceLoopState
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.app.speech.audio.AudioPlayer
import com.sabreware.aide.app.speech.audio.SentenceSplitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Voice output (Layer D). The reason→speech half lifted out of the old `VoicePipeline`: it consumes the
 * reason stage's neutral [ReasonEvent] stream, sentence-splits the streamed reply into a TTS queue (so
 * synthesis overlaps generation), speaks each sentence via [SpeechEngineRepository.synthesize] →
 * [AudioPlayer] (streaming-PCM engines) or out-of-band (system TTS), and emits [VoiceLoopState].
 *
 * Reads its prefs once per render (per turn) so a mid-session toggle takes effect. Acquires no residency
 * and no audio focus — focus is owned by [AudioPlayer], residency by `AssistantVoiceController`. On
 * cancellation it stops playback in its own `finally`; the turn loop keeps a top-level safety net.
 */
class VoiceOutputChannel(
    private val speech: SpeechEngineRepository,
    private val player: AudioPlayer,
    private val prefs: PreferenceStore,
) : OutputChannel<ReasonEvent, VoiceLoopState> {

    override fun render(reason: Flow<ReasonEvent>): Flow<VoiceLoopState> = channelFlow {
        val speakReplies = prefs.flow(SpeechPrefs.SpeakAssistantReplies).firstOrNull() ?: true
        val announceTools = prefs.flow(SpeechPrefs.AnnounceToolCalls).firstOrNull() ?: false
        val pcmStreaming = runCatching { speech.resolvedTtsSupportsStreamingPcm() }.getOrDefault(false)

        send(VoiceLoopState.Thinking(""))
        val ttsQueue = Channel<String>(Channel.UNLIMITED)
        val ttsJob = launch {
            try {
                if (speakReplies) {
                    drainTts(this@channelFlow, ttsQueue, pcmStreaming)
                } else {
                    // Replies are not spoken, but the producer still writes sentences here — consume and
                    // discard them, or the unlimited channel grows for the length of the session.
                    ttsQueue.consumeEach { }
                }
            } catch (t: Throwable) {
                AideLog.w(TAG, "tts drain crashed", t)
            }
        }

        val accumulated = StringBuilder()
        val sentenceBuffer = StringBuilder()
        try {
            reason.collect { event ->
                when (event) {
                    is ReasonEvent.Warming ->
                        send(VoiceLoopState.Thinking("Loading model…"))
                    is ReasonEvent.TextDelta -> {
                        // The real delta rides on the event (B6) — no removePrefix reconstruction, so a
                        // non-monotonic update can't re-queue the whole reply to TTS.
                        if (event.delta.isNotEmpty()) {
                            accumulated.append(event.delta)
                            sentenceBuffer.append(event.delta)
                            SentenceSplitter.drainComplete(sentenceBuffer)
                                ?.forEach { ttsQueue.send(it) }
                            send(VoiceLoopState.Thinking(accumulated.toString()))
                        }
                    }
                    is ReasonEvent.Done -> {
                        accumulated.clear(); accumulated.append(event.text)
                        val tail = sentenceBuffer.toString().trim()
                        if (tail.isNotBlank()) ttsQueue.send(tail)
                        sentenceBuffer.clear()
                    }
                    is ReasonEvent.ToolAnnounce -> {
                        if (announceTools) {
                            val pretty = event.name.replace('_', ' ')
                            send(VoiceLoopState.ToolAnnouncing(event.name, "Running $pretty…"))
                            ttsQueue.send("Running $pretty.")
                        }
                    }
                    is ReasonEvent.Error ->
                        send(VoiceLoopState.Error(event.message, recoverable = true))
                }
            }
        } catch (ce: CancellationException) {
            runCatching { player.stop() }
            throw ce
        } finally {
            ttsQueue.close()
            runCatching { ttsJob.join() }
        }

        send(VoiceLoopState.Idle)
    }

    private suspend fun drainTts(
        emitter: ProducerScope<VoiceLoopState>,
        queue: Channel<String>,
        pcmStreaming: Boolean,
    ) {
        var consecutiveFailures = 0
        var surfaced = false
        for (sentence in queue) {
            emitter.send(VoiceLoopState.Speaking(sentence, queuedSentences = 0))
            var failed = false
            try {
                // A per-sentence deadline. The engine has its own init timeout now, but a synthesis that
                // starts and then never terminates — an OEM engine that drops its utterance callback — would
                // otherwise leave the voice loop sitting in Speaking for the rest of the session.
                withTimeout(speakTimeoutMs(sentence)) {
                if (pcmStreaming) {
                    val chunks = channelFlow {
                        speech.synthesize(sentence).collect { event ->
                            when (event) {
                                is TtsStreamEvent.AudioChunk -> send(event)
                                is TtsStreamEvent.End -> (event.outcome as? SpeechStreamOutcome.Error)?.let {
                                    failed = true; AideLog.w(TAG, "tts error: ${it.message}")
                                }
                            }
                        }
                    }
                    player.play(chunks)
                } else {
                    // System TTS plays the utterance out-of-band; collect awaits the terminal End.
                    speech.synthesize(sentence).collect { event ->
                        (event as? TtsStreamEvent.End)?.outcome?.let { outcome ->
                            (outcome as? SpeechStreamOutcome.Error)?.let {
                                failed = true; AideLog.w(TAG, "tts error: ${it.message}")
                            }
                        }
                    }
                }
                }
            } catch (timeout: TimeoutCancellationException) {
                failed = true
                AideLog.w(TAG, "tts timed out for a ${sentence.length}-char sentence")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failed = true
                AideLog.w(TAG, "tts playback failed for sentence", t)
            }
            // Surface PERSISTENT synthesis failure once (B7) — otherwise an engine unload / audio-focus
            // loss leaves the user with silence and no error. Recoverable: the turn loop continues.
            if (failed) {
                consecutiveFailures++
                if (consecutiveFailures >= 2 && !surfaced) {
                    surfaced = true
                    emitter.send(VoiceLoopState.Error("Couldn’t speak the reply.", recoverable = true))
                }
            } else {
                consecutiveFailures = 0
            }
        }
    }

    /**
     * How long one sentence gets. Scaled by length so a long sentence is not cut off, with a floor that
     * covers engine start-up: speech runs at roughly 15 characters a second, and this allows four times
     * that before calling the utterance lost.
     */
    private fun speakTimeoutMs(sentence: String): Long =
        (MIN_SPEAK_TIMEOUT_MS + sentence.length * MS_PER_CHAR).coerceAtMost(MAX_SPEAK_TIMEOUT_MS)

    private companion object {
        private const val TAG = "VoiceOutputChannel"
        private const val MIN_SPEAK_TIMEOUT_MS = 10_000L
        private const val MS_PER_CHAR = 270L
        private const val MAX_SPEAK_TIMEOUT_MS = 120_000L
    }
}
