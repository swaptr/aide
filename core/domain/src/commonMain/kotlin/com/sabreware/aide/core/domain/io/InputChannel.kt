package com.sabreware.aide.core.domain.io

import com.sabreware.aide.core.domain.chat.AidePart
import kotlinx.coroutines.flow.Flow

/**
 * Layer D — the input half of a turn. A swappable source of user input that produces content-IR
 * parts for the reason stage, independent of modality. Voice streams [InputEvent.Partial]s (live
 * transcript parts) then a single [InputEvent.Final]; text emits one [InputEvent.Final]
 * immediately. Dictation reuses a voice channel but routes the partials/final to a text sink
 * instead of submitting a turn.
 *
 * Channels declare no [kotlinx.coroutines.CoroutineScope] and no dispatcher — confinement lives
 * inside the engines (system STT is `flowOn(Main.immediate)`, Sherpa is single-thread IO); the
 * collector's context is inherited. They also never acquire residency or audio focus — that stays
 * in the per-surface composition root (e.g. `AssistantVoiceController`, `AudioCapturer`).
 */
interface InputChannel {
    fun capture(options: InputOptions = InputOptions()): Flow<InputEvent>
}

/** One step of input production. Terminates with exactly one [Final], or an [Error] then nothing. */
sealed interface InputEvent {
    /**
     * Partial, in-progress input as content-IR parts (e.g. the live transcript as an [AidePart.Text]);
     * [progress] is an optional 0..1 completion hint for sources that have one. Modality-agnostic by
     * design — voice-only signals (mic RMS level) live on the voice surface, not on this event (B3).
     */
    data class Partial(val parts: List<AidePart>, val progress: Float? = null) : InputEvent

    /** The committed user turn as content-IR parts (e.g. [AidePart.Text] or [AidePart.AudioFile]). */
    data class Final(val parts: List<AidePart>) : InputEvent

    data class Error(val message: String, val recoverable: Boolean = true) : InputEvent
}

/**
 * How a channel should produce input. [micAllowed] lets a surface veto microphone capture (e.g.
 * dictation on a sensitive password field); a voice channel that receives `false` emits an
 * [InputEvent.Error] instead of opening the mic. Ignored by text channels. (ASR-decode `SttOptions`
 * are a voice concern owned by the voice channel, not this modality-agnostic options bag — B3.)
 */
data class InputOptions(
    val micAllowed: Boolean = true,
)
