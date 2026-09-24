package com.sabreware.aide.core.domain.connector

/**
 * Ordered icon URL candidates for a connector (no bundled assets, all derived from the URL):
 *  1. the connector's own `apple-touch-icon.png` (colored, real brand mark, no third party),
 *  2. its `favicon.ico`,
 *  3. the Simple Icons CDN SVG by slug (brand-colored vector).
 * If all fail, the row renders an on-device monogram (handled in the composable, not here). Pure → tested.
 */
object IconResolver {

    fun request(connector: Connector): ConnectorIconRequest =
        ConnectorIconRequest(connector.id, candidates(connector))

    fun candidates(connector: Connector): List<String> {
        val out = ArrayList<String>(3)
        connector.brandDomain?.trim()?.takeIf { it.isNotBlank() }?.let { domain ->
            out += "https://$domain/apple-touch-icon.png"
            out += "https://$domain/favicon.ico"
        }
        slug(connector)?.let { out += "https://cdn.simpleicons.org/$it" }
        return out.distinct()
    }

    fun slug(connector: Connector): String? =
        connector.iconSlug?.trim()?.takeIf { it.isNotBlank() }
            ?: ConnectorIconSlugs.override(connector.name)
            ?: connector.name.lowercase().filter(Char::isLetterOrDigit).takeIf { it.isNotBlank() }
}
