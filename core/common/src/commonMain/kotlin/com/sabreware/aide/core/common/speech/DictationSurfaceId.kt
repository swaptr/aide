package com.sabreware.aide.core.common.speech

enum class DictationSurfaceId(
    /**
     * True when the surface lives inside the IME window. The in-app permission rationale draws in a
     * normal activity window that sits *under* the keyboard, so IME-hosted surfaces skip it and go
     * straight to the system dialog (launching the trampoline hides the keyboard anyway). The main
     * chat composer is a normal activity and shows the rationale.
     */
    val imeHosted: Boolean,
) {
    MAIN_CHAT_COMPOSER(imeHosted = false),
    IME_CHAT_COMPOSER(imeHosted = true),
    IME_KEYBOARD_HOST_FIELD(imeHosted = true),
    IME_TRANSFORM_HOST_FIELD(imeHosted = true),
}
