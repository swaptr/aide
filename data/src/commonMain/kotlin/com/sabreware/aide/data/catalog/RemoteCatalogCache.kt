package com.sabreware.aide.data.catalog

import com.sabreware.aide.core.common.persist.DocumentStore
import com.sabreware.aide.core.common.persist.Durability
import com.sabreware.aide.core.common.persist.PersistedDocument
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ProviderId
import kotlinx.serialization.Serializable

/** One model as it is persisted: the wire id, plus whatever display name the listing carried. */
@Serializable
data class CachedModel(val id: String, val displayName: String? = null)

/**
 * A provider's last-good listing, the endpoint it came from, and when it landed.
 *
 * [endpoint] is part of the record rather than just the file name: one OpenAI-compatible connection can be
 * repointed from Ollama to OpenRouter, so a listing is only valid for the base URL that
 * produced it. Repoint the base URL and the cache misses instead of offering the old server's models.
 */
@Serializable
data class CachedListing(
    val endpoint: String = "",
    val fetchedAt: Long = 0L,
    val models: List<CachedModel> = emptyList(),
)

/** The persistable shadow of a fetched listing: identity only — capabilities are re-derived on read. */
fun List<ChatModelSpec>.toCachedListing(endpoint: String, fetchedAt: Long): CachedListing = CachedListing(
    endpoint = endpoint,
    fetchedAt = fetchedAt,
    models = mapNotNull { spec -> spec.remoteName?.let { CachedModel(it, spec.displayName) } },
)

/** Every provider's last-good listing, keyed by [ProviderId.value] — the [RemoteCatalogCache.Document] schema. */
@Serializable
data class RemoteCatalogs(val listings: Map<String, CachedListing> = emptyMap())

/**
 * The last-good remote catalog on disk, so a cold start resolves cloud models WITHOUT a network round trip.
 *
 * Cloud specs used to exist only in RAM, minted from a live `/v1/models`. A fresh process therefore held no
 * spec for the model the user had already picked: the chat header flashed "No model", the composer said
 * "Set up a model to begin", and both the IME and the assistant gate read `NoModel` — until the deferred
 * [com.sabreware.aide.data.llm.CatalogRefreshBootstrap] fetch landed, or forever on a dead network.
 *
 * Only the *identity* is cached. Capabilities come from the bundled models.dev snapshot
 * ([RemoteModelMetadata], a Compose resource), so specs are re-minted offline through the same
 * [RemoteCatalog] builders, with fresh caps and no per-spec serializer to keep in step with the spec types.
 *
 * One [PersistedDocument] ([Document]) rather than a hand-rolled JSON file per provider: the schema is
 * versioned in `data/schemas/documents/`, writes are atomic (a torn write used to cost a cache miss), and
 * every provider's read-modify-write is serialized through one store. Refetchable by definition, so it is
 * [Durability.Cache]: an OS that reclaims it costs one cold start's worth of the old behaviour, never
 * correctness.
 */
class RemoteCatalogCache(private val store: DocumentStore<RemoteCatalogs>) {

    /** [provider]'s listing for [endpoint], or null on a miss, an endpoint change, or an unreadable file. */
    suspend fun read(provider: ProviderId, endpoint: String): CachedListing? =
        store.awaitReady().listings[provider.value]
            ?.takeIf { it.endpoint == endpoint && it.models.isNotEmpty() }

    /** Records [listing] as [provider]'s last-good. A write that fails costs a cache miss, never a crash. */
    suspend fun write(provider: ProviderId, listing: CachedListing) {
        store.update { it.copy(listings = it.listings + (provider.value to listing)) }
    }

    /** Forgets [provider]'s listing — its credentials are gone, so its models are not ours to offer. */
    suspend fun clear(provider: ProviderId) {
        store.update { if (provider.value in it.listings) it.copy(listings = it.listings - provider.value) else it }
    }

    companion object {
        val Document = PersistedDocument(
            name = "remote_catalogs",
            version = 1,
            serializer = RemoteCatalogs.serializer(),
            default = RemoteCatalogs(),
            durability = Durability.Cache,
        )
    }
}
