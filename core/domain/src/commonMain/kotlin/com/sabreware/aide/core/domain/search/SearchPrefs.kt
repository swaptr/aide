package com.sabreware.aide.core.domain.search

import com.sabreware.aide.core.common.prefs.boolKey
import com.sabreware.aide.core.common.prefs.nullableEnumKey

/**
 * Web-search preferences, declared next to the feature that owns them. Key names and defaults are exactly
 * what `UserPreferencesRepository` stored, so existing installs keep their values.
 */
object SearchPrefs {
    val Enabled = boolKey("web_search_enabled", default = false)

    /** null = "auto": the resolver walks its provider priority list. */
    val ProviderOverride = nullableEnumKey<WebSearchProviderId>("web_search_provider_override")
}
