package com.sabreware.aide.ui.speech

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.sabreware.aide.core.common.speech.DictationSink

class TextFieldStateSink(
    private val state: TextFieldState,
) : DictationSink {

    override fun applyDelta(text: String, isFinal: Boolean) {
        // Preserve composer text on empty final.
        if (isFinal && text.isBlank()) return
        state.setTextAndPlaceCursorAtEnd(text)
    }

    override fun reset() {
    }
}
