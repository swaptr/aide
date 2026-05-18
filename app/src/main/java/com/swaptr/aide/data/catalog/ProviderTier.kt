package com.swaptr.aide.data.catalog

import kotlinx.serialization.Serializable

// Splits OLLAMA into self/cloud (same wire protocol, distinct user buckets for
// "last-used" tracking). Derived from the spec id prefix RemoteCatalog mints.
@Serializable
enum class ProviderTier {
    LOCAL,
    OLLAMA_SELF,
    OLLAMA_CLOUD;

    companion object {
        fun of(spec: ModelSpec): ProviderTier = when (spec.provider) {
            ProviderId.LOCAL -> LOCAL
            ProviderId.OLLAMA -> if (spec.id.startsWith(CLOUD_PREFIX)) OLLAMA_CLOUD else OLLAMA_SELF
        }

        private const val CLOUD_PREFIX = "ollama-cloud:"
    }
}
