package com.sabreware.aide.core.domain.chat

/**
 * Creates transcript instances so presentation/use-cases never construct concrete data-layer impls:
 * the persistent (chat-store-backed) variant by id, and the in-memory (incognito) variant the UI
 * both writes to and observes.
 */
interface ChatTranscriptFactory {
    fun createInMemory(): ObservableChatTranscript

    /** Persistent transcript backed by the chat store for [chatId]. */
    fun createPersistent(chatId: String): ChatTranscript
}
