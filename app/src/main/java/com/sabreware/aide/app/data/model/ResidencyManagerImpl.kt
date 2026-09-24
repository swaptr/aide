package com.sabreware.aide.app.data.model

import com.sabreware.aide.core.domain.device.DeviceInfo
import com.sabreware.aide.core.domain.model.InsufficientMemoryException
import com.sabreware.aide.core.domain.model.MemoryAdmission
import com.sabreware.aide.core.domain.model.NativeLoadJournal
import com.sabreware.aide.core.domain.model.around
import com.sabreware.aide.core.domain.model.Residency
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.model.ResidentModel
import com.sabreware.aide.core.domain.util.AideLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Two mutexes, on purpose:
 *  - [stateMutex] guards the resident table + refcounts. Held only for fast in-memory mutation —
 *    never across a native load/close.
 *  - [loadMutex] serializes the native load/close calls. A held handle is *refcount* state, not this
 *    lock, so a caller holding the chat model can still acquire asr → vad → tts without deadlocking
 *    on the load queue.
 *
 * The single source of truth for "needs (re)load" is [Slot.loadedFlag], read and written only under
 * [loadMutex]. close() flips it false under the same lock, so an acquire that races an eviction can
 * never be handed a closed model: it either skips close (refcount already bumped) or reloads (flag
 * went false).
 */
