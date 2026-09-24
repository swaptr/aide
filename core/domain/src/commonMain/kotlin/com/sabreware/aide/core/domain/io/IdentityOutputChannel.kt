package com.sabreware.aide.core.domain.io

import kotlinx.coroutines.flow.Flow

/**
 * Output channel for surfaces whose reason events ARE their presentation (the chat composer and the
 * IME): render is the identity. The seam exists so every surface composes as `input → reason → output`;
 * the actual per-event presentation stays in the consumer (ViewModel). Contrast the voice output
 * channel, which transforms the stream into speech. Stateless — construct directly, no DI needed.
 */
class IdentityOutputChannel<E> : OutputChannel<E, E> {
    override fun render(reason: Flow<E>): Flow<E> = reason
}
