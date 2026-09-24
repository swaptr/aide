package com.sabreware.aide.data.connector

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorSource

/**
 * Merges connector slices from several directories into one catalog. Pure → unit-tested.
 *
 * Dedup key = brand domain (else normalized name). On collision the **earlier (higher-priority) source wins**
 * the load-bearing fields (serverUrl, authType, popularityRank, category, iconSlug — we trust the hand-curated
 * OAuth URLs); a later source only *fills* blank description / website / repo and flips [ConnectorSource.MERGED].
 * Source-unique entries pass through. Sort: ranked by rank ascending, then the rest alphabetically.
 */
object ConnectorMerge {

    /**
     * Fold [sources] left-to-right; **[sources]`[0]` has the highest precedence** (e.g. the curated overlay
     * first, then registries by ascending priority). Empty input → empty output.
     */
    fun mergeAll(sources: List<List<Connector>>): List<Connector> {
        // LinkedHashMap preserves first-seen order (highest-priority source first); the final sort reorders anyway.
        val byKey = LinkedHashMap<String, Connector>()
        for (source in sources) {
            for (c in source) {
                val k = key(c)
                val existing = byKey[k]
                byKey[k] = if (existing == null) {
                    c
                } else {
                    // `existing` came from an earlier (>= priority) source — it wins; only fill its blanks.
                    existing.copy(
                        description = existing.description.ifBlank { c.description },
                        websiteUrl = existing.websiteUrl ?: c.websiteUrl,
                        repositoryUrl = existing.repositoryUrl ?: c.repositoryUrl,
                        source = ConnectorSource.MERGED,
                    )
                }
            }
        }
        return byKey.values
            // A registry can repeat a server `name` (versions/dupes); ids must be unique or the catalog
            // LazyColumn key invariant blows up on scroll. Keep the first of each id.
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.popularityRank ?: Int.MAX_VALUE }, { it.name.lowercase() }))
    }

    /** Two-source convenience: [curated] over [registry] (curated wins). */
    fun merge(curated: List<Connector>, registry: List<Connector>): List<Connector> =
        mergeAll(listOf(curated, registry))

    private fun key(c: Connector): String =
        c.brandDomain?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?: c.name.lowercase().filter(Char::isLetterOrDigit)
}
