package com.swaptr.aide.ime.page.keyboard

import android.view.inputmethod.EditorInfo

internal interface KeyboardCallbacks {
    fun onCommit(text: String)
    fun onReplaceLastChar(text: String)
    fun onDeleteLastChar()
    fun onBackspace()
    fun onBackspaceWord()
    fun onEnter()
    fun onShiftTap()
    fun onLayerChange(next: Layer)

    fun shift(): Shift
    fun layer(): Layer
    fun editorInfo(): EditorInfo?
}
