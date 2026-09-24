package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ModelStorage

class DeleteModelUseCase(
    private val engine: LlmEngineRepository,
    private val cancelDownload: CancelDownloadUseCase,
    private val storage: ModelStorage,
    private val registry: ModelRegistryRepository,
) {
    suspend operator fun invoke(spec: ModelSpec) {
        engine.withLifecycleLock { engine.unload(spec.id) }
        // cancel() already wipes what landed on disk via the asset source; delete() covers an imported
        // model, which was never downloaded and so has nothing for the scheduler to cancel.
        cancelDownload(spec)
        storage.deleteFile(spec)

        // No fallback pick: a private app never substitutes a model the user did not choose (an on-device
        // model silently becoming a cloud one); the chat surface prompts for a new pick instead. The match is
        // checked inside the store's atomic update, so a pick made concurrently is never wiped.
        registry.clearDefaultIfMatches(spec.id)

        registry.clearLastUsedIfMatches(spec.id)
        // Drop per-tier last-used slot so the deleted model leaves the "In Use" tab immediately.
        registry.clearUsed(spec)
    }
}
