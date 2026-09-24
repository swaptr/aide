package com.sabreware.aide.platform.android.surface.ime.text

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.ExtractedText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// getExtractedText is best-effort; client may not be ready on initial bind, so retry
// with backoff to surface pre-populated fields without a user keystroke.
class TextContextRepository {

    private val _context = MutableStateFlow(TextFieldContext.Empty)
    val context: StateFlow<TextFieldContext> = _context.asStateFlow()

    @Volatile private var source: InputConnectionTextSource? = null

    private val handler = Handler(Looper.getMainLooper())
    private val retryCallbacks = mutableListOf<Runnable>()

    @Synchronized
    fun bind(newSource: InputConnectionTextSource) {
        cancelRetries()
        source?.close()
        source = newSource
        if (newSource.isSensitive) {
            _context.value = newSource.sensitivePlaceholder()
            return
        }
        val initial = newSource.subscribe() ?: TextFieldContext.Empty
        _context.value = initial
        if (!initial.hasText) scheduleRetries(newSource)
    }

    @Synchronized
    fun unbind() {
        cancelRetries()
        source?.close()
        source = null
        _context.value = TextFieldContext.Empty
    }

    fun onExtractedText(token: Int, extracted: ExtractedText?) {
        val src = source ?: return
        if (token != EXTRACTED_TEXT_TOKEN) return
        src.fromExtracted(extracted)?.let { _context.value = it }
    }

    fun onSelectionChanged(
        newSelStart: Int,
        newSelEnd: Int,
        composingStart: Int,
        composingEnd: Int,
    ) {
        val src = source ?: return
        src.fromSelection(newSelStart, newSelEnd, composingStart, composingEnd)?.let {
            _context.value = it
        }
    }

    fun refresh() {
        val src = source ?: return
        src.fetchFull()?.let { _context.value = it }
    }

    // For LLM consumers; null if unbound or sensitive. Side-effect: updates context StateFlow.
    fun fetchFull(): TextFieldContext? {
        val src = source ?: return null
        val full = src.fetchFull() ?: return null
        _context.value = full
        return full.takeIf { it.hasText }
    }

    // Falls back to selectAll trick when Compose editors refuse normal reads; flashes
    // the field, so use only on explicit user actions.
    fun fetchFullAggressive(): TextFieldContext? {
        val src = source ?: return null
        src.fetchFull()?.takeIf { it.hasText }?.let {
            _context.value = it
            return it
        }
        val current = _context.value
        val via = src.fetchAllViaSelectAll(current.selectionStart, current.selectionEnd)
            ?: return null
        _context.value = via
        return via
    }

    private fun scheduleRetries(target: InputConnectionTextSource) {
        RETRY_DELAYS_MS.forEach { delay ->
            val r = Runnable {
                synchronized(this) {
                    if (source !== target) return@Runnable
                    val snap = target.fetchFull() ?: return@Runnable
                    if (snap.hasText) _context.value = snap
                }
            }
            retryCallbacks += r
            handler.postDelayed(r, delay)
        }
    }

    private fun cancelRetries() {
        retryCallbacks.forEach(handler::removeCallbacks)
        retryCallbacks.clear()
    }

    companion object {
        const val EXTRACTED_TEXT_TOKEN = 1
        private val RETRY_DELAYS_MS = longArrayOf(50L, 200L, 600L, 1500L, 3500L)
    }
}
