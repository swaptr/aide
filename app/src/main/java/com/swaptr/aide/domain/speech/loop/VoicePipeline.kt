package com.swaptr.aide.domain.speech.loop

import android.util.Log
import com.swaptr.aide.data.chat.ChatTranscript
import com.swaptr.aide.data.chat.InMemoryChatTranscript
import com.swaptr.aide.data.model.ModelRegistryRepository
import com.swaptr.aide.data.prefs.ToolCategory
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.speech.SpeechEngineRepository
import com.swaptr.aide.domain.llm.ChatSession
import com.swaptr.aide.domain.llm.Surface
import com.swaptr.aide.domain.search.WebSearchProviderId
import com.swaptr.aide.domain.speech.MicUnavailableException
import com.swaptr.aide.domain.speech.MissingMicPermissionException
import com.swaptr.aide.domain.speech.SttStreamEvent
import com.swaptr.aide.domain.speech.TtsStreamEvent
import com.swaptr.aide.domain.speech.VoiceLoopState
import com.swaptr.aide.domain.speech.audio.AudioCapturer
import com.swaptr.aide.domain.speech.audio.AudioPlayer
import com.swaptr.aide.domain.speech.audio.SentenceSplitter
import com.swaptr.aide.domain.tools.AideToolRegistry
import com.swaptr.aide.domain.tools.WebFetchToolset
import com.swaptr.aide.domain.tools.WebSearchToolset
import com.swaptr.aide.domain.tools.fs.FileSystemToolset
import com.swaptr.aide.domain.usecase.SendChatMessageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

