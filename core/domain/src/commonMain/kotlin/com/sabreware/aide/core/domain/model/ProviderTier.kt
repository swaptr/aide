package com.sabreware.aide.core.domain.model

/**
 * "Last-used" selection bucket. Splits providers into user-facing tiers (a cloud endpoint and a
 * self-hosted server feel like distinct sources even though they share a wire protocol). A
 * string-backed value class (was a closed enum) so new providers get their own tiers without
 * editing this type; derived from the typed [ModelSpec.provider] + [ModelSpec.cloud] pair, never
 * from id-prefix parsing. [key] is the persisted map key ([ModelSelection.lastUsedByTier]).
 */
@JvmInline
value class ProviderTier(val key: String) {
    companion object {
        val LOCAL = ProviderTier("local")

        fun of(spec: ModelSpec): ProviderTier = of(spec.provider, spec.cloud)

        fun of(provider: ProviderId, cloud: Boolean): ProviderTier = when {
            provider == ProviderId.LOCAL -> LOCAL
            cloud -> ProviderTier("${provider.value}-cloud")
            else -> ProviderTier(provider.value)
        }
    }
}
