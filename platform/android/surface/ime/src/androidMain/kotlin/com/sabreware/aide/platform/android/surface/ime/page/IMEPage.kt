package com.sabreware.aide.platform.android.surface.ime.page

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText

interface IMEPage {

    val view: View

    val desiredHeightDp: Int get() = DEFAULT_HEIGHT_DP

    companion object {
        const val DEFAULT_HEIGHT_DP = 340
    }

    fun onAttach(host: PageHost) {}

    fun onDetach() {}

    fun onStartInput(info: EditorInfo?, restarting: Boolean) {}
    fun onStartInputView(info: EditorInfo?, restarting: Boolean) {}
    fun onWindowShown() {}
    fun onWindowHidden() {}
    fun onUpdateExtractedText(token: Int, text: ExtractedText?) {}
    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {}
    fun onFinishInputView(finishingInput: Boolean) {}
    fun onFinishInput() {}
    fun onDestroy() {}
}
