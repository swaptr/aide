package com.sabreware.aide.app.speech.io

import kotlinx.coroutines.flow.first
import com.sabreware.aide.core.domain.model.ModelGateState
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatTranscript
import com.sabreware.aide.core.domain.io.InputEvent
import com.sabreware.aide.core.domain.io.InputOptions
import com.sabreware.aide.core.domain.io.VoiceInputChannel
import com.sabreware.aide.core.domain.io.VoiceReasoner
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import com.sabreware.aide.core.domain.speech.MicUnavailableException
import com.sabreware.aide.core.domain.speech.MissingMicPermissionException
import com.sabreware.aide.app.speech.VoiceLoopState
import com.sabreware.aide.app.speech.VoiceTurnLoop
import com.sabreware.aide.app.speech.VoiceTurnPolicy
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.ToolGate
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.chat.InMemoryChatTranscript
import com.sabreware.aide.app.speech.audio.AudioPlayer
import com.sabreware.aide.app.speech.loop.TurnDecision
import com.sabreware.aide.app.speech.loop.TurnSummary
import com.sabreware.aide.app.speech.loop.evaluate
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The voice turn loop (Layer D composition): `VoiceInputChannel → SendChatMessageUseCase →
 * VoiceOutputChannel`, repeated under a [VoiceTurnPolicy]. This is the assistant surface's whole turn
 * orchestration, lifted out of the old `VoicePipeline` monolith — the listen half is now a swappable
 * input channel and the speak half a swappable output channel, joined by the content IR.
 *
 * Acquires NO residency, permission, or audio focus: `AssistantVoiceController` holds the per-session
 * STT/VAD/TTS residency and the mic permission; the capturer/[AudioPlayer] own focus. The loop keeps
 * only a top-level `player.stop()` safety net for cancellation (the output channel stops on its own too).
 */