class ResidencyManagerImpl(
    private val scope: CoroutineScope,
    /** Trim level at/above which unheld LOADED residents are evicted (Android `TRIM_MEMORY_*`). */
    private val trimThresholdLevel: Int,
    /** Read fresh at each admission decision — see [DeviceInfo.availableRamBytes]. */
    private val deviceInfo: DeviceInfo,
    /** Fraction of total memory kept clear for everything that is not model weights. Injected so a test
     *  can pin it and a host with different pressure characteristics can differ without a second class. */
    private val headroomFraction: Double = MemoryAdmission.DEFAULT_HEADROOM_FRACTION,
    /** Durable note of what is entering native code, so a segfault is attributable at next launch. */
    private val journal: NativeLoadJournal,
) : ResidencyManager {

    private class Slot(
        val model: ResidentModel,
        val estimateBytes: Long,
    ) {
        var refCount: Int = 0
        var loadedFlag: Boolean = false
        var idleJob: Job? = null
        var touchSeq: Long = 0L
    }

    private val stateMutex = Mutex()
    private val loadMutex = Mutex()
    private val slots = HashMap<String, Slot>()
    private var seq: Long = 0L

    @Volatile private var residentsSnapshot: List<ResidencyManager.Resident> = emptyList()

    override suspend fun acquire(model: ResidentModel): ResidencyHandle {
        // Stateless model — nothing to keep resident, refcount, or evict. Still loaded (idempotent),
        // because NONE only means "holds no native weights *we* evict": a remote engine must still set
        // its wire marker so the turn can run. No slot, no refcount, no-op release.
        if (model.residency == Residency.NONE) {
            model.load()
            return NoOpHandle
        }

        val slot = stateMutex.withLock {
            slots.getOrPut(model.key) { Slot(model, model.memoryEstimateBytes()) }.also {
                it.refCount += 1
                it.touchSeq = ++seq
                it.idleJob?.cancel(); it.idleJob = null   // cancel any pending idle-release
                publishLocked()
            }
        }

        try {
            loadMutex.withLock {
                if (!slot.loadedFlag) {
                    admit(slot)
                    // Every load below this line may enter JNI — Sherpa and LiteRT both do. A crash there
                    // ends the process without unwinding, so the marker is written first and cleared on any
                    // normal return, exception included.
                    journal.around(model.key) { model.load() }
                    slot.loadedFlag = true
                    AideLog.i(TAG, "loaded ${model.key} (${model.modality.value})")
                }
            }
        } catch (t: Throwable) {
            // Roll back the ref we took so a failed load doesn't pin a phantom resident.
            stateMutex.withLock {
                slot.refCount = (slot.refCount - 1).coerceAtLeast(0)
                if (slot.refCount == 0 && !slot.loadedFlag && slots[model.key] === slot) {
                    slots.remove(model.key)
                }
                publishLocked()
            }
            throw t
        }

        return RealHandle(slot)
    }

    /**
     * Decide whether [slot] can be loaded, and make room if making room is enough.
     *
     * Runs inside [loadMutex], so no second load can consume the memory this one just accounted for — the
     * check and the load that follows it are one decision. Held residents are never counted as reclaimable:
     * a model someone is mid-request on is not memory we have.
     */
    private suspend fun admit(slot: Slot) {
        if (slot.estimateBytes <= 0L) return

        val (evictable, heldBytes) = stateMutex.withLock {
            evictableLocked() to slots.values
                .filter { it !== slot && it.refCount > 0 && it.loadedFlag }
                .sumOf { it.estimateBytes }
        }
        val verdict = MemoryAdmission.check(
            requiredBytes = slot.estimateBytes,
            availableBytes = deviceInfo.availableRamBytes,
            reclaimableBytes = evictable.sumOf { it.estimateBytes },
            heldBytes = heldBytes,
            totalBytes = deviceInfo.totalRamBytes,
            headroomFraction = headroomFraction,
        )
        when (verdict) {
            is MemoryAdmission.Verdict.Fits -> return

            is MemoryAdmission.Verdict.Evict -> {
                // Evict LRU-first until the debt is paid, not everything at once: a re-summon of the model
                // we just evicted is a cold reload, so over-evicting is a real cost rather than free hygiene.
                var reclaimed = 0L
                for (victim in evictable) {
                    if (reclaimed >= verdict.bytesToReclaim) break
                    victim.idleJob?.cancel()
                    closeIfIdleLocked(victim, "admission")
                    reclaimed += victim.estimateBytes
                }
                AideLog.i(
                    TAG,
                    "admission: freed ${reclaimed / MB} MB for ${slot.model.key} " +
                        "(needed ${verdict.bytesToReclaim / MB} MB)",
                )
            }

            is MemoryAdmission.Verdict.TooLarge -> throw InsufficientMemoryException(
                modelKey = slot.model.key,
                requiredBytes = verdict.requiredBytes,
                availableBytes = verdict.availableBytes,
                shortfallBytes = verdict.shortfallBytes,
            )
        }
    }

    /** Unheld, loaded residents, least-recently-used first — the same order [onTrimMemory] evicts in. */
    private fun evictableLocked(): List<Slot> =
        slots.values
            .filter { it.refCount == 0 && it.loadedFlag }
            .sortedWith(compareBy({ it.touchSeq }, { -it.estimateBytes }))

    private suspend fun release(slot: Slot, keepAliveMs: Long) = stateMutex.withLock {
        slot.refCount = (slot.refCount - 1).coerceAtLeast(0)
        slot.touchSeq = ++seq
        if (slot.refCount == 0) {
            slot.idleJob?.cancel()
            slot.idleJob = scope.launch {
                try {
                    delay(keepAliveMs)
                } catch (_: Throwable) {
                    return@launch          // re-acquired (job cancelled) — keep the model warm
                }
                closeIfIdle(slot, "idle")
            }
        }
        publishLocked()
    }

    // Commits a close only if the slot is still unheld AND loaded. Holds loadMutex throughout so the
    // refcount check is atomic against acquire's pre-loadMutex bump (see class doc).
    /** Takes [loadMutex]. Never call from a context that already holds it — `Mutex` is not reentrant. */
    private suspend fun closeIfIdle(slot: Slot, reason: String) = loadMutex.withLock {
        closeIfIdleLocked(slot, reason)
    }

    /**
     * The close itself, assuming [loadMutex] is ALREADY held.
     *
     * Split out for admission, which decides and evicts inside the very lock that serialises loads — that
     * is what makes "check, make room, load" one atomic decision rather than three racing ones. Calling
     * [closeIfIdle] from there deadlocked instead, which is the shape of bug a non-reentrant mutex is
     * supposed to make loud and here made silent: the coroutine simply never resumed.
     */
    private suspend fun closeIfIdleLocked(slot: Slot, reason: String) {
        val proceed = stateMutex.withLock { slot.refCount == 0 && slot.loadedFlag }
        if (!proceed) return
        withContext(NonCancellable) {
            runCatching { slot.model.close() }
                .onFailure { AideLog.w(TAG, "${slot.model.key}: close ($reason) failed", it) }
                .onSuccess { AideLog.i(TAG, "${slot.model.key}: released ($reason)") }
        }
        slot.loadedFlag = false
        stateMutex.withLock {
            // refCount may have gone >0 (an acquirer bumped, now blocked on this loadMutex); it will
            // reload because loadedFlag is now false. Only drop the slot when it is truly idle.
            if (slot.refCount == 0 && slots[slot.model.key] === slot) slots.remove(slot.model.key)
            slot.idleJob = null
            publishLocked()
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level < trimThresholdLevel) return
        scope.launch {
            val victims = stateMutex.withLock { evictableLocked() }
            if (victims.isEmpty()) return@launch
            AideLog.i(TAG, "onTrimMemory level=$level — evicting ${victims.size} unheld resident(s)")
            for (slot in victims) {
                slot.idleJob?.cancel()
                closeIfIdle(slot, "trim")
            }
        }
    }

    override fun residents(): List<ResidencyManager.Resident> = residentsSnapshot

    private fun publishLocked() {
        residentsSnapshot = slots.values.map {
            ResidencyManager.Resident(
                modality = it.model.modality,
                key = it.model.key,
                refCount = it.refCount,
                pendingRelease = it.idleJob != null,
                estimateBytes = it.estimateBytes,
            )
        }
    }

    private object NoOpHandle : ResidencyHandle {
        override suspend fun release(keepAliveMs: Long) = Unit
    }

    private inner class RealHandle(private val slot: Slot) : ResidencyHandle {
        private val released = AtomicBoolean(false)
        override suspend fun release(keepAliveMs: Long) {
            if (released.compareAndSet(false, true)) release(slot, keepAliveMs)
        }
    }

    companion object {
        private const val TAG = "ResidencyManager"
    }
}

private const val MB = 1024L * 1024L
