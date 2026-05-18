package com.swaptr.aide.domain.speech.dictation.sink

import android.view.inputmethod.InputConnection
import com.swaptr.aide.domain.speech.dictation.DictationSink

// Connection fetched lazily — IME hosts re-bind across field focus changes and the
// controller's job can outlive a single bind window.
class InputConnectionSink(
    private val connection: () -> InputConnection?,
) : DictationSink {

    private var partialLen: Int = 0

    override fun applyDelta(text: String, isFinal: Boolean) {
        val ic = connection() ?: return
        ic.beginBatchEdit()
        try {
            if (partialLen > 0) ic.deleteSurroundingText(partialLen, 0)
            if (text.isNotEmpty()) ic.commitText(text, 1)
            partialLen = text.length
        } finally {
            ic.endBatchEdit()
        }
    }

    override fun reset() {
        partialLen = 0
    }
}
