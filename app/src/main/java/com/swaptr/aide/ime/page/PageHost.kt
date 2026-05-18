package com.swaptr.aide.ime.page

import android.content.Context
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import androidx.transition.Fade
import androidx.transition.TransitionManager

// Lazy lookup: Android may null the InputConnection mid-session.
class PageHost(
    context: Context,
    private val connectionProvider: () -> InputConnection?,
    private val activityStarter: (android.content.Intent) -> Unit,
    private val heightChangeListener: (Int) -> Unit = {},
) {

    val view: FrameLayout = FrameLayout(context)

    private var current: IMEPage? = null

    val currentInputConnection: InputConnection? get() = connectionProvider()

    fun startActivity(intent: android.content.Intent) = activityStarter(intent)

    fun notifyHeightChanged() {
        val h = current?.desiredHeightDp ?: return
        heightChangeListener(h)
    }

    fun setPage(page: IMEPage) {
        if (current === page) return
        current?.let { prev ->
            prev.onDetach()
            view.removeAllViews()
        }
        current = page
        TransitionManager.beginDelayedTransition(view, Fade().setDuration(CROSSFADE_MS))
        view.addView(
            page.view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        page.onAttach(this)
    }

    fun forwardStartInput(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        current?.onStartInput(info, restarting)
    }

    fun forwardStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        current?.onStartInputView(info, restarting)
    }

    fun forwardWindowShown() { current?.onWindowShown() }
    fun forwardWindowHidden() { current?.onWindowHidden() }

    fun forwardUpdateExtractedText(
        token: Int,
        text: android.view.inputmethod.ExtractedText?,
    ) {
        current?.onUpdateExtractedText(token, text)
    }

    fun forwardUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        current?.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
    }

    fun forwardFinishInputView(finishingInput: Boolean) {
        current?.onFinishInputView(finishingInput)
    }

    fun forwardFinishInput() { current?.onFinishInput() }
    fun forwardDestroy() {
        current?.onDetach()
        current?.onDestroy()
        current = null
    }

    private companion object {
        const val CROSSFADE_MS = 220L
    }
}
