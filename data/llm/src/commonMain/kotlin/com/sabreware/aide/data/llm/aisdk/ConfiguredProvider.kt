package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.core.domain.provider.ProviderConfig
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.StateFlow

/**
 * A vendor's `:aisdk` provider, rebuilt only when its stored configuration changes.
 *
 * Every engine here takes a factory rather than a provider, because a key pasted into Settings has to
 * take effect on the next call rather than the next launch. The naive factory rebuilt the provider on
 * every call — per chat turn, per spoken sentence, per ladder walk that asked a vendor whether it could
 * transcribe — and a provider is a handful of objects plus a URL parse each time. This keeps the
 * "next call sees the new key" property, since [ProviderConfig] is a value and a changed key is a
 * different value, while paying for construction once per configuration.
 *
 * Null when nothing is configured, or when [build] decides the configuration is not enough (no key
 * for a vendor that needs one): the engines already read null as "not configured — add an API key".
 */
class ConfiguredProvider<P : Any>(
    private val state: StateFlow<ProviderConfig?>,
    private val build: (ProviderConfig) -> P?,
) : () -> P? {

    private class Built<P>(val config: ProviderConfig, val provider: P?)

    private val cache = atomic<Built<P>?>(null)

    override fun invoke(): P? {
        val config = state.value ?: return null
        cache.value?.let { built -> if (built.config == config) return built.provider }
        val provider = build(config)
        cache.value = Built(config, provider)
        return provider
    }
}
