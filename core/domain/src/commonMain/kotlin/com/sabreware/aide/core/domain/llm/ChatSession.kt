package com.sabreware.aide.core.domain.llm

import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import kotlinx.coroutines.flow.Flow

interface ChatSession : AutoCloseable {
    fun send(
        userMessage: AideMessage,
        dispatchContext: ToolDispatcher.Context,
    ): Flow<ChatStreamEvent>

    fun reset()

    fun cancel()

    override fun close()
}
