package com.swaptr.aide.data.model

import android.content.ComponentCallbacks2
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Release runs in NonCancellable so a parent-scope cancel can't strand native handles
// half-freed; all transitions through mutex so surfaces don't race refcount.
class ResidentModelGuard(
    val role: Role,
    private val idleMs: Long,
    private val release: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    enum class Role { LLM, STT, TTS, VAD }

    data class Snapshot(
        val role: Role,
        val refCount: Int,
        val pendingRelease: Boolean,
        val lastReleaseAtMs: Long,
    )

    private val mutex = Mutex()
    private var refCount: Int = 0
    private var idleJob: Job? = null
    @Volatile private var lastReleaseAtMs: Long = 0L

    private val _snapshot = MutableStateFlow(snapshotLocked())
    val snapshotFlow: StateFlow<Snapshot> = _snapshot.asStateFlow()

    // Caller owns the load; this object only coordinates *when* it's safe to release.
    suspend fun <T> use(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            releaseRef()
        }
    }

    // Memory-pressure entry; no-op while in use, else cancel idle job and release now.
    suspend fun forceRelease() = mutex.withLock {
        if (refCount > 0) return@withLock
        idleJob?.cancel()
        idleJob = null
        runReleaseLocked("force")
    }

    fun snapshot(): Snapshot = snapshotLocked()

    private suspend fun acquire() = mutex.withLock {
        refCount += 1
        if (idleJob != null) {
            idleJob?.cancel()
            idleJob = null
            Log.d(TAG, "${role.name}: re-acquire cancelled pending release")
        }
        _snapshot.value = snapshotLocked()
    }

    private suspend fun releaseRef() = mutex.withLock {
        refCount = (refCount - 1).coerceAtLeast(0)
        if (refCount == 0) scheduleIdleReleaseLocked()
        _snapshot.value = snapshotLocked()
    }

    private fun scheduleIdleReleaseLocked() {
        idleJob?.cancel()
        idleJob = scope.launch {
            try {
                delay(idleMs)
            } catch (_: Throwable) {
                return@launch
            }
            mutex.withLock {
                if (refCount > 0) return@withLock      // re-acquired during delay
                runReleaseLocked("idle")
            }
        }
    }

    private suspend fun runReleaseLocked(reason: String) {
        withContext(NonCancellable) {
            runCatching { release() }
                .onFailure { Log.w(TAG, "${role.name}: release ($reason) failed", it) }
                .onSuccess { Log.i(TAG, "${role.name}: released ($reason)") }
        }
        lastReleaseAtMs = System.currentTimeMillis()
        idleJob = null
        _snapshot.value = snapshotLocked()
    }

    private fun snapshotLocked() = Snapshot(
        role = role,
        refCount = refCount,
        pendingRelease = idleJob != null,
        lastReleaseAtMs = lastReleaseAtMs,
    )

    companion object {
        private const val TAG = "ResidentModelGuard"
        /** Default model idle-release timeout (ms). 60 s per architecture decision. */
        const val DEFAULT_IDLE_MS: Long = 60_000L
    }
}

// Fires when level >= threshold (RUNNING_MODERATE=5 → CRITICAL=15); default RUNNING_CRITICAL
// keeps weights hot for performance.
data class TrimPolicy(
    @Suppress("DEPRECATION") val llmReleaseAt: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
    @Suppress("DEPRECATION") val sttReleaseAt: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
    @Suppress("DEPRECATION") val ttsReleaseAt: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
    @Suppress("DEPRECATION") val vadReleaseAt: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
) {
    fun thresholdFor(role: ResidentModelGuard.Role): Int = when (role) {
        ResidentModelGuard.Role.LLM -> llmReleaseAt
        ResidentModelGuard.Role.STT -> sttReleaseAt
        ResidentModelGuard.Role.TTS -> ttsReleaseAt
        ResidentModelGuard.Role.VAD -> vadReleaseAt
    }
}
