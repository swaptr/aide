package com.swaptr.aide.ime.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.util.forEach

// One Tracker per pointerId, fire on DOWN. Iterates getHistorySize() on MOVE for sub-frame
// samples (main cause of "lost keys" at high WPM); always intercepts to bypass child quirks.
class KeyboardTouchHost(ctx: Context) : LinearLayout(ctx) {

    init { orientation = VERTICAL }

    private val handler = Handler(Looper.getMainLooper())
    private val trackers = SparseArray<Tracker>()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> down(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> up(event, event.actionIndex)
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun down(event: MotionEvent, index: Int) {
        val pid = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        val key = hitTest(x, y) ?: return
        trackers[pid]?.cancel()
        Tracker(pid, key, x, y).also { trackers.put(pid, it); it.start() }
    }

    private fun move(event: MotionEvent) {
        if (trackers.size() == 0) return
        // Sub-frame samples first (anti-ghost), then the current sample.
        val historySize = event.historySize
        for (h in 0 until historySize) {
            for (i in 0 until event.pointerCount) {
                val pid = event.getPointerId(i)
                val t = trackers[pid] ?: continue
                t.onMove(
                    event.getHistoricalX(i, h),
                    event.getHistoricalY(i, h),
                    event.getRawX(),
                    event.getRawY(),
                )
            }
        }
        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            val t = trackers[pid] ?: continue
            t.onMove(event.getX(i), event.getY(i), event.getRawX(), event.getRawY())
        }
    }

    private fun up(event: MotionEvent, index: Int) {
        val pid = event.getPointerId(index)
        val t = trackers[pid] ?: return
        trackers.remove(pid)
        t.release()
    }

    private fun cancelAll() {
        trackers.forEach { _, t -> t.cancel() }
        trackers.clear()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    /** Walk rows + key children. Spacers + non-Key children fall through. */
    private fun hitTest(x: Float, y: Float): Key? {
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? ViewGroup ?: continue
            if (y < row.top || y >= row.bottom) continue
            val rx = x - row.left
            val ry = y - row.top
            for (j in 0 until row.childCount) {
                val c = row.getChildAt(j)
                if (rx < c.left || rx >= c.right) continue
                if (ry < c.top || ry >= c.bottom) continue
                return keyOf(c)
            }
            return null
        }
        return null
    }

    /** Per-finger state machine. Created on DOWN, dropped on UP / CANCEL. */
    private inner class Tracker(
        val pid: Int,
        var key: Key,
        var downX: Float,
        var downY: Float,
    ) {
        private var timer: Runnable? = null
        private var repeats = 0
        private var popupShown = false
        private var released = false

        fun start() {
            key.view.isPressed = true
            key.view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            key.popup?.takeIf { it.isShowing() }?.dismiss()

            // Fire base char on DOWN. Popup-capable keys retract / replace
            // on release.
            key.onTap.invoke()

            schedule()
        }

        private fun schedule() {
            val r = when {
                key.alts != null -> Runnable { openPopup() }
                key.onRepeat != null -> Runnable { tickRepeat() }
                else -> null
            }
            timer = r
            r?.let { handler.postDelayed(it, longPressMs) }
        }

        fun onMove(x: Float, y: Float, rawX: Float, rawY: Float) {
            if (released) return

            if (popupShown) {
                key.popup?.updateTouch(rawX, rawY)
                return
            }

            // Popup-capable keys hold their gesture until the popup opens —
            // user often drifts upward in anticipation of the long-press.
            if (key.alts != null) return

            // Ignore micro-wobbles.
            val dx = x - downX
            val dy = y - downY
            if (dx * dx + dy * dy < slop * slop) return

            // Still inside the source key (with hysteresis)? Stay put.
            if (isInsideInflated(key.view, x, y)) return

            // Auto-switch to whichever key sits under the finger now.
            val next = hitTest(x, y)
            cancelInternal(retractBase = false)
            if (next != null) {
                Tracker(pid, next, x, y).also { trackers.put(pid, it); it.start() }
            }
        }

        fun release() {
            if (released) return
            released = true
            key.view.isPressed = false
            timer?.let { handler.removeCallbacks(it) }

            val popup = key.popup
            if (popupShown && popup != null) {
                val dismissed = popup.wasDismissedBySlide()
                val pick = if (!dismissed) popup.selected() else null
                popup.dismiss()
                when {
                    dismissed -> key.onCancelBase?.invoke()
                    pick != null -> key.onAltChosen?.invoke(pick)
                    else -> { /* popup shown but no pick: keep base */ }
                }
            }
            // Non-popup path: base already committed on DOWN.
        }

        fun cancel() = cancelInternal(retractBase = popupShown)

        private fun cancelInternal(retractBase: Boolean) {
            if (released) return
            released = true
            key.view.isPressed = false
            timer?.let { handler.removeCallbacks(it) }
            if (popupShown) key.popup?.dismiss()
            if (retractBase) key.onCancelBase?.invoke()
            popupShown = false
            trackers.remove(pid)
        }

        private fun openPopup() {
            if (released) return
            val popup = key.popup ?: return
            val alts = key.alts ?: return
            key.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            popup.show(key.view, alts)
            popupShown = true
        }

        private fun tickRepeat() {
            if (released) return
            val cb = key.onRepeat ?: return
            repeats++
            cb.invoke(repeats)
            val delay = if (repeats >= REPEAT_FAST_AFTER) REPEAT_FAST_MS else REPEAT_INTERVAL_MS
            timer?.let { handler.postDelayed(it, delay) }
        }

        private fun isInsideInflated(view: View, x: Float, y: Float): Boolean {
            val row = view.parent as? View ?: return false
            val w = view.width
            val h = view.height
            // FlorisBoard hysteresis: ±10% horizontal, ±35% vertical.
            val padX = (w * 0.10f).toInt()
            val padY = (h * 0.35f).toInt()
            val l = row.left + view.left - padX
            val t = row.top + view.top - padY
            val r = row.left + view.right + padX
            val b = row.top + view.bottom + padY
            return x >= l && x < r && y >= t && y < b
        }
    }

    private companion object {
        const val REPEAT_INTERVAL_MS = 55L
        const val REPEAT_FAST_AFTER = 12
        const val REPEAT_FAST_MS = 30L
    }
}
