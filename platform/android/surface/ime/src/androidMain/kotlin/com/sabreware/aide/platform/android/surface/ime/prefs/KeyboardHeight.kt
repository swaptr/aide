package com.sabreware.aide.platform.android.surface.ime.prefs

/** Keyboard height preset. [scale] multiplies the default key + body height (Gboard's
 *  "Keyboard height"). Persisted as [name]. */
enum class KeyboardHeight(val scale: Float) {
    Short(0.85f),
    Default(1f),
    Tall(1.18f),
}
