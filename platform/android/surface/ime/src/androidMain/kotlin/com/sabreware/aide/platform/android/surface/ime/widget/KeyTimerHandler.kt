package com.sabreware.aide.platform.android.surface.ime.widget

import android.os.Handler
import android.os.Looper
import android.os.Message

/** Decouples [PointerTracker] from the concrete Handler (LatinIME's `TimerProxy`). */
interface TimerProxy {
    fun startLongPressTimer(tracker: PointerTracker, delayMs: Long)
    fun cancelLongPressTimer(tracker: PointerTracker)
    fun startKeyRepeatTimer(tracker: PointerTracker, delayMs: Long)
    fun cancelKeyRepeatTimer(tracker: PointerTracker)
    fun cancelAllTimers()
}

/**
 * Port of AOSP LatinIME `TimerHandler`. Long-press and auto-repeat are delayed messages on the
 * **UI thread** — the same thread that processes touch — so a timer can never fire interleaved
 * with a half-processed touch sample. Auto-repeat is a self-re-arming `MSG_REPEAT` (start ≈400 ms,
 * interval ≈50 ms); long-press is a single `MSG_LONGPRESS` cancelled on move-off / up.
 */
class KeyTimerHandler(looper: Looper) : Handler(looper), TimerProxy {

    override fun handleMessage(msg: Message) {
        val tracker = msg.obj as? PointerTracker ?: return
        when (msg.what) {
            MSG_LONGPRESS -> tracker.onLongPressed()
            MSG_REPEAT -> tracker.onKeyRepeat()
        }
    }

    override fun startLongPressTimer(tracker: PointerTracker, delayMs: Long) {
        removeMessages(MSG_LONGPRESS, tracker)
        sendMessageDelayed(obtainMessage(MSG_LONGPRESS, tracker), delayMs)
    }

    override fun cancelLongPressTimer(tracker: PointerTracker) {
        removeMessages(MSG_LONGPRESS, tracker)
    }

    override fun startKeyRepeatTimer(tracker: PointerTracker, delayMs: Long) {
        removeMessages(MSG_REPEAT, tracker)
        sendMessageDelayed(obtainMessage(MSG_REPEAT, tracker), delayMs)
    }

    override fun cancelKeyRepeatTimer(tracker: PointerTracker) {
        removeMessages(MSG_REPEAT, tracker)
    }

    override fun cancelAllTimers() {
        removeMessages(MSG_LONGPRESS)
        removeMessages(MSG_REPEAT)
    }

    private companion object {
        const val MSG_LONGPRESS = 1
        const val MSG_REPEAT = 2
    }
}
