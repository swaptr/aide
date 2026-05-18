package com.swaptr.aide.data.model

import com.swaptr.aide.data.catalog.ModelCatalog
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.download.DownloadController
import com.swaptr.aide.data.download.DownloadStatus
import com.swaptr.aide.data.storage.ModelStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

// Exposes a combined per-catalog snapshot so callers don't wire individual model flows by hand.
@Singleton
class ModelDownloadRepository @Inject constructor(
    private val controller: DownloadController,
    private val storage: ModelStorage,
) {

    fun isDownloaded(spec: ModelSpec): Boolean = storage.isDownloaded(spec)

    fun downloadedBytes(spec: ModelSpec): Long = storage.downloadedBytes(spec)

    fun delete(spec: ModelSpec): Boolean = storage.delete(spec)

    fun enqueue(spec: ModelSpec, authToken: String? = null) = controller.enqueue(spec, authToken)

    fun pause(modelId: String) = controller.pause(modelId)

    fun cancel(spec: ModelSpec) = controller.cancel(spec.id, spec)

    fun observe(spec: ModelSpec): Flow<DownloadStatus> = controller.observe(spec)

    /** Combined snapshot of [DownloadStatus] for every catalog entry, keyed by model id. */
    fun observeAllStatuses(): Flow<Map<String, DownloadStatus>> {
        val catalog = ModelCatalog.models
        if (catalog.isEmpty()) return flowOf(emptyMap())
        val flows = catalog.map { controller.observe(it) }
        return combine(flows) { arr ->
            arr.associateBy { it.modelId }
        }
    }
}
