package com.sabreware.aide.platform.android.surface.ime.widget

import android.view.HapticFeedbackConstants
import android.view.MotionEvent

/**
 * Port of AOSP LatinIME `PointerTracker` — **one instance per finger** (pointer id). Converts that
 * pointer's raw down/move/up stream into press graphics + key output. Faithful to LatinIME:
 *
 *  - **Press on DOWN, code on UP**, keyed to the key captured at DOWN. Small finger jitter never
 *    moves the committed key (8 dp hysteresis), so a sloppy tap still types the key you pressed.
 *  - **Repeatable keys** (backspace) emit on DOWN and auto-repeat while held; they do not re-emit
 *    on UP.
 *  - **Long-press** opens the more-keys popup; releasing on it emits the chosen alternate, sliding
 *    off it emits nothing.
 *  - **Historical-sample replay** on MOVE so a fast key-to-key slide is never decimated.
 *  - **up→down noise filter** drops firmware bounce (a DOWN within 40 ms and 12.6 dp of the last
 *    UP), so hardware chatter can't ghost an extra tap.
 *
 * AIDE adaptation: a Key carries its own output lambdas (`onTap` / `onRepeat` / `onAltChosen`), so
 * this plays the role of both LatinIME's PointerTracker and its KeyboardActionListener. The current
 * key is held as its [KeyDetector.Entry] so the hot path (steady finger, hysteresis) needs no
 * per-sample lookups.
 */
class PointerTracker(val pointerId: Int, private val env: Env) {

    /** Everything the tracker needs from its host, plus shared tuning + noise-filter state. */
    interface Env {
        val detector: KeyDetector
        val timer: TimerProxy
        val queue: PointerTrackerQueue
        val tuning: Tuning
        // Last release across ANY finger — the up→down noise filter reads these.
        var lastUpTime: Long
        var lastUpX: Int
        var lastUpY: Int
        // True while any finger holds a more-keys popup; the host ignores new fingers meanwhile.
        var moreKeysShowing: Boolean
    }

    /** Timing / threshold knobs — LatinIME defaults, resolved to px by the host. */
    data class Tuning(
        val noiseTimeMs: Long,
        val noiseDistSq: Float,
        val longPressMs: Long,
        val repeatStartMs: Long,
        val repeatIntervalMs: Long,
    )

    private var current: KeyDetector.Entry? = null
    private var popupShown = false
    private var repeatCount = 0

    /** True once a repeatable key has emitted (on DOWN / via repeat) so UP must not re-emit. */
    private var consumed = false

    fun onDownEvent(x: Int, y: Int, eventTime: Long) {
        // up→down noise filter: a DOWN landing on the same spot a hair after an UP is firmware
        // bounce, not a real tap. Distance-gated so genuine fast taps on other keys still pass.
        if (eventTime - env.lastUpTime < env.tuning.noiseTimeMs) {
            val dx = (x - env.lastUpX).toFloat()
            val dy = (y - env.lastUpY).toFloat()
            if (dx * dx + dy * dy < env.tuning.noiseDistSq) return
        }
        val entry = env.detector.detect(x, y) ?: return
        current = entry
        popupShown = false
        consumed = false
        repeatCount = 0
        env.queue.add(this)
        onPress(entry)
        when {
            entry.key.isRepeatable -> startRepeat(entry.key)
            entry.key.isLongPressEnabled -> env.timer.startLongPressTimer(this, env.tuning.longPressMs)
        }
    }

    fun onMoveEvent(x: Int, y: Int, me: MotionEvent) {
        val idx = me.findPointerIndex(pointerId)
        if (popupShown) {
            // Raw (screen) coords only matter to the popup, so read them only on this path.
            if (idx >= 0) current?.key?.popup?.updateTouch(me.getRawX(idx), me.getRawY(idx))
            return
        }
        // Replay coalesced sub-frame samples so a fast slide registers each key it crossed.
        if (idx >= 0) {
            for (h in 0 until me.historySize) {
                onMoveInternal(me.getHistoricalX(idx, h).toInt(), me.getHistoricalY(idx, h).toInt())
            }
        }
        onMoveInternal(x, y)
    }

