package com.sabreware.aide.data.model

import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.Tier
import com.sabreware.aide.core.common.prefs.nullableStringKey
import com.sabreware.aide.core.domain.model.NativeLoadJournal

/**
 * [NativeLoadJournal] over the preference store.
 *
 * DataStore is the right backing here for the unglamorous reason that it is the only durable writer both
 * targets already have on the load path. The write must land before the JNI call, so it is awaited rather
 * than fired off — a marker written asynchronously is a marker that loses the race it exists to win.
 *
 * `Tier.UiState`, not `Settings`: this is a diagnostic breadcrumb, not something the user chose, and it is
 * fine to lose on a wipe. The `ui.` prefix is the tier's own naming rule, enforced by `PrefKey`'s init.
 */
class PreferenceNativeLoadJournal(private val prefs: PreferenceStore) : NativeLoadJournal {

    /**
     * Two keys, not one. The in-flight marker is cleared on every normal return, so by the time anything
     * asks "did we crash?", it is already gone in the happy case. Startup promotes a surviving in-flight
     * marker into [Crashed] exactly once, and that is what survives until acknowledged.
     */
    override suspend fun begin(key: String) = prefs.set(InFlight, key)

    override suspend fun finish() = prefs.set(InFlight, null)

    override suspend fun crashedKey(): String? {
        prefs.get(InFlight)?.let { survivor ->
            // Promote and clear in-flight: a second launch must not re-promote, and the crashed entry has
            // to outlive this read in case the app dies again before anyone acts on it.
            prefs.set(Crashed, survivor)
            prefs.set(InFlight, null)
        }
        return prefs.get(Crashed)
    }

    override suspend fun acknowledge() = prefs.set(Crashed, null)

    private companion object {
        val InFlight = nullableStringKey("ui.native_load_in_flight", tier = Tier.UiState)
        val Crashed = nullableStringKey("ui.native_load_crashed", tier = Tier.UiState)
    }
}
