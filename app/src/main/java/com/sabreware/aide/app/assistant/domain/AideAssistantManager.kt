package com.sabreware.aide.app.assistant.domain

/**
 * Reads and drives Aide's standing as the device digital assistant (the assist app the system binds to
 * the home gesture / power-button hold). UI depends on this contract instead of touching `RoleManager`
 * / `Settings` intents directly.
 *
 * State is pull-based: there's no broadcast for "assistant changed", so callers re-read [currentStatus]
 * on the window-focus-gained signal (returning from the system Assist settings screen regains focus).
 */
interface AideAssistantManager {

    /** Aide's current standing as the device assistant — a cheap, synchronous live read. */
    fun currentStatus(): AideAssistantStatus

    /** Open the system "Assist & voice input" settings, where the user picks Aide as the assistant. */
    fun openAssistantSettings()
}
