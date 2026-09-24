package com.sabreware.aide.core.domain.connector

/**
 * An icon to load for a connector: a stable [connectorId] plus the ordered fallback URL [candidates]
 * (see [IconResolver]). Pure data so the UI can build it without touching the data layer; the Coil
 * Keyer/Fetcher live in `data` and consume this.
 */
data class ConnectorIconRequest(val connectorId: String, val candidates: List<String>)
