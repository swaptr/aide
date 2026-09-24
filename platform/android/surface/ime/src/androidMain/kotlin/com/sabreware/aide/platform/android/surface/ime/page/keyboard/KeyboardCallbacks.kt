package com.sabreware.aide.platform.android.surface.ime.page.keyboard

import android.view.inputmethod.EditorInfo

internal interface KeyboardCallbacks {
    fun onCommit(text: String)
    fun onBackspace()
    fun onBackspaceWord()
    fun onEnter()
    fun onShiftTap()
    fun onLayerChange(next: Layer)

    fun shift(): Shift
    fun layer(): Layer
    fun editorInfo(): EditorInfo?
}
