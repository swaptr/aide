package com.swaptr.aide.domain.speech.dictation

interface DictationSink {
    fun applyDelta(text: String, isFinal: Boolean)

    // Must be idempotent — controller invokes it defensively from multiple paths.
    fun reset()
}
