package com.sabreware.aide.core.domain.device

/**
 * What this device can hold.
 *
 * [totalRamGb] answers "how big a model is plausible here" for the model library's sizing hints.
 * [availableRamBytes] answers a different and sharper question — "can THIS load happen right now" — and is
 * what [com.sabreware.aide.core.domain.model.MemoryAdmission] decides on. A static total cannot answer it:
 * a 6 GB phone with four apps warm has far less than 6 GB to give.
 */
interface DeviceInfo {
    val totalRamGb: Int

    /** Total physical/heap memory in bytes, as the host reports it. */
    val totalRamBytes: Long

    /**
     * Free memory right now, in bytes. Read fresh on every call — a cached value is worse than none, since
     * the whole point is the state at the moment of a load.
     */
    val availableRamBytes: Long
}