    private fun onMoveInternal(x: Int, y: Int) {
        val cur = current ?: return
        // Fast path: finger still on its own cell (the overwhelming majority of move samples).
        if (cur.contains(x, y)) return
        val next = env.detector.detect(x, y) ?: return
        if (next === cur) return
        // Hysteresis: only switch once the finger is a clear 8 dp past the current key's edge.
        if (cur.distSqToEdge(x, y) < env.detector.hysteresisDistanceSq()) return
        env.timer.cancelLongPressTimer(this)
        env.timer.cancelKeyRepeatTimer(this)
        onRelease(cur.key, linger = false)
        current = next
        consumed = false
        repeatCount = 0
        onPress(next)
        when {
            next.key.isRepeatable -> startRepeat(next.key)
            next.key.isLongPressEnabled ->
                env.timer.startLongPressTimer(this, env.tuning.longPressMs * SLIDING_LONGPRESS_MULT)
        }
    }

    fun onUpEvent(x: Int, y: Int, eventTime: Long) {
        // Commit older fingers first (rollover order) before this one.
        env.queue.releaseAllPointersOlderThan(this, eventTime)
        emitAndRelease()
        env.queue.remove(this)
        env.lastUpTime = eventTime
        env.lastUpX = x
        env.lastUpY = y
    }

    /** Synthetic up for a still-held older finger when a newer finger lifts (see queue). */
    fun onPhantomUpEvent(eventTime: Long) {
        // A finger holding a more-keys popup resolves only on its OWN real up, never because a
        // different finger lifted — otherwise a second tap would commit the popup early.
        if (popupShown) return
        emitAndRelease()
    }

    private fun emitAndRelease() {
        env.timer.cancelLongPressTimer(this)
        env.timer.cancelKeyRepeatTimer(this)
        val key = current?.key ?: return
        current = null
        onRelease(key, linger = true)
        if (popupShown) {
            val popup = key.popup
            val slidOff = popup?.wasDismissedBySlide() == true
            val pick = if (!slidOff) popup?.selected() else null
            popup?.dismiss()
            popupShown = false
            env.moreKeysShowing = false
            if (pick != null) key.onAltChosen?.invoke(pick)
            return
        }
        if (consumed) return            // repeatable key already emitted on DOWN / via repeats
        key.onTap.invoke()              // ← the tap fires here, on UP, on the DOWN-captured key
    }

    fun onCancelEvent() {
        env.timer.cancelLongPressTimer(this)
        env.timer.cancelKeyRepeatTimer(this)
        current?.let { onRelease(it.key, linger = false) }
        if (popupShown) {
            current?.key?.popup?.dismiss()
            env.moreKeysShowing = false
        }
        popupShown = false
        current = null
        env.queue.remove(this)
    }

    fun onLongPressed() {
        val key = current?.key ?: return
        val alts = key.alts ?: return
        val popup = key.popup ?: return
        key.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        popup.show(key.view, alts)
        popupShown = true
        env.moreKeysShowing = true
    }

    private fun startRepeat(key: Key) {
        consumed = true                 // repeatable keys output here, never again on UP
        repeatCount = 1
        key.onTap.invoke()              // first emit, on DOWN
        env.timer.startKeyRepeatTimer(this, env.tuning.repeatStartMs)
    }

    fun onKeyRepeat() {
        val key = current?.key ?: return
        val cb = key.onRepeat ?: return
        repeatCount++
        cb.invoke(repeatCount)
        env.timer.startKeyRepeatTimer(this, env.tuning.repeatIntervalMs)
    }

    private fun onPress(entry: KeyDetector.Entry) {
        val view = entry.key.view
        view.removeCallbacks(view.unpressAction)   // cancel a pending linger from a prior tap
        view.isPressed = true
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // linger=true holds the highlight a beat past release so even a fast tap flashes visibly
    // (LatinIME dismisses its key preview with the same delay); slide-off / cancel clear at once.
    private fun onRelease(key: Key, linger: Boolean) {
        val view = key.view
        view.removeCallbacks(view.unpressAction)
        if (linger) view.postDelayed(view.unpressAction, PRESS_HOLD_MS) else view.isPressed = false
    }

    private companion object {
        const val SLIDING_LONGPRESS_MULT = 3L

        /** Min time the pressed highlight stays visible after release, so fast taps still flash. */
        const val PRESS_HOLD_MS = 80L
    }
}
