package com.sabreware.aide.core.domain.model

/**
 * Whether a model that is about to be loaded can actually fit.
 *
 * The eviction path answers the wrong question. `onTrimMemory` fires *after* the system is already under
 * pressure, which means a model too large for the device is loaded first and the process is killed second —
 * the user sees a crash, not a reason. Deciding before the load turns that into "this model needs 4.1 GB and
 * 1.3 GB is free", which is a sentence someone can act on.
 *
 * Pure arithmetic over a snapshot, deliberately: it is the piece worth testing, it has no platform in it,
 * and it lets the caller decide what to do with a [Verdict.Evict] rather than having eviction wired in here.
 */
object MemoryAdmission {

    /**
     * Fraction of TOTAL memory never promised to model weights — the OS, the Compose UI, the Room page
     * cache, Ktor buffers, and the allocation spike a native load makes *while* mapping weights.
     *
     * 25% is not a tuned number and is not claimed to be. It is a deliberate over-estimate, because the two
     * failure modes are asymmetric: refusing a model that would just barely have fitted is a message, and
     * accepting one that does not is the process dying mid-load.
     */
    const val DEFAULT_HEADROOM_FRACTION: Double = 0.25

    sealed interface Verdict {
        /** Fits without evicting anything of ours. */
        data object Fits : Verdict

        /**
         * Fits the device, but free memory is short by [bytesToReclaim]: the caller evicts unheld residents
         * (LRU) up to that debt, then loads — the host reclaims the rest from cached processes. This does not
         * decide what is expendable; that ordering is the residency manager's job.
         */
        data class Evict(val bytesToReclaim: Long) : Verdict

        /**
         * Cannot fit on this device at all, whatever is evicted: [availableBytes] is the device's whole model
         * budget (total minus headroom minus what held residents occupy), [shortfallBytes] what is missing.
         */
        data class TooLarge(val requiredBytes: Long, val availableBytes: Long, val shortfallBytes: Long) : Verdict
    }

    /**
     * Two different questions, answered against two different numbers:
     *
     * 1. **Can this device hold it at all?** Against the device's budget — total minus headroom minus the
     *    residents someone is mid-request on. Refused only here.
     * 2. **Is there room right now?** Against free memory. A shortfall is NOT a refusal: free memory on a
     *    phone is low by design (the OS keeps cached apps resident until something needs the room, and
     *    Android's `availMem` does not count them), so refusing on it turned away a 1.7 GB model on a 6 GB
     *    phone with nothing else loaded. A shortfall asks the caller to evict its own idle residents first.
     *
     * @param requiredBytes what the model needs resident. Zero or unknown (`null` estimate mapped to 0) is
     *   always admitted — refusing on an absent number would block every model whose size we cannot read.
     * @param availableBytes free memory right now, as the host reports it.
     * @param reclaimableBytes what evicting every unheld resident would free.
     * @param heldBytes what residents that are in use (not evictable) occupy.
     * @param totalBytes the device's total. Falls back to `availableBytes` when a host cannot report one.
     * @param headroomFraction fraction of [totalBytes] never spent on weights. See [DEFAULT_HEADROOM_FRACTION].
     */
    fun check(
        requiredBytes: Long,
        availableBytes: Long,
        reclaimableBytes: Long = 0L,
        heldBytes: Long = 0L,
        totalBytes: Long = availableBytes,
        headroomFraction: Double = DEFAULT_HEADROOM_FRACTION,
    ): Verdict {
        if (requiredBytes <= 0L) return Verdict.Fits

        val total = totalBytes.coerceAtLeast(0L)
        val budget = (total - (total * headroomFraction).toLong() - heldBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        if (requiredBytes > budget) {
            return Verdict.TooLarge(
                requiredBytes = requiredBytes,
                availableBytes = budget,
                shortfallBytes = requiredBytes - budget,
            )
        }

        val deficit = requiredBytes - availableBytes.coerceAtLeast(0L)
        if (deficit <= 0L || reclaimableBytes <= 0L) return Verdict.Fits
        return Verdict.Evict(bytesToReclaim = deficit)
    }
}

/**
 * Thrown by [ResidencyManager.acquire] when a model cannot fit even after evicting everything evictable.
 *
 * A typed exception rather than a null return because the load path is deep — the caller three frames up
 * wants to tell the user *why*, and a null would arrive there having lost the numbers.
 */
class InsufficientMemoryException(
    val modelKey: String,
    val requiredBytes: Long,
    val availableBytes: Long,
    val shortfallBytes: Long,
) : IllegalStateException(
    "$modelKey needs ${requiredBytes / MB} MB but this device can give models only ${availableBytes / MB} MB " +
        "(${shortfallBytes / MB} MB short)",
)

private const val MB = 1024L * 1024L
