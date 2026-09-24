package com.sabreware.aide.platform.android.surface.ime.widget

import android.content.Context
import android.os.Looper
import android.util.SparseArray
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * The single touch-owning surface for the QWERTY area — AIDE's equivalent of LatinIME's
 * `MainKeyboardView`. It owns the raw `MotionEvent` stream and routes each pointer to its
 * [PointerTracker]; the key views it contains are inert (drawing only). This is the architecture
 * every serious IME uses (AOSP LatinIME / HeliBoard / AnySoftKeyboard / FUTO, and FlorisBoard via
 * one `pointerInteropFilter`): one surface, manual per-pointer hit-testing — never per-key click
 * listeners, which drop fast taps.
 *
 * Owns the engine collaborators and implements [PointerTracker.Env]. A fresh host is created per
 * keyboard rebuild, so all tracker/timer state is local and torn down on detach (no leakage).
 */
class KeyboardTouchHost(ctx: Context) : LinearLayout(ctx), PointerTracker.Env {

    init { orientation = VERTICAL }

    private val density = resources.displayMetrics.density
    private val hysteresisPx = HYSTERESIS_DP * density

    override val detector = KeyDetector()
    override val timer: TimerProxy = KeyTimerHandler(Looper.getMainLooper())
    override val queue = PointerTrackerQueue()
    private val trackers = SparseArray<PointerTracker>()

    override var lastUpTime = 0L
    override var lastUpX = 0
    override var lastUpY = 0
    override var moreKeysShowing = false
    override val tuning = PointerTracker.Tuning(
        noiseTimeMs = NOISE_TIME_MS,
        noiseDistSq = (NOISE_DIST_DP * density).let { it * it },
        longPressMs = LONGPRESS_MS,
        repeatStartMs = REPEAT_START_MS,
        repeatIntervalMs = REPEAT_INTERVAL_MS,
    )

    // Own the gesture so no child can hijack it.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed || detector.isEmpty()) detector.setKeyboard(this, hysteresisPx)
    }

    private fun tracker(id: Int): PointerTracker =
        trackers[id] ?: PointerTracker(id, this).also { trackers.put(id, it) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (detector.isEmpty()) detector.setKeyboard(this, hysteresisPx)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val firstFinger = event.actionMasked == MotionEvent.ACTION_DOWN
                // Own the whole gesture: stop any ancestor (page swipe / drag handle / overlay)
                // from stealing a fast-typing stream mid-flight, which would CANCEL in-flight keys.
                if (firstFinger) parent?.requestDisallowInterceptTouchEvent(true)
                // While a more-keys popup is open, ignore extra fingers (LatinIME does the same) so
                // a stray second touch can't phantom-commit the popup selection.
                if (firstFinger || !moreKeysShowing) {
                    val i = event.actionIndex
                    tracker(event.getPointerId(i)).onDownEvent(
                        event.getX(i).toInt(), event.getY(i).toInt(), event.eventTime,
                    )
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val t = trackers[event.getPointerId(i)] ?: continue
                    t.onMoveEvent(event.getX(i).toInt(), event.getY(i).toInt(), event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                trackers[event.getPointerId(i)]?.onUpEvent(
                    event.getX(i).toInt(), event.getY(i).toInt(), event.eventTime,
                )
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun cancelAll() {
        for (k in 0 until trackers.size()) trackers.valueAt(k).onCancelEvent()
    }

    override fun onDetachedFromWindow() {
        timer.cancelAllTimers()
        cancelAll()
        trackers.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        // LatinIME defaults (config-common.xml / PointerTracker): see docs/keyboard-touch.md.
        const val NOISE_TIME_MS = 40L
        const val NOISE_DIST_DP = 12.6f
        const val LONGPRESS_MS = 300L
        const val REPEAT_START_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
        const val HYSTERESIS_DP = 8f
    }
}
