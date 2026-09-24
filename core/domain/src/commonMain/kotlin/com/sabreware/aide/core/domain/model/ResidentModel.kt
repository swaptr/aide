package com.sabreware.aide.core.domain.model

/**
 * A loadable, closeable model the [ResidencyManager] schedules. The engine adapter behind it owns the
 * actual native load/close; the manager only coordinates *when* — refcounts, the serialized-load
 * queue, keepAlive expiry, and memory-pressure eviction.
 *
 * Identity is [key]: two acquires of the same key share one resident slot (one refcount), so a model
 * is loaded once and closed once no matter how many surfaces hold it. Callers build a fresh adapter
 * per acquire; as long as same-key adapters drive the same underlying engine, that is fine — the
 * manager keeps the first instance per key for its lifetime in the table.
 */
interface ResidentModel {
    /** Which slot this occupies (chat/asr/tts/vad). Drives the fixed acquisition order in callers. */
    val modality: Modality

    /** Stable identity — canonically `"provider:modelId"`. Same key ⇒ same resident slot ⇒ shared refcount. */
    val key: String

    /** Whether the manager refcounts + evicts ([Residency.LOADED]) or returns a no-op handle ([Residency.NONE]). */
    val residency: Residency

    /**
     * Best-effort resident-RAM cost in bytes — the manager logs it and uses it only to break ties
     * when choosing trim victims. `0` when unknown; never load just to compute this.
     */
    fun memoryEstimateBytes(): Long = 0L

    /**
     * Bring the model into memory. Must be idempotent — a no-op when already resident — because the
     * manager may call it while the underlying engine is already loaded. Runs inside the manager's
     * serialized load queue so two native loads never overlap and spike RAM.
     */
    suspend fun load()

    /**
     * Free the native weights. The manager invokes this under `NonCancellable`, so a parent-scope
     * cancel can never strand a half-freed handle. Must tolerate being called when nothing is loaded.
     */
    suspend fun close()
}
