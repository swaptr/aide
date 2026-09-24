package com.sabreware.aide.core.common.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform port for push-to-talk dictation across the app's input surfaces (main chat composer, IME
 * fields). Implemented in the data layer over the STT engine; consumers (feature ViewModels, the IME
 * service) depend only on this port.
 *
 * commonMain interface — field sensitivity is passed as a portable [micAllowed] boolean (the IME computes
 * it from its `EditorInfo` via `SensitiveFieldPolicy`, which stays Android-only), so the port speaks no
 * android types. Injected via Koin.
 *
 * Single-active invariant: one [DictationSink] receives recognised text at a time (one AudioRecord
 * per process); the impl serialises toggle/stop so cancel-then-start is atomic against rapid taps.
 */
interface DictationController {

    fun stateFor(surface: DictationSurfaceId): StateFlow<DictationState>

    fun registerSurface(surface: DictationSurfaceId)

    fun unregisterSurface(surface: DictationSurfaceId)

    /** [micAllowed] = false suppresses the mic for a sensitive field (e.g. a password IME field). */
    fun toggle(surface: DictationSurfaceId, sink: DictationSink, micAllowed: Boolean = true)

    fun stop(surface: DictationSurfaceId? = null)
}
