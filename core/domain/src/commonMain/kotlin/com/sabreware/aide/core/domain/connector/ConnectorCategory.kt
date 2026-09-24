package com.sabreware.aide.core.domain.connector

/**
 * Catalog categories for the browse tabs. [ALL] is a tab-only aggregate — it is never persisted on a
 * [Connector]. The registry has no category field, so registry entries are bucketed by a keyword
 * heuristic (RegistryCategoryHeuristic); curated entries carry their category explicitly.
 */
enum class ConnectorCategory(val label: String) {
    ALL("All"),
    PRODUCTIVITY("Productivity"),
    COMMUNICATION("Communication"),
    CODE("Code"),
    DESIGN("Design"),
    DATA("Data"),
    KNOWLEDGE("Knowledge"),
    OTHER("Other"),
}
