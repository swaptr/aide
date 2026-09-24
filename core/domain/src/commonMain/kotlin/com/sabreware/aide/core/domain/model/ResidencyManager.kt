package com.sabreware.aide.core.domain.model

/**
 * One refcount + lifecycle authority across every modality (chat / asr / tts / vad). It replaces the
 * per-role [com.sabreware.aide.data.model.ResidentModelGuard] single-slots and the load-centric
 * single-resident invariant in `LlmEngineRepositoryImpl`: a resident STT model and a resident LLM are
 * the same RAM problem, so they share one manager.
 *
 * Guarantees:
 *  - **No unload mid-request** — a model with a held handle is never closed.
 *  - **Serialized loads** — native loads run one at a time, in a queue independent of held handles,
 *    so nested acquires (the voice loop holds chat → acquires asr → vad → tts) never deadlock.
 *  - **keepAlive idle-release** — when the last hold drops, the model lingers for `keepAliveMs` then
 *    closes, so quick re-summons skip a cold reload.
 *  - **LRU trim-evict** — under memory pressure, unheld [Residency.LOADED] residents are closed
 *    least-recently-used first; held ones are spared.
 */
interface ResidencyManager {

    /**
     * Acquire a hold on [model]: ensure it is resident (loading it inside the serialized queue if
     * not), then refcount it so no concurrent release/trim can close it mid-use. Returns a
     * [ResidencyHandle] — call [ResidencyHandle.release] in a `finally`. Acquiring an already-resident
     * model just bumps the refcount (no reload). [Residency.NONE] models skip all of this and return a
     * no-op handle. Rethrows if [ResidentModel.load] fails (the refcount is rolled back first).
     */
    suspend fun acquire(model: ResidentModel): ResidencyHandle

    /** Snapshot of currently-tracked residents — diagnostics and tests only. */
    fun residents(): List<Resident>

    /**
     * Memory-pressure hook (wired from `AideApp.onTrimMemory`). When [level] meets the impl's
     * threshold, unheld [Residency.LOADED] residents are evicted least-recently-used first; held
     * residents are never yanked. Returns immediately — eviction runs off the caller's thread.
     */
    fun onTrimMemory(level: Int)

    data class Resident(
        val modality: Modality,
        val key: String,
        val refCount: Int,
        val pendingRelease: Boolean,
        val estimateBytes: Long,
    )

    companion object {
        /** Default keepAlive after the last hold drops. 60 s — quick re-summons skip a cold reload. */
        const val DEFAULT_KEEP_ALIVE_MS: Long = 60_000L
    }
}
