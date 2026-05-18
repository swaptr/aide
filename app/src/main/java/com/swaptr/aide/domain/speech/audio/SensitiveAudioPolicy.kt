package com.swaptr.aide.domain.speech.audio

import android.view.inputmethod.EditorInfo
import com.swaptr.aide.ime.text.SensitiveFieldPolicy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensitiveAudioPolicy @Inject constructor() {
    fun isMicAllowed(info: EditorInfo?): Boolean = !SensitiveFieldPolicy.isSensitive(info)
}
