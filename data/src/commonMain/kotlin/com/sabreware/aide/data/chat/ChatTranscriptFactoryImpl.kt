package com.sabreware.aide.data.chat

import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.ChatTranscript
import com.sabreware.aide.core.domain.chat.ChatTranscriptFactory
import com.sabreware.aide.core.domain.chat.ObservableChatTranscript

class ChatTranscriptFactoryImpl(
    private val chats: ChatRepository,
) : ChatTranscriptFactory {
    override fun createInMemory(): ObservableChatTranscript = InMemoryChatTranscript()

    override fun createPersistent(chatId: String): ChatTranscript =
        PersistentChatTranscript(chats, chatId)
}
