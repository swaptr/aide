package com.sabreware.aide.core.domain.fakes

import com.sabreware.aide.core.common.prefs.PrefKey
import com.sabreware.aide.core.common.prefs.PrefSnapshot
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [PreferenceStore] for tests. Replaces the hand-written `FakeUserPreferencesRepository`, which
 * had to stub ~65 members: a test now seeds only the keys it cares about.
 *
 *     FakePreferenceStore(SpeechPrefs.SpeakAssistantReplies to false)
 *
 * Values are keyed by [PrefKey.name], so a key read before it is written falls back to its own default —
 * the same totality the real store guarantees.
 */
class FakePreferenceStore(vararg seed: Pair<PrefKey<*>, Any?>) : PreferenceStore {

    private val values = MutableStateFlow(seed.associate { (key, value) -> key.name to value })

    @Suppress("UNCHECKED_CAST")
    override fun <T> flow(key: PrefKey<T>): Flow<T> =
        values.map { map -> if (map.containsKey(key.name)) map[key.name] as T else key.default }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> get(key: PrefKey<T>): T =
        values.value.let { map -> if (map.containsKey(key.name)) map[key.name] as T else key.default }

    override val snapshots: Flow<PrefSnapshot> = values.map(::MapSnapshot)

    override val current: PrefSnapshot get() = MapSnapshot(values.value)

    override val isLoaded: Boolean get() = true

    override suspend fun awaitLoaded() = Unit

    override suspend fun <T> set(key: PrefKey<T>, value: T) {
        values.value = values.value + (key.name to value)
    }

    override suspend fun <T> update(key: PrefKey<T>, block: (T) -> T) {
        set(key, block(get(key)))
    }

    override suspend fun remove(key: PrefKey<*>) {
        values.value = values.value - key.name
    }

    override suspend fun clear(tier: Tier) {
        val uiPrefix = "ui."
        values.value = values.value.filterKeys { name ->
            val owned = if (tier == Tier.UiState) name.startsWith(uiPrefix) else !name.startsWith(uiPrefix)
            !owned
        }
    }
}

private class MapSnapshot(private val map: Map<String, Any?>) : PrefSnapshot {
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: PrefKey<T>): T = if (map.containsKey(key.name)) map[key.name] as T else key.default
}
