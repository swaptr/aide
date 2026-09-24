package com.sabreware.aide.core.domain.connection

import com.sabreware.aide.core.domain.model.ProviderCatalog
import com.sabreware.aide.core.domain.model.ProviderId
import kotlinx.coroutines.flow.StateFlow

/** How any provider — an on-device engine or one of the user's connections — is shown and filed. */
data class ProviderInfo(
    val id: ProviderId,
    /** The user's name for it (their alias, else the connection's own name, else the engine's). */
    val name: String,
    val kind: ConnectionKind,
    /** The service's name ("OpenRouter", "Anthropic"); null for an on-device engine. */
    val vendorName: String? = null,
    val vendor: VendorId? = null,
    val tags: List<String> = emptyList(),
    /** The endpoint, for a connection. */
    val baseUrl: String? = null,
)

/**
 * The one answer to "what is this provider called, and where does it run?" — for every surface that names a
 * model's source (a row subtitle, the voice-engine picker, a filter option). Built from the connections, the
 * user's labels and the vendors, so a rename anywhere shows everywhere.
 */
interface ProviderDirectory {
    /** Every connection's info by provider id; null until the connections have been read. */
    val connections: StateFlow<Map<String, ProviderInfo>?>

    /** [id]'s info right now. For painting: an unread or removed connection falls back to its raw id. */
    fun infoOf(id: ProviderId): ProviderInfo = connections.value?.get(id.value) ?: builtIn(id)

    companion object {
        /** An on-device engine's info, or a placeholder for an id that is not (yet) a known connection. */
        fun builtIn(id: ProviderId): ProviderInfo {
            val descriptor = ProviderCatalog.of(id)
            return ProviderInfo(
                id = id,
                name = descriptor.displayName,
                kind = if (ProviderCatalog.isBuiltIn(id)) ConnectionKind.OnDevice else ConnectionKind.Cloud,
            )
        }
    }
}
