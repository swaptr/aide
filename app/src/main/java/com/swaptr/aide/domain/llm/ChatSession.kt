package com.swaptr.aide.domain.llm

import com.swaptr.aide.data.chat.AideMessage
import com.swaptr.aide.domain.llm.dispatch.ToolDispatcher
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
