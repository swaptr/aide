package com.sabreware.aide.core.domain.io

import com.sabreware.aide.core.domain.chat.AidePart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Text input (Layer D): the degenerate single-emission input channel. The typed text (+ optional
 * attachment parts) is already in hand, so [capture] emits exactly one [InputEvent.Final]. Attachment
 * parts lead the Text part to match the chat-template ordering the reason stage and engines expect.
 * Constructed per send (it carries that turn's text), unlike the singleton voice channels.
 */
class TextInputChannel(
    private val text: String,
    private val attachments: List<AidePart> = emptyList(),
) : InputChannel {
    override fun capture(options: InputOptions): Flow<InputEvent> =
        flowOf(InputEvent.Final(attachments + AidePart.Text(text)))
}