class VoicePipeline @Inject constructor(
    private val capturer: AudioCapturer,
    private val speech: SpeechEngineRepository,
    private val player: AudioPlayer,
    private val sendChat: SendChatMessageUseCase,
    private val registry: ModelRegistryRepository,
    private val prefs: UserPreferencesRepository,
) {

    // transcript is mutable so the controller can swap in-memory for persistent once chat row minted.
    interface VoiceSession : SendChatMessageUseCase.SessionHolder {
        var transcript: ChatTranscript
    }

    class DefaultVoiceSession : VoiceSession {
        override var transcript: ChatTranscript = InMemoryChatTranscript()
        override var session: ChatSession? = null
        override var sessionModelId: String? = null
        override var sessionEnabledGated: Set<AideToolRegistry.Gated> = emptySet()
        override var sessionWebSearchProviderId: WebSearchProviderId? = null
        override var sessionWebSearchToolset: WebSearchToolset? = null
        override var sessionWebFetchToolset: WebFetchToolset? = null
        override var sessionFileSystemToolset: FileSystemToolset? = null
        override var sessionEnabledCategories: Set<ToolCategory> = emptySet()
    }

    fun invoke(
        voiceSession: VoiceSession,
        policy: VoiceTurnPolicy = VoiceTurnPolicy.OffAfterIdleSilence(
            VoiceTurnPolicy.DEFAULT_ASSISTANT_SILENCE_MS,
        ),
    ): Flow<VoiceLoopState> = channelFlow {
        send(VoiceLoopState.Idle)
        val modelId = resolveActiveModelId()
        if (modelId == null) {
            send(VoiceLoopState.Error("Pick a default model in the main Aide app first.", true))
            return@channelFlow
        }
        val speakReplies = prefs.speakAssistantRepliesFlow.firstOrNull() ?: true
        val announceTools = prefs.announceToolCallsFlow.firstOrNull() ?: false
        val pcmStreaming = runCatching { speech.resolvedTtsSupportsStreamingPcm() }.getOrDefault(false)
        Log.i(
            TAG,
            "loop start modelId=$modelId policy=${policy::class.simpleName} " +
                "speakReplies=$speakReplies pcmStreaming=$pcmStreaming",
        )

        var cumulativeBlankMs = 0L

        try {
            outer@ while (true) {
                send(VoiceLoopState.Listening("", rmsDb = capturer.lastRmsDb))
                val listenStart = System.currentTimeMillis()
                val listenResult = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                    listenForUtterance(
                        onPartial = { partial ->
                            send(VoiceLoopState.Listening(partial, capturer.lastRmsDb))
                        },
                    )
                }
                val listenedMs = System.currentTimeMillis() - listenStart
                val finalText = listenResult ?: ""

                if (finalText.isBlank()) {
                    cumulativeBlankMs += listenedMs.coerceAtLeast(1L)
                    val decision = policy.evaluate(
                        TurnSummary(wasBlank = true, cumulativeBlankMs = cumulativeBlankMs),
                    )
                    Log.d(
                        TAG,
                        "blank turn listenedMs=$listenedMs " +
                            "cumulativeBlankMs=$cumulativeBlankMs decision=$decision",
                    )
                    if (decision == TurnDecision.Stop) {
                        send(VoiceLoopState.Idle)
                        break@outer
                    }
                    continue@outer
                }

                Log.i(TAG, "user said: '$finalText'")
                cumulativeBlankMs = 0L

                send(VoiceLoopState.Thinking(""))
                val ttsQueue = Channel<String>(Channel.UNLIMITED)
                val ttsJob = launch {
                    try {
                        if (speakReplies) drainTts(this@channelFlow, ttsQueue, pcmStreaming)
                        else for (s in ttsQueue) { }
                    } catch (t: Throwable) {
                        Log.w(TAG, "tts drain crashed", t)
                    }
                }

                val accumulated = StringBuilder()
                val sentenceBuffer = StringBuilder()
                try {
                    sendChat.invoke(
                        transcript = voiceSession.transcript,
                        modelId = modelId,
                        userText = finalText,
                        imagePath = null,
                        holder = voiceSession,
                        enabledGated = emptySet(),
                        surface = Surface.VOICE,
                    ).collect { event ->
                        when (event) {
                            is SendChatMessageUseCase.Event.Warming ->
                                send(VoiceLoopState.Thinking("Loading model…"))
                            is SendChatMessageUseCase.Event.Streaming -> {
                                val delta = event.text.removePrefix(accumulated.toString())
                                accumulated.clear(); accumulated.append(event.text)
                                if (delta.isNotEmpty()) {
                                    sentenceBuffer.append(delta)
                                    SentenceSplitter.drainComplete(sentenceBuffer)
                                        ?.forEach { ttsQueue.send(it) }
                                }
                                send(VoiceLoopState.Thinking(accumulated.toString()))
                            }
                            is SendChatMessageUseCase.Event.Done -> {
                                accumulated.clear(); accumulated.append(event.finalText)
                                val tail = sentenceBuffer.toString().trim()
                                if (tail.isNotBlank()) ttsQueue.send(tail)
                                sentenceBuffer.clear()
                            }
                            is SendChatMessageUseCase.Event.ToolCallStarted -> {
                                if (announceTools) {
                                    val pretty = event.name.replace('_', ' ')
                                    send(VoiceLoopState.ToolAnnouncing(event.name, "Running $pretty…"))
                                    ttsQueue.send("Running $pretty.")
                                }
                            }
                            is SendChatMessageUseCase.Event.Error ->
                                send(VoiceLoopState.Error(event.message, recoverable = true))
                            else -> { }
                        }
                    }
                } finally {
                    ttsQueue.close()
                    runCatching { ttsJob.join() }
                }

                send(VoiceLoopState.Idle)

                val decision = policy.evaluate(
                    TurnSummary(wasBlank = false, cumulativeBlankMs = 0L),
                )
                if (decision == TurnDecision.Stop) break@outer
            }
        } catch (ce: CancellationException) {
            runCatching { player.stop() }
            send(VoiceLoopState.Idle)
            throw ce
        } catch (mic: MissingMicPermissionException) {
            send(VoiceLoopState.Error("Microphone permission required.", recoverable = false))
        } catch (mic: MicUnavailableException) {
            send(
                VoiceLoopState.Error(
                    mic.message ?: "Microphone unavailable. Tap to retry.",
                    recoverable = true,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "voice loop failed", t)
            send(VoiceLoopState.Error(t.message ?: "Voice loop failed", recoverable = true))
        } finally {
            runCatching { voiceSession.session?.close() }
            voiceSession.session = null
            voiceSession.sessionModelId = null
            runCatching { player.stop() }
        }
    }

    // Skip AudioCapturer when engine self-captures (Android SpeechRecognizer) — opening
    // AudioCapturer races the system service on VOICE_RECOGNITION → NO_SPEECH_DETECTED.
    private suspend fun listenForUtterance(onPartial: suspend (String) -> Unit): String =
        kotlinx.coroutines.coroutineScope {
            var partial = ""
            var finalText: String? = null
            val engineOwnsAudio = speech.resolvedSttOwnsAudioInput()
            val session = if (engineOwnsAudio) null else capturer.openSession(this)
            val frames: Flow<FloatArray> = session?.frames ?: emptyFlow()
            try {
                speech.recognize(frames)
                    .transformWhile { event ->
                        emit(event)
                        event !is SttStreamEvent.Endpoint && event != SttStreamEvent.Completed
                    }
                    .collect { event ->
                        when (event) {
                            is SttStreamEvent.Partial -> {
                                partial = event.text
                                onPartial(partial)
                            }
                            is SttStreamEvent.Final -> finalText = event.text
                            SttStreamEvent.Endpoint -> { }
                            is SttStreamEvent.Error -> Log.w(TAG, "stt error: ${event.message}")
                            SttStreamEvent.Completed -> { }
                        }
                    }
            } finally {
                session?.stop()
            }
            finalText ?: partial
        }

    private suspend fun drainTts(
        emitter: ProducerScope<VoiceLoopState>,
        queue: Channel<String>,
        pcmStreaming: Boolean,
    ) {
        for (sentence in queue) {
            Log.d(TAG, "tts dequeue len=${sentence.length} pcm=$pcmStreaming")
            emitter.send(VoiceLoopState.Speaking(sentence, queuedSentences = 0))
            try {
                if (pcmStreaming) {
                    var chunkCount = 0
                    val chunks = channelFlow {
                        speech.synthesize(sentence).collect { event ->
                            when (event) {
                                is TtsStreamEvent.AudioChunk -> {
                                    chunkCount++
                                    send(event)
                                }
                                TtsStreamEvent.Completed ->
                                    Log.d(TAG, "tts synth completed chunks=$chunkCount")
                                is TtsStreamEvent.Error -> Log.w(TAG, "tts error: ${event.message}")
                                is TtsStreamEvent.Boundary -> { }
                            }
                        }
                    }
                    player.play(chunks)
                    Log.d(TAG, "tts playback drained")
                } else {
                    // System TTS plays utterance out-of-band; collect awaits Completed.
                    speech.synthesize(sentence).collect { event ->
                        when (event) {
                            is TtsStreamEvent.Error -> Log.w(TAG, "tts error: ${event.message}")
                            TtsStreamEvent.Completed -> Log.d(TAG, "system tts completed")
                            else -> Unit
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "tts playback failed for sentence", t)
            }
        }
        Log.d(TAG, "tts queue closed")
    }

    suspend fun resolveActiveModelId(): String? {
        prefs.lastUsedModelIdFlow.firstOrNull()?.let { id ->
            if (registry.findSpec(id) != null) return id
        }
        prefs.defaultModelIdFlow.firstOrNull()?.let { id ->
            if (registry.findSpec(id) != null) return id
        }
        return null
    }

    companion object {
        private const val TAG = "VoicePipeline"
        private const val LISTEN_TIMEOUT_MS = 60_000L
    }
}
