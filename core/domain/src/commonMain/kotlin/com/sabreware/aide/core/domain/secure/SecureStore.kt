package com.sabreware.aide.core.domain.secure

import kotlinx.coroutines.flow.Flow

/** Secure key-value store (encrypted at rest). Android impl = Tink; desktop/iOS supply their own. Koin-injected. */
interface SecureStore {
    fun observe(key: String): Flow<String?>
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)

    /**
     * Several keys in ONE commit (a null value removes that key), so an observer of more than one of them —
     * a provider config spans three — sees the change once instead of each half-written state in turn.
     */
    suspend fun write(changes: Map<String, String?>)
}
