package com.sabreware.aide.core.domain.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The one data-layer cache shape. What a surface relies on: null means "not yet", a value outlives its last
 * subscriber (so a re-open paints instantly), the upstream is released when nobody watches, and a failure
 * neither crashes nor wedges the cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotCacheTest {

    @Test
    fun `null until the first value, then the value`() = runTest {
        val source = MutableStateFlow<List<String>?>(null)
        val cache = source.snapshotCache(backgroundScope, "test")
        val watcher = backgroundScope.launch { cache.collect {} }
        runCurrent()
        assertNull(cache.value, "an unsettled source must read as not-yet")

        source.value = listOf("a")
        runCurrent()
        assertEquals(listOf("a"), cache.value)
        watcher.cancel()
    }

    @Test
    fun `the value is retained after the upstream is released, and a re-open restarts it`() = runTest {
        var starts = 0
        var active = 0
        val cache = flow { emit(listOf("a")); awaitCancellation() }
            .onStart { starts++; active++ }
            .onCompletion { active-- }
            .snapshotCache(backgroundScope, "test")

        val first = backgroundScope.launch { cache.collect {} }
        runCurrent()
        first.cancel()
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(0, active, "nobody watching → the upstream (disk, decrypt) is released")
        assertEquals(listOf("a"), cache.value, "…but the snapshot survives for the next open to paint from")

        val second = backgroundScope.launch { cache.collect {} }
        runCurrent()
        assertEquals(2, starts, "a re-open revalidates behind the cached value")
        second.cancel()
    }

    @Test
    fun `a throwing upstream leaves not-yet, and the next subscription retries`() = runTest {
        var attempts = 0
        val cache = flow {
            attempts++
            if (attempts == 1) error("disk unreadable")
            emit(listOf("recovered"))
        }.snapshotCache(backgroundScope, "test")

        val first = backgroundScope.launch { cache.collect {} }
        runCurrent()
        assertNull(cache.value)
        first.cancel()
        advanceTimeBy(5_001)

        val second = backgroundScope.launch { cache.collect {} }
        runCurrent()
        assertEquals(listOf("recovered"), cache.value)
        second.cancel()
    }

    @Test
    fun `warm-up resolves a cold cache with no surface watching, once`() = runTest {
        var starts = 0
        val cache = flow { starts++; emit(listOf("a")) }.snapshotCache(backgroundScope, "test")
        val warmup = CacheWarmup(backgroundScope, mapOf("test" to cache))

        warmup.start()
        warmup.start()
        runCurrent() // not advanceUntilIdle: it stops once only backgroundScope work is left

        assertEquals(listOf("a"), cache.value, "the first open now seeds from this")
        assertEquals(1, starts, "idempotent: the shell re-runs bootstraps on activity recreation")
    }

    @Test
    fun `warm-up gives up on a source that never settles instead of pinning it open`() = runTest {
        val never = MutableStateFlow<String?>(null)
        var released = false
        val cache = never.onCompletion { released = true }.snapshotCache(backgroundScope, "slow")

        CacheWarmup(backgroundScope, mapOf("slow" to cache)).start()
        advanceTimeBy(30_000 + 5_000 + 1) // the warm-up's bound, then the cache's release delay
        runCurrent()

        assertNull(cache.value)
        assertEquals(true, released)
        // And the cache is still usable afterwards.
        never.value = "late"
        val watcher = backgroundScope.launch { cache.first { it != null } }
        runCurrent()
        assertEquals("late", cache.value)
        watcher.cancel()
    }
}
