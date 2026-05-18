package com.swaptr.aide.domain.speech.dictation.sink

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.swaptr.aide.domain.speech.dictation.DictationSink

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
