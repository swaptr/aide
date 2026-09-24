package com.sabreware.aide.platform.android.surface.ime.widget

/**
 * Port of AOSP LatinIME `PointerTrackerQueue`. Holds the active per-finger trackers in **press
 * order** so multitouch releases stay ordered — the key to n-key rollover (a fast typist presses
 * the next key before lifting the previous one).
 *
 * When a finger lifts, every finger pressed *before* it is committed first via a phantom-up, so
 * "press t, press h, lift h, lift t" still types "th", not "ht". Single-threaded (UI thread);
 * no locking needed.
 */
class PointerTrackerQueue {

    private val queue = ArrayList<PointerTracker>()

    fun add(tracker: PointerTracker) {
        queue.remove(tracker)
        queue.add(tracker)
    }

    fun remove(tracker: PointerTracker) {
        queue.remove(tracker)
    }

    /** Phantom-up + drop every finger pressed before [tracker], preserving typing order. */
    fun releaseAllPointersOlderThan(tracker: PointerTracker, eventTime: Long) {
        val it = queue.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t === tracker) break
            t.onPhantomUpEvent(eventTime)
            it.remove()
        }
    }
}
