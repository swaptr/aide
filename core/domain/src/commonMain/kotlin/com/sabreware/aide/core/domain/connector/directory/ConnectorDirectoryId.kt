package com.sabreware.aide.core.domain.connector.directory

/** Stable identity of a [ConnectorDirectory] (a connector data source). Used as the cache key + selection key. */
@JvmInline
value class ConnectorDirectoryId(val value: String)
