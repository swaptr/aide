package com.sabreware.aide.app.speech.dictation.sink

import com.sabreware.aide.core.common.speech.DictationSink

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
