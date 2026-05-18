package com.swaptr.aide.ime.text

import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

interface TextContextSource {
    val sensitiveReason: SensitiveReason
    fun snapshot(): TextFieldContext?
    fun close()
}

// Lazy connection: getCurrentInputConnection() can be null at onStartInput then non-null
// later; caching would freeze a stale null into every retry.
class InputConnectionTextSource(
    private val connectionProvider: () -> InputConnection?,
    private val editorInfo: EditorInfo?,
    private val packageName: String?,
    private val token: Int,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) : TextContextSource {

    @Volatile private var closed = false

    override val sensitiveReason: SensitiveReason =
        SensitiveFieldPolicy.classify(editorInfo)

    val isSensitive: Boolean get() = sensitiveReason != SensitiveReason.None

    val fieldId: Int = editorInfo?.fieldId ?: 0

    fun subscribe(): TextFieldContext? {
        if (closed || isSensitive) return null
        val ic = connectionProvider() ?: return null
        runCatching { ic.getExtractedText(buildRequest(), InputConnection.GET_EXTRACTED_TEXT_MONITOR) }
        return fetchFull()
    }

    override fun snapshot(): TextFieldContext? = fetchFull()

    fun fetchFull(): TextFieldContext? {
        if (closed || isSensitive) return null
        val ic = connectionProvider() ?: return null

        val ext = runCatching { ic.getExtractedText(buildRequest(), 0) }
            .onFailure { Log.w(TAG, "getExtractedText threw", it) }
            .getOrNull()
        if (ext != null) {
            val partial = ext.partialStartOffset != -1 || ext.partialEndOffset != -1
            if (!partial && (ext.text?.isNotEmpty() == true)) {
                return toContext(ext)
            }
        }

        val surrounding = runCatching {
            ic.getSurroundingText(maxChars / 2, maxChars / 2, 0)
        }.onFailure { Log.w(TAG, "getSurroundingText threw", it) }.getOrNull()
        if (surrounding != null) {
            val text = surrounding.text.toString()
            if (text.isNotEmpty()) {
                val offset = surrounding.offset.coerceAtLeast(0)
                val selStart = (surrounding.selectionStart - offset).coerceIn(0, text.length)
                val selEnd = (surrounding.selectionEnd - offset).coerceIn(selStart, text.length)
                return TextFieldContext(
                    text = text,
                    selectionStart = selStart,
                    selectionEnd = selEnd,
                    composingStart = -1,
                    composingEnd = -1,
                    sensitiveReason = SensitiveReason.None,
                    packageName = packageName,
                    fieldId = fieldId,
                )
            }
        }

        val before = runCatching { ic.getTextBeforeCursor(maxChars / 2, 0) }.getOrNull() ?: ""
        val after = runCatching { ic.getTextAfterCursor(maxChars / 2, 0) }.getOrNull() ?: ""
        val merged = before.toString() + after.toString()
        if (merged.isEmpty()) return null
        return TextFieldContext(
            text = merged,
            selectionStart = before.length,
            selectionEnd = before.length,
            composingStart = -1,
            composingEnd = -1,
            sensitiveReason = SensitiveReason.None,
            packageName = packageName,
            fieldId = fieldId,
        )
    }

    // Last-resort selectAll → read → restore for Compose editors; flashes the field,
    // so only on explicit user action, never on keystroke.
    fun fetchAllViaSelectAll(currentSelStart: Int, currentSelEnd: Int): TextFieldContext? {
        if (closed || isSensitive) return null
        val ic = connectionProvider() ?: return null
        ic.beginBatchEdit()
        try {
            ic.performContextMenuAction(android.R.id.selectAll)
            val text = runCatching { ic.getSelectedText(0) }
                .onFailure { Log.w(TAG, "getSelectedText threw", it) }
                .getOrNull()?.toString().orEmpty()
            ic.setSelection(
                currentSelStart.coerceAtLeast(0),
                currentSelEnd.coerceAtLeast(currentSelStart),
            )
            if (text.isEmpty()) return null
            return TextFieldContext(
                text = text,
                selectionStart = currentSelStart.coerceIn(0, text.length),
                selectionEnd = currentSelEnd.coerceIn(0, text.length),
                composingStart = -1,
                composingEnd = -1,
                sensitiveReason = SensitiveReason.None,
                packageName = packageName,
                fieldId = fieldId,
            )
        } finally {
            ic.endBatchEdit()
        }
    }

    fun fromExtracted(extracted: ExtractedText?): TextFieldContext? {
        if (closed || isSensitive) return null
        if (extracted == null) return null
        // Partial updates only carry the changed slice — for LLM use we always
        // want the whole field, so re-pull synchronously when partial.
        if (extracted.partialStartOffset != -1 || extracted.partialEndOffset != -1) {
            return fetchFull()
        }
        return toContext(extracted)
    }

    fun fromSelection(
        newSelStart: Int,
        newSelEnd: Int,
        composingStart: Int,
        composingEnd: Int,
    ): TextFieldContext? {
        val full = fetchFull() ?: return null
        return full.copy(
            composingStart = composingStart,
            composingEnd = composingEnd,
            selectionStart = newSelStart.coerceIn(0, full.text.length),
            selectionEnd = newSelEnd.coerceIn(0, full.text.length),
        )
    }

    override fun close() {
        closed = true
    }

    private fun buildRequest(): ExtractedTextRequest = ExtractedTextRequest().apply {
        this.token = this@InputConnectionTextSource.token
        hintMaxChars = maxChars
        hintMaxLines = DEFAULT_MAX_LINES
        flags = 0
    }

    private fun toContext(extracted: ExtractedText): TextFieldContext {
        val text = extracted.text ?: ""
        val offset = extracted.startOffset.coerceAtLeast(0)
        val start = (extracted.selectionStart - offset).coerceIn(0, text.length)
        val end = (extracted.selectionEnd - offset).coerceIn(start, text.length)
        return TextFieldContext(
            text = text,
            selectionStart = start,
            selectionEnd = end,
            composingStart = -1,
            composingEnd = -1,
            sensitiveReason = SensitiveReason.None,
            packageName = packageName,
            fieldId = fieldId,
        )
    }

    fun sensitivePlaceholder(): TextFieldContext = TextFieldContext(
        text = "",
        selectionStart = 0,
        selectionEnd = 0,
        composingStart = -1,
        composingEnd = -1,
        sensitiveReason = sensitiveReason,
        packageName = packageName,
        fieldId = fieldId,
    )

    companion object {
        private const val TAG = "AideIme"
        // Ceiling for oversized fields; EditText ignores hint and returns full Editable.
        const val DEFAULT_MAX_CHARS = 200_000
        const val DEFAULT_MAX_LINES = 10_000
    }
}
