package com.swaptr.aide.domain.llm.dispatch

import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdempotencyCache @Inject constructor() {

    private data class Entry(val envelope: JsonObject, val expiresAt: Long)

    private val map: LinkedHashMap<String, Entry> = object : LinkedHashMap<String, Entry>(
        CAPACITY,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>): Boolean = size > CAPACITY
    }

    @Synchronized
    fun get(key: String, nowMs: Long = System.currentTimeMillis()): JsonObject? {
        val entry = map[key] ?: return null
        if (entry.expiresAt <= nowMs) {
            map.remove(key)
            return null
        }
        return entry.envelope
    }

    @Synchronized
    fun put(
        key: String,
        envelope: JsonObject,
        ttlMs: Long = DEFAULT_TTL_MS,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        map[key] = Entry(envelope, nowMs + ttlMs)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    companion object {
        const val CAPACITY = 100
        const val DEFAULT_TTL_MS = 5 * 60_000L
    }
}
