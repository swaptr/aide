package com.sabreware.aide.data.prefs

import com.sabreware.aide.core.common.prefs.PrefKey
import com.sabreware.aide.core.common.prefs.PrefSnapshot
import com.sabreware.aide.core.common.prefs.PrefStorageApi
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.Tier

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

/**
 * [PreferenceStore] over Preferences DataStore — the same store the settings already use.
 *
 * DataStore rewrites the whole file per commit: right for a tap, wrong for a drag. Values that change while a
 * pointer is down (window bounds, pane widths, scroll anchors) must be debounced before reaching [set].
 */
@OptIn(PrefStorageApi::class)
class DataStorePreferenceStore(
    private val dataStore: DataStore<Preferences>,
    appScope: CoroutineScope,
) : PreferenceStore {

    // ONE store subscription shared by every key's [flow]: N screens × M keys used to each collect
    // `dataStore.data` separately. Held as a StateFlow so [peek] can answer synchronously, and started
    // Eagerly: the app root collects the theme for the whole session anyway, so the subscription is always
    // live — Eagerly only moves the file read to construction (a `createdAtStart` binding), which is what
    // lets the first frame paint the user's theme instead of the default one.
    private val data: StateFlow<Preferences?> =
        dataStore.data.stateIn(appScope, SharingStarted.Eagerly, null)

    override fun <T> flow(key: PrefKey<T>): Flow<T> =
        data.filterNotNull().map { key.read(it) ?: key.default }.distinctUntilChanged()

    override val snapshots: Flow<PrefSnapshot> = data.filterNotNull().map(::PreferencesSnapshot)

    override val current: PrefSnapshot get() = data.value?.let(::PreferencesSnapshot) ?: PrefSnapshot.Defaults

    override val isLoaded: Boolean get() = data.value != null

    override suspend fun awaitLoaded() {
        data.first { it != null }
    }

    // Reads the STORE, not the shared replay cache: a `get` is usually the read half of a
    // read-modify-write, and the cache can lag an [edit] that just committed.
    override suspend fun <T> get(key: PrefKey<T>): T =
        dataStore.data.map { key.read(it) ?: key.default }.first()

    override suspend fun <T> set(key: PrefKey<T>, value: T) {
        dataStore.edit { key.write(it, value) }
    }

    override suspend fun <T> update(key: PrefKey<T>, block: (T) -> T) {
        dataStore.edit { prefs -> key.write(prefs, block(key.read(prefs) ?: key.default)) }
    }

    override suspend fun remove(key: PrefKey<*>) {
        dataStore.edit { it -= key.storageKey }
    }

    override suspend fun clear(tier: Tier) {
        dataStore.edit { prefs ->
            // Snapshot the keys first — removing while iterating the live map mutates under us.
            prefs.asMap().keys.filter { tier.owns(it.name) }.forEach { prefs -= it }
        }
    }
}

@OptIn(PrefStorageApi::class)
private class PreferencesSnapshot(private val prefs: Preferences) : PrefSnapshot {
    override fun <T> get(key: PrefKey<T>): T = key.read(prefs) ?: key.default
}
