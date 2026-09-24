package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ProviderCatalog

class LoadModelUseCase(
    private val acquireModel: AcquireModelUseCase,
    private val registry: ModelRegistryRepository,
) {
    suspend operator fun invoke(spec: ModelSpec) {
        // The choice is recorded FIRST: it is the user's intent and must survive a warm-up that fails. It
        // used to be written only after a successful load, so a refused or failed preload threw past it and
        // "Use" silently kept "No model" — the send path loads (and reports) on its own anyway.
        registry.setDefaultModelId(spec.id)
        registry.recordUsed(spec)
        // Only a chat model has an engine to warm. Preload through the ResidencyManager so it is
        // refcount-tracked + trim-evictable; release with the chat keepAlive so it stays warm for the chat
        // that usually follows, else idle-unloads instead of pinning RAM forever.
        if (spec is ChatModelSpec) {
            acquireModel(spec, null, owner = Surface.CHAT).release(ResidencyDurations.CHAT_KEEPALIVE_MS)
        }
    }
}

class UnloadModelUseCase(
    private val engine: LlmEngineRepository,
    private val registry: ModelRegistryRepository,
) {
    suspend operator fun invoke(spec: ModelSpec) {
        // Only local (resident-engine) providers hold weights to unload; remote is a no-op.
        if (ProviderCatalog.of(spec.provider).local) engine.withLifecycleLock { engine.unload(spec.id) }
        registry.clearUsed(spec)
        // Drop global active-model pointer too — chat headers bind to effectiveLastUsedModelIdFlow.
        registry.clearLastUsedIfMatches(spec.id)
        registry.clearDefaultIfMatches(spec.id)
    }
}

class SetDefaultModelUseCase(
    private val registry: ModelRegistryRepository,
) {
    suspend operator fun invoke(modelId: String): Boolean = registry.setDefaultModelId(modelId)
}
