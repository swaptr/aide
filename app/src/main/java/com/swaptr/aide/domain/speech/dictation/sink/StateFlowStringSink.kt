package com.swaptr.aide.domain.speech.dictation.sink

import com.swaptr.aide.domain.speech.dictation.DictationSink

class StateFlowStringSink(
    private val update: (String) -> Unit,
) : DictationSink {

    override fun applyDelta(text: String, isFinal: Boolean) {
        if (isFinal && text.isBlank()) return
        update(text)
    }

    override fun reset() {
    }
}
