package com.sabreware.aide.app.speech.audio

import android.view.inputmethod.EditorInfo
import com.sabreware.aide.platform.android.text.SensitiveFieldPolicy

class SensitiveAudioPolicy {
    fun isMicAllowed(info: EditorInfo?): Boolean = !SensitiveFieldPolicy.isSensitive(info)
}
