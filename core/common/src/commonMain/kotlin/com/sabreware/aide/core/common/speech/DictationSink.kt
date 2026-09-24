package com.sabreware.aide.core.common.speech

interface DictationSink {
    fun applyDelta(text: String, isFinal: Boolean)

    // Must be idempotent — controller invokes it defensively from multiple paths.
    fun reset()
}
