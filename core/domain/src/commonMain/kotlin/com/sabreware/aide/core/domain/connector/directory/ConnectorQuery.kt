package com.sabreware.aide.core.domain.connector.directory

import com.sabreware.aide.core.domain.connector.Connector

/**
 * The shared "local search": a case-insensitive name + description substring filter over an in-memory slice.
 * Used by bundled directories and as the offline fallback for remote ones. A blank query returns everything.
 */
fun List<Connector>.matchingQuery(query: String): List<Connector> {
    val q = query.trim()
    if (q.isBlank()) return this
    return filter { it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }
}
