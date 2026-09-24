package com.sabreware.aide.platform.android.surface.ime.speech

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.sabreware.aide.core.common.speech.DictationSink
import com.sabreware.aide.platform.android.text.SensitiveFieldPolicy

/**
 * Writes dictation into whatever field the keyboard is attached to right now.
 *
 * Connection AND editor info are both fetched lazily, per delta: an IME re-binds across focus changes and
 * the controller's job outlives a single bind window. That is also why the sensitive-field check lives
 * here rather than at the mic button — the button's answer was a snapshot taken at tap time, so a dictation
 * started on an ordinary field kept transcribing into a password field once focus moved. The field that is
 * about to be written to is the only one whose sensitivity matters.
 */
class InputConnectionSink(
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
) : DictationSink {

    private var partialLen: Int = 0

    override fun applyDelta(text: String, isFinal: Boolean) {
        // Backstop, not the primary defence: the service stops dictation outright when focus lands on a
        // sensitive field. This guarantees that even a delta already in flight never reaches one.
        if (SensitiveFieldPolicy.isSensitive(editorInfo())) {
            partialLen = 0
            return
        }
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
