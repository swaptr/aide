package com.sabreware.aide.core.domain.io

import com.sabreware.aide.core.domain.chat.ChatTranscript
import com.sabreware.aide.core.domain.usecase.SendChatMessageUseCase
import kotlinx.coroutines.flow.Flow

/**
 * The D↔E seam for the voice surface: turn one user utterance into the reason stage's neutral
 * [ReasonEvent] stream (B5). The default binding delegates to [SendChatMessageUseCase] with
 * `Surface.VOICE` and adapts its event union to [ReasonEvent] once (see `VoiceModule`); isolating it
 * behind this functional interface keeps `VoiceTurnLoop` unit-testable without the heavy use case, and
 * matches the north-star's independent middle "reason" stage.
 *
 * (The [holder] is the chat session lifecycle handle, threaded through to the use case — a session
 * concern distinct from the now-neutral output event type.)
 */
fun interface VoiceReasoner {
    fun reason(
        transcript: ChatTranscript,
        modelId: String,
        userText: String,
        holder: SendChatMessageUseCase.SessionHolder,
    ): Flow<ReasonEvent>
}
