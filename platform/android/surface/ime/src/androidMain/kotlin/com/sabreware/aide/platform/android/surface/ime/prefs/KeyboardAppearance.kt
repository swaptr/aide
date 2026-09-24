package com.sabreware.aide.platform.android.surface.ime.prefs

import com.sabreware.aide.core.common.prefs.PrefSnapshot

/**
 * Aggregated user-adjustable keyboard look/behaviour, observed live by the IME so a settings change
 * re-styles the running keyboard without a restart. Each field maps to one persisted pref.
 */
data class KeyboardAppearance(
    val keyStyle: KeyStyle = KeyStyle.Borderless,
    val height: KeyboardHeight = KeyboardHeight.Default,
    val numberRow: Boolean = false,
    /** When false, the assistant bar (tasks / rewrite / mic) is hidden — a plain keyboard. */
    val aiEnabled: Boolean = true,
)

/** The keyboard's look as one prefs snapshot reads it — the IME and its settings screen share this. */
fun PrefSnapshot.keyboardAppearance() = KeyboardAppearance(
    keyStyle = this[KeyboardPrefs.KeyStyle],
    height = this[KeyboardPrefs.Height],
    numberRow = this[KeyboardPrefs.NumberRowEnabled],
    aiEnabled = this[KeyboardPrefs.AiEnabled],
)
