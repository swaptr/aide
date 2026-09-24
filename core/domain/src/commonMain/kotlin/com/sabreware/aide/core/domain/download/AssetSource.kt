package com.sabreware.aide.core.domain.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okio.Path

/**
 * Everything the download stack needs to know about one family of downloadable asset: where an id's bytes
 * come from, where they land, whether they are already installed, and how to remove them.
 *
 * This is the extension point for the whole stack. Adding a downloadable capability — an image model, a
 * cloud voice pack — is one `AssetSource` binding: the scheduler, the progress plumbing, the notification
 * and the resume logic are already written and know nothing about what they are fetching.
 */
interface AssetSource {

    /** The [com.sabreware.aide.core.domain.download.AssetKind] this source serves. */
    val kind: String

    /**
     * Resolve an id to a concrete job, or null when this source does not know it.
     *
     * Suspending, like every other member here that touches the disk: resolving a job means asking the
     * storage where the bytes are and creating the directory they will land in. A plain function hid that
     * behind a signature no caller could reason about.
     */
    suspend fun resolve(id: String): DownloadAsset?

    /** Sanity-check the landed file before it counts as installed. */
    suspend fun verify(asset: DownloadAsset): Boolean

    /** Unpack / install step after a verified download. Most assets need none. */
    suspend fun postProcess(asset: DownloadAsset): Unit = Unit

    /** On-disk state, which is authoritative: nothing in a work queue can revoke a file that exists. */
    suspend fun isInstalled(asset: DownloadAsset): Boolean

    /** Wipe everything on disk for [asset]. */
    suspend fun delete(asset: DownloadAsset)

    /** Re-emits when the on-disk state changed without the scheduler knowing (a delete, an import). */
    val changes: Flow<Unit> get() = flowOf()
}

/** `kind` namespaces the id so an LLM "foo" and a speech "foo" cannot share a flat work key. */
data class AssetHandle(val kind: String, val id: String) {
    val uniqueWorkName: String get() = "${kind}_download_$id"
    val tag: String get() = "${kind}_download:$id"
}

/**
 * One resolved download job. The engine writes [partFile] and renames to [finalFile], so a crash never
 * leaves a half-written model where a whole one is expected.
 */
data class DownloadAsset(
    val handle: AssetHandle,
    val displayName: String,
    val downloadUrl: String,
    val partFile: Path,
    val finalFile: Path,
    val sizeBytes: Long?,
)

/**
 * The contributed asset sources, indexed by kind — the download stack's peer of the provider registries.
 * Collected with `getAll`, so a module that owns a downloadable capability binds its source and nothing
 * central changes.
 */
class AssetSourceRegistry(sources: Collection<AssetSource>) {

    private val byKind: Map<String, AssetSource> = LinkedHashMap<String, AssetSource>(sources.size).apply {
        sources.forEach { source ->
            val clash = put(source.kind, source)
            require(clash == null) { "Two asset sources claim kind '${source.kind}'" }
        }
    }

    val all: Collection<AssetSource> get() = byKind.values

    operator fun get(kind: String): AssetSource? = byKind[kind]

    /** The source and the resolved job for [handle], or null if either is unknown. */
    suspend fun resolve(handle: AssetHandle): Pair<AssetSource, DownloadAsset>? {
        val source = byKind[handle.kind] ?: return null
        val asset = source.resolve(handle.id) ?: return null
        return source to asset
    }
}
