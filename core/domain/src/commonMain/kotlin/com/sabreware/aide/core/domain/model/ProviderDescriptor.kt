package com.sabreware.aide.core.domain.model

/**
 * Static traits of the providers that ship inside the app, which data-drive the branch sites that used to
 * switch on [ProviderId] identity.
 *
 * @property local manages resident on-device weights (LiteRT, Sherpa): needs a download and the
 *   single-resident load lifecycle. The Android system engine has no weights, so it is not local.
 */
data class ProviderDescriptor(
    val id: ProviderId,
    val local: Boolean,
    val displayName: String,
)

/**
 * The built-in providers. Every other id is a user connection: remote by construction, and named by the
 * user (see `com.sabreware.aide.core.domain.connection.ConnectionNames`), so [of] degrades an unknown id to
 * a remote descriptor rather than crashing.
 */
object ProviderCatalog {
    private val byId: Map<ProviderId, ProviderDescriptor> = listOf(
        ProviderDescriptor(ProviderId.LOCAL, local = true, displayName = "On-device"),
        ProviderDescriptor(ProviderId.SHERPA, local = true, displayName = "Sherpa-ONNX"),
        ProviderDescriptor(ProviderId.ANDROID_SYSTEM, local = false, displayName = "Android System"),
    ).associateBy { it.id }

    fun of(id: ProviderId): ProviderDescriptor =
        byId[id] ?: ProviderDescriptor(id, local = false, displayName = id.value)

    /** True for a provider that ships with the app; false for a user connection. */
    fun isBuiltIn(id: ProviderId): Boolean = id in byId
}
