package com.sabreware.aide.platform.android.surface.ime.text

import com.sabreware.aide.platform.android.text.SensitiveReason

data class TextFieldContext(
    val text: CharSequence,
    val selectionStart: Int,
    val selectionEnd: Int,
    val composingStart: Int,
    val composingEnd: Int,
    val sensitiveReason: SensitiveReason,
    val packageName: String?,
    val fieldId: Int,
) {
    val isSensitive: Boolean get() = sensitiveReason != SensitiveReason.None

    val hasText: Boolean get() = text.isNotEmpty()

    val textBeforeCursor: CharSequence
        get() = if (selectionStart in 0..text.length) text.subSequence(0, selectionStart) else ""

    val textAfterCursor: CharSequence
        get() {
            val end = selectionEnd.coerceIn(0, text.length)
            return text.subSequence(end, text.length)
        }

    companion object {
        val Empty: TextFieldContext = TextFieldContext(
            text = "",
            selectionStart = 0,
            selectionEnd = 0,
            composingStart = -1,
            composingEnd = -1,
            sensitiveReason = SensitiveReason.None,
            packageName = null,
            fieldId = 0,
        )
    }
}
