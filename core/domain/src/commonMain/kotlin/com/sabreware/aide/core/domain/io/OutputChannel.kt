package com.sabreware.aide.core.domain.io

import kotlinx.coroutines.flow.Flow

/**
 * Layer D — the output half of a turn. Consumes a reason stage's event stream [E] and renders it in a
 * modality, emitting that modality's presentation state [S].
 *
 * Generic in [E] because the two real surfaces feed genuinely different event types: the chat composer
 * is an identity passthrough over its own `SendChatMessageUseCase.Event` ([S] = [E], see
 * [IdentityOutputChannel]) — per-event reaction (streaming patch, tool chips, thinking, stats) is
 * surface-specific presentation that stays in the ViewModel — while voice output is the substantive
 * transform over the neutral [ReasonEvent], sentence-splitting the stream into speech and emitting
 * `VoiceLoopState`. (The IME drives `RunTaskUseCase` directly and does NOT use this seam.)
 */
interface OutputChannel<in E, out S> {
    fun render(reason: Flow<E>): Flow<S>
}
