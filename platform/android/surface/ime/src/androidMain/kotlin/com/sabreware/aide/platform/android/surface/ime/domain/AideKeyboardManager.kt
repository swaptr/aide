package com.sabreware.aide.platform.android.surface.ime.domain

/**
 * Reads and drives the Aide input method's standing with the system: whether it's enabled and active,
 * plus the two system surfaces that change that (the enable list and the input switcher). UI depends
 * on this contract instead of touching `InputMethodManager` / `Settings.Secure` / intents directly.
 *
 * State is pull-based: there's no public broadcast for "selected IME changed", and a persistent
 * `ContentObserver` proved unreliable for picker selections — so callers re-read [currentStatus] on the
 * window-focus-gained signal (the IME picker is an overlay; dismissing it returns focus to the app, and
 * returning from the system keyboard settings regains focus too).
 */
interface AideKeyboardManager {

    /** The IME's current standing — a cheap, synchronous live read. */
    fun currentStatus(): AideKeyboardStatus

    /** Open the system enabled-keyboards list, where the user turns Aide on. */
    fun openEnableSettings()

    /** Open the system input-method switcher, where the user picks Aide as the active keyboard. */
    fun openSwitcher()
}
