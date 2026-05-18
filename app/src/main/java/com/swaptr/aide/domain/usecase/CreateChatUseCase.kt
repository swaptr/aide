package com.swaptr.aide.domain.usecase

import com.swaptr.aide.data.chat.ChatEntity
import com.swaptr.aide.data.chat.ChatRepository
import javax.inject.Inject

class CreateChatUseCase @Inject constructor(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(): ChatEntity = chats.createChat()
}
