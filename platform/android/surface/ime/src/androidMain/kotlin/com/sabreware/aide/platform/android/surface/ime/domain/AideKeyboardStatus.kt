package com.sabreware.aide.platform.android.surface.ime.domain

/** Where the Aide IME stands with the system — the single axis the Keyboard setup UI branches on. */
enum class AideKeyboardStatus {
    /** Not in the system's enabled-keyboards list — the user must turn it on first. */
    NotEnabled,

    /** Enabled, but another IME is currently active — the user can switch to Aide. */
    EnabledInactive,

    /** Aide is the active (default) keyboard — nothing left to do. */
    Active,
}
