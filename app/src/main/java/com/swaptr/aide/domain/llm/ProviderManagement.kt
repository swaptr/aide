package com.swaptr.aide.domain.llm

import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.provider.ConnectionTestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ProviderManagement {
    val remoteCatalogFlow: Flow<RemoteCatalogState>

    suspend fun refreshCatalog()

    suspend fun testConnection(): ConnectionTestResult?

    fun pullModel(name: String): Flow<PullProgress>? = null

    suspend fun hydrateSpec(modelId: String) {}
}

sealed interface RemoteCatalogState {
    data object Unconfigured : RemoteCatalogState
    data class Ready(val specs: List<ModelSpec>, val fetchedAt: Long) : RemoteCatalogState
    data class Refreshing(val previous: List<ModelSpec>) : RemoteCatalogState
    data class Failed(val previous: List<ModelSpec>, val message: String) : RemoteCatalogState
}

data class PullProgress(
    val status: String,
    val percent: Float?,
    val terminal: Boolean,
    val error: String? = null,
)

object NoOpProviderManagement : ProviderManagement {
    private val state = MutableStateFlow<RemoteCatalogState>(RemoteCatalogState.Unconfigured)
    override val remoteCatalogFlow: Flow<RemoteCatalogState> = state.asStateFlow()
    override suspend fun refreshCatalog() {}
    override suspend fun testConnection(): ConnectionTestResult? = null
    override fun pullModel(name: String): Flow<PullProgress>? = null
}
