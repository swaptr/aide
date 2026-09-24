package com.sabreware.aide.core.domain.fakes

import com.sabreware.aide.core.domain.secure.SecureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** In-memory [SecureStore]: one map, every multi-key write committed at once. */
class FakeSecureStore(vararg seed: Pair<String, String>) : SecureStore {
    val values = MutableStateFlow(mapOf(*seed))

    override fun observe(key: String): Flow<String?> = values.map { it[key] }.distinctUntilChanged()

    override suspend fun put(key: String, value: String) = write(mapOf(key to value))

    override suspend fun remove(key: String) = write(mapOf(key to null))

    override suspend fun write(changes: Map<String, String?>) {
        values.value = changes.entries.fold(values.value) { acc, (key, value) ->
            if (value == null) acc - key else acc + (key to value)
        }
    }
}
