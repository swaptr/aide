package com.sabreware.aide.app.llm

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.sabreware.aide.core.domain.engine.nativeStream
import kotlinx.coroutines.flow.Flow

/**
 * One LiteRT-LM run through the callback API, as a flow that stops the decode when its collector is
 * cancelled and returns only once LiteRT has acknowledged the stop.
 *
 * Never use LiteRT's own `sendMessageAsync(): Flow`. In 0.11.0 it is a `callbackFlow` with an empty
 * `awaitClose {}`, and still is in 0.17.1 (the latest on Google Maven, checked 2026-09-24): cancelling the
 * collector does not call `cancelProcess()`, so the model kept generating on the GPU after a chat was left, a
 * keyboard hidden or an assistant dismissed. The official docs do not describe cancellation at all; this was
 * read from the shipped AAR.
 */
internal fun Conversation.settledStream(send: Conversation.(MessageCallback) -> Unit): Flow<Message> =
    nativeStream(cancel = ::cancelProcess) { sink ->
        send(
            object : MessageCallback {
                override fun onMessage(message: Message) = sink.emit(message)
                override fun onDone() = sink.done()
                override fun onError(throwable: Throwable) = sink.fail(throwable)
            },
        )
    }