class VoiceTurnLoopImpl(
    private val voiceInput: VoiceInputChannel,
    private val voiceOutput: VoiceOutputChannel,
    private val reasoner: VoiceReasoner,
    private val micActivity: MicActivityMonitor,
    private val player: AudioPlayer,
    private val registry: ModelRegistryRepository,
    private val selection: ModelSelectionStore,
) : VoiceTurnLoop {

    override fun newSession(): VoiceTurnLoop.VoiceSession = DefaultVoiceSession()

    class DefaultVoiceSession : VoiceTurnLoop.VoiceSession {
        override var transcript: ChatTranscript = InMemoryChatTranscript()
        override var session: ChatSession? = null
        override var sessionModelId: String? = null
        override var sessionEnabledGated: Set<ToolGate> = emptySet()
        override var sessionWebSearchProviderId: WebSearchProviderId? = null
        override var sessionEnabledCategories: Set<ToolCategory> = emptySet()
    }

    override fun run(
        voiceSession: VoiceTurnLoop.VoiceSession,
        policy: VoiceTurnPolicy,
    ): Flow<VoiceLoopState> = channelFlow {
        send(VoiceLoopState.Idle)
        val modelId = resolveActiveModelId()
        if (modelId == null) {
            send(VoiceLoopState.Error("Pick a default model in the main Aide app first.", true))
            return@channelFlow
        }
        AideLog.i(TAG, "loop start modelId=$modelId policy=${policy::class.simpleName}")

        var cumulativeBlankMs = 0L
        // A listen that FAILS returns in about a millisecond, and the failure is not fatal to the loop — so
        // on a device with no working recogniser this used to run roughly fifteen thousand turns in the
        // fifteen seconds before the blank-time policy stopped it. Consecutive failures back off, and enough
        // of them in a row is reported as an error instead of pretending to listen.
        var consecutiveFailures = 0
        try {
            outer@ while (true) {
                send(VoiceLoopState.Listening("", rmsDb = micActivity.state.value.rmsDb))
                val listenStart = System.currentTimeMillis()
                val turn = withTimeoutOrNull(LISTEN_TIMEOUT_MS) { listen(this@channelFlow) }
                val finalText = turn?.text.orEmpty()
                val failed = turn?.error != null
                val listenedMs = System.currentTimeMillis() - listenStart

                if (finalText.isBlank()) {
                    cumulativeBlankMs += listenedMs.coerceAtLeast(1L)
                    val decision = policy.evaluate(
                        TurnSummary(wasBlank = true, cumulativeBlankMs = cumulativeBlankMs),
                    )
                    if (decision == TurnDecision.Stop) {
                        send(VoiceLoopState.Idle)
                        break@outer
                    }
                    // A turn that came back instantly did not listen to anything, whether or not it said so.
                    if (failed || listenedMs < MIN_PRODUCTIVE_TURN_MS) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            AideLog.w(TAG, "listen failed $consecutiveFailures times in a row — stopping")
                            send(
                                VoiceLoopState.Error(
                                    turn?.error?.ifBlank { null }
                                        ?: "Speech recognition is unavailable on this device.",
                                    recoverable = true,
                                ),
                            )
                            break@outer
                        }
                        val backoffMs = FAILURE_BACKOFF_BASE_MS shl
                            (consecutiveFailures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
                        AideLog.w(TAG, "listen produced nothing in ${listenedMs}ms — backing off ${backoffMs}ms")
                        delay(backoffMs)
                    }
                    continue@outer
                }

                AideLog.i(TAG, "user said: '$finalText'")
                cumulativeBlankMs = 0L
                consecutiveFailures = 0

                // reason → speak: the output channel emits Thinking/Speaking/Idle as it consumes the stream.
                val reason = reasoner.reason(voiceSession.transcript, modelId, finalText, voiceSession)
                voiceOutput.render(reason).collect { send(it) }

                val decision = policy.evaluate(TurnSummary(wasBlank = false, cumulativeBlankMs = 0L))
                if (decision == TurnDecision.Stop) break@outer
            }
        } catch (ce: CancellationException) {
            runCatching { player.stop() }
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
            AideLog.w(TAG, "voice loop failed", t)
            send(VoiceLoopState.Error(t.message ?: "Voice loop failed", recoverable = true))
        } finally {
            runCatching { voiceSession.session?.close() }
            voiceSession.session = null
            voiceSession.sessionModelId = null
            runCatching { player.stop() }
        }
    }

    /** What one listen produced: the transcript, and whether the engine reported a failure while doing it. */
    private data class ListenResult(val text: String, val error: String?)

    /** Listen for one utterance, emitting Listening partials; returns the final text (or last partial). */
    private suspend fun listen(out: ProducerScope<VoiceLoopState>): ListenResult {
        var finalText = ""
        var lastPartial = ""
        var error: String? = null
        voiceInput.capture(InputOptions()).collect { ev ->
            when (ev) {
                is InputEvent.Partial -> {
                    val partialText = ev.parts.textContent()
                    lastPartial = partialText
                    // Mic level is a voice-surface concern sampled from the shared MicActivityMonitor
                    // (B3) — it no longer rides on the modality-agnostic InputEvent.
                    out.send(VoiceLoopState.Listening(partialText, rmsDb = micActivity.state.value.rmsDb))
                }
                is InputEvent.Final -> finalText = ev.parts.textContent()
                // Still not fatal to the turn — but it is no longer indistinguishable from silence. The
                // caller needs it to tell "the user said nothing" from "this engine cannot listen".
                is InputEvent.Error -> error = ev.message
            }
        }
        return ListenResult(finalText.ifBlank { lastPartial }, error)
    }

    /**
     * The model this voice session talks to: the user's chosen chat model, resolved the same way every
     * surface resolves it ([ModelRegistryRepository.resolve]) — waiting for its own source to answer rather
     * than guessing from a half-loaded catalog, refusing an unusable pick, and honouring the reroute setting
     * (a stand-in is logged; the choice itself is never changed).
     */
    override suspend fun resolveActiveModelId(): String? {
        val settled = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            val chosen = selection.current().chosenFor(Modality.Chat) ?: return@withTimeoutOrNull null
            registry.resolve(chosen).first { it !is ModelGateState.Unresolved && it !is ModelGateState.Downloading }
        }
        return when (settled) {
            is ModelGateState.Ready -> {
                settled.reroutedFrom?.let {
                    AideLog.w(TAG, "chosen model ${it.id} unavailable; rerouted to ${settled.spec.id} by setting")
                }
                settled.spec.id
            }
            else -> null
        }
    }

    private fun List<AidePart>.textContent(): String =
        filterIsInstance<AidePart.Text>().joinToString(separator = "") { it.text }

    private companion object {
        private const val TAG = "VoiceTurnLoop"
        private const val LISTEN_TIMEOUT_MS = 60_000L

        // How long a voice start waits for the chosen model's own source (its provider's cached catalog, or
        // a first fetch) before giving up with "no model" rather than hanging the session.
        private const val RESOLVE_TIMEOUT_MS = 10_000L

        // Backoff for a listen that produced nothing. Base 250ms doubling to a 4s ceiling, and five in a
        // row is reported as an error rather than retried forever: on a device with no recognition service
        // every turn returns instantly, and without this the loop simply span.
        private const val MIN_PRODUCTIVE_TURN_MS = 250L
        private const val FAILURE_BACKOFF_BASE_MS = 250L
        private const val MAX_BACKOFF_SHIFT = 4
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
