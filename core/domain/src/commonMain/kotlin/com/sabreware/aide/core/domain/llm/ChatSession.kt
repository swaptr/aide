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

    /**
     * False once this session can no longer continue the conversation it holds: an on-device turn was
     * cancelled or failed mid-generation (a native session is unusable after a cancel), or its engine was
     * unloaded underneath it. The owner then builds a fresh session from the transcript, which already holds
     * whatever partial reply was kept.
     */
    val reusable: Boolean get() = true

    override fun close()
}
