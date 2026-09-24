package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ProviderCatalog
import com.sabreware.aide.core.domain.model.Residency
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.model.ResidentModel

/**
 * Per-surface keepAlive after a turn's last hold drops — how long a model lingers warm before the
 * [ResidencyManager] idle-releases it.
 */
object ResidencyDurations {
    /** Chat: stay hot across quick back-and-forth and brief screen exits. */
    const val CHAT_KEEPALIVE_MS: Long = 5 * 60_000L

    /** Voice assistant: quick re-summons skip a cold reload. */
    const val VOICE_KEEPALIVE_MS: Long = 60_000L

    /** IME transforms: matches the keyboard's prior 1.5 s idle-release of the engine. */
    const val IME_KEEPALIVE_MS: Long = 1_500L
}

/**
 * Acquires a chat model through the [ResidencyManager] AFTER the caller has resolved its [ChatModelSpec].
 * The returned [ResidencyHandle] must be
 * released — typically `flow.onCompletion { handle?.release(keepAliveMs) }` — so the model is never
 * unloaded mid-turn and idle-releases on the surface's keepAlive afterwards.
 *
 * The adapter is built from domain interfaces only: residency comes from [ProviderCatalog] (`local`
 * provider ⇒ holds native weights ⇒ LOADED; remote ⇒ NONE), load/close delegate to the engine repo.
 */
class AcquireModelUseCase(
    private val residency: ResidencyManager,
    private val engine: LlmEngineRepository,
) {
    suspend operator fun invoke(spec: ChatModelSpec, config: ChatGenerationConfig?, owner: Surface): ResidencyHandle =
        residency.acquire(LlmResidentModel(spec, config, engine), owner)

    private class LlmResidentModel(
        private val spec: ChatModelSpec,
        private val config: ChatGenerationConfig?,
        private val engine: LlmEngineRepository,
    ) : ResidentModel {
        override val modality = spec.modality
        override val key = spec.id
        override val residency =
            if (ProviderCatalog.of(spec.provider).local) Residency.LOADED else Residency.NONE

        override fun memoryEstimateBytes(): Long = spec.sizeBytes ?: 0L

        // ensureLoaded no-ops when already resident, so this is idempotent. lifecycleLock keeps the
        // engine's own load/unload serialisation intact under the manager's load queue.
        override suspend fun load() = engine.withLifecycleLock { engine.ensureLoaded(spec, config) }

        // Asked of the engine, because an engine drops its model on its own when it loads another one.
        override fun isResident(): Boolean = engine.isLoaded(spec)

        // Exactly this model: if the engine has since loaded another, that one is someone else's.
        override suspend fun close() = engine.withLifecycleLock { engine.unload(spec.id) }
    }
}
