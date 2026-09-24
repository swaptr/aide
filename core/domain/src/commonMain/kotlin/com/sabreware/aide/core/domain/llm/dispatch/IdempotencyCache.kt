package com.sabreware.aide.core.domain.llm.dispatch

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject

class IdempotencyCache {

    private data class Entry(val envelope: JsonObject, val expiresAt: Long)

    private val lock = SynchronizedObject()

    // Access-order LRU: iteration order runs least-recently-used → most-recently-used. `get`/`put`
    // bump the touched key to MRU; over-capacity puts evict from the front. All access is lock-guarded.
    private val map: LinkedHashMap<String, Entry> = LinkedHashMap()

    fun get(key: String, nowMs: Long = Clock.System.now().toEpochMilliseconds()): JsonObject? =
        synchronized(lock) {
            val entry = map[key] ?: return@synchronized null
            if (entry.expiresAt <= nowMs) {
                map.remove(key)
                return@synchronized null
            }
            // Access-order bump to MRU.
            map.remove(key)
            map[key] = entry
            entry.envelope
        }

    fun put(
        key: String,
        envelope: JsonObject,
        ttlMs: Long = DEFAULT_TTL_MS,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        synchronized(lock) {
            map.remove(key)
            map[key] = Entry(envelope, nowMs + ttlMs)
            while (map.size > CAPACITY) {
                val eldest = map.keys.iterator().next()
                map.remove(eldest)
            }
        }
    }

    fun clear() {
        synchronized(lock) { map.clear() }
    }

    companion object {
        const val CAPACITY = 100
        const val DEFAULT_TTL_MS = 5 * 60_000L
    }
}
