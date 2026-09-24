package com.sabreware.aide.data.llm.vendor

import com.sabreware.aide.aisdk.providers.anthropic.ANTHROPIC_DEFAULT_BASE_URL
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicProvider as AisdkAnthropicProvider
import com.sabreware.aide.core.domain.connection.Connection
import com.sabreware.aide.core.domain.connection.ConnectionRuntime
import com.sabreware.aide.core.domain.connection.ServiceDescriptor
import com.sabreware.aide.core.domain.connection.Vendor
import com.sabreware.aide.core.domain.connection.VendorDescriptor
import com.sabreware.aide.core.domain.connection.VendorId
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.provider.ConnectionTestResult
import com.sabreware.aide.core.domain.provider.ProviderConfig
import com.sabreware.aide.data.catalog.RemoteCatalog
import com.sabreware.aide.data.catalog.RemoteModelMetadata
import com.sabreware.aide.data.llm.aisdk.AiSdkLlmEngine
import com.sabreware.aide.data.llm.aisdk.AiSdkModelCatalog
import com.sabreware.aide.data.llm.aisdk.ConfiguredProvider
import com.sabreware.aide.data.llm.remote.RemoteChatProvider
import com.sabreware.aide.data.llm.remote.RemoteProviderManagement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Anthropic (Claude). Chat AND catalog on the `:aisdk` transport: chat is the native provider — the one this
 * whole port exists for, and the only path that preserves a thinking signature across a session rebind; the
 * `/v1/models` listing is the one endpoint the spec does not model, so it rides the catalog client directly.
 */
class AnthropicVendor(private val env: VendorEnvironment) : Vendor {

    override val descriptor = VendorDescriptor(
        id = VendorId.ANTHROPIC,
        displayName = "Anthropic",
        services = listOf(
            ServiceDescriptor(
                id = "anthropic",
                name = "Anthropic",
                vendor = VendorId.ANTHROPIC,
                baseUrl = "https://api.anthropic.com/v1",
                modalities = setOf(Modality.Chat),
                helpUrl = "https://console.anthropic.com/settings/keys",
                blurb = "Claude models.",
            ),
        ),
    )

    override fun connect(connection: Connection, config: StateFlow<ProviderConfig?>, scope: CoroutineScope): ConnectionRuntime {
        val id = connection.providerId
        val listing = AiSdkModelCatalog(env.http)
        fun baseUrl(): String = config.value?.baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: ANTHROPIC_DEFAULT_BASE_URL
        fun headers(): Map<String, String> = AiSdkModelCatalog.anthropicHeaders(config.value?.apiKey)
        val provider = ConfiguredProvider(config) { cfg ->
            cfg.apiKey?.takeIf { it.isNotBlank() }?.let { key -> AisdkAnthropicProvider(client = env.http, apiKey = key, baseUrl = baseUrl()) }
        }
        val chat = RemoteChatProvider(
            id = id,
            chat = AiSdkLlmEngine(provider, logTag = "anthropic", dispatcher = env.dispatcher, readBytes = env.readBytes),
            management = RemoteProviderManagement(
                providerId = id,
                config = config,
                scope = scope,
                catalog = {
                    RemoteModelMetadata.ensureLoaded(env.assets)
                    // /v1/models returns only Claude chat models, so no non-chat trim is needed.
                    listing.listModels(baseUrl(), headers())
                        .map { RemoteCatalog.anthropicSpec(id, it.id, RemoteModelMetadata::capabilitiesFor, it.displayName) }
                        .sortedBy { it.displayName.lowercase() }
                },
                connectionTester = {
                    if (config.value?.apiKey.isNullOrBlank()) ConnectionTestResult.Failed("Add an API key")
                    else listing.testConnection(baseUrl(), headers())
                },
                cache = env.catalogCache,
                mint = { RemoteModelMetadata.ensureLoaded(env.assets); RemoteCatalog.anthropicSpec(id, it.id, RemoteModelMetadata::capabilitiesFor, it.displayName) },
                metadata = { RemoteModelMetadata.ensureLoaded(env.assets); RemoteModelMetadata.metadataFor(it) },
            ),
        )
        return ConnectionRuntime(connection = connection, chat = chat, manageable = chat)
    }
}
