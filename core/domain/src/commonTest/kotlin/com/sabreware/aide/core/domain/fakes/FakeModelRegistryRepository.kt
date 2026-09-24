package com.sabreware.aide.core.domain.fakes

import com.sabreware.aide.core.domain.model.ModelCard
import kotlinx.coroutines.flow.flowOf
import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.model.ModelGateState
import com.sabreware.aide.core.domain.model.ModelMetadata
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ModelSummary
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.ProviderTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reusable hand fake. [findSpec] resolves from [specs]; the rest are unused by current callers and throw
 * so a test that needs them fails loudly rather than silently passing on a wrong default.
 *
 * Unit-returning members spell out `: Unit` rather than letting `= unsupported()` infer `Nothing` — an
 * inferred `Nothing` cannot be overridden by a subclass that wants to do nothing, which is exactly what a
 * test subclassing this fake needs to do.
 */
open class FakeModelRegistryRepository(
    private val specs: Map<String, ModelSpec> = emptyMap(),
) : ModelRegistryRepository {
    override fun findSpec(id: String): ModelSpec? = specs[id]

    override suspend fun modelMetadata(spec: ChatModelSpec): ModelMetadata = unsupported()
    override fun metadataSnapshot(spec: ChatModelSpec): ModelMetadata = unsupported()

    override fun refreshing(provider: ProviderId): StateFlow<Boolean> = unsupported()
    override fun catalogFetchedAt(provider: ProviderId): StateFlow<Long?> = unsupported()
    override val lastUsedByTier: StateFlow<Map<ProviderTier, String>> get() = unsupported()
    override val lastUsedModelIdFlow: StateFlow<String?> get() = unsupported()
    override fun refresh(provider: ProviderId): Unit = unsupported()
    override val models: StateFlow<List<ModelSummary>?> get() = unsupported()

    // A fake with nothing picked: the header falls straight through to the settled resolution, which is
    // what every test that does not care about the first-frame seed wants.
    override val selection: StateFlow<DocState<ModelSelection>> = MutableStateFlow(DocState.Ready(ModelSelection()))
    override val effectiveLastUsedModelIdFlow: Flow<String?> get() = unsupported()
    override val gateStateFlow: StateFlow<ModelGateState> get() = unsupported()
    // A known chat spec resolves Ready; anything else is Missing — the settled answers, with no waiting.
    override fun resolve(id: String): Flow<ModelGateState> = flowOf(
        (specs[id] as? ChatModelSpec)?.let { ModelGateState.Ready(it) } ?: ModelGateState.Missing(ModelCard(id)),
    )
    override suspend fun setDefaultModelId(id: String): Boolean = unsupported()
    override suspend fun clearDefaultModelId(): Unit = unsupported()
    override suspend fun clearDefaultIfMatches(id: String): Unit = unsupported()
    override suspend fun recordUsed(spec: ModelSpec): Unit = unsupported()
    override suspend fun recordSelected(spec: ModelSpec): Unit = unsupported()
    override fun selectWhenDownloaded(spec: ModelSpec): Unit = unsupported()
    override suspend fun clearUsed(spec: ModelSpec): Unit = unsupported()
    override suspend fun clearLastUsedIfMatches(id: String): Unit = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
}
