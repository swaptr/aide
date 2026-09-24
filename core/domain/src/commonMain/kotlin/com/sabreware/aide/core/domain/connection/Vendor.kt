package com.sabreware.aide.core.domain.connection

import com.sabreware.aide.core.domain.image.ImageProvider
import com.sabreware.aide.core.domain.llm.ChatProvider
import com.sabreware.aide.core.domain.llm.Manageable
import com.sabreware.aide.core.domain.model.ImageModelSpec
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.provider.ProviderConfig
import com.sabreware.aide.core.domain.speech.CloudSpeechModelSpec
import com.sabreware.aide.core.domain.speech.SpeechProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * A service the user can connect: OpenAI, OpenRouter, Ollama, Anthropic… — the one flat, deduplicated catalogue
 * the connect surfaces list. Several services can share a vendor (OpenRouter, Groq and Ollama all speak the
 * OpenAI-compatible wire), but each is listed once, whatever modality the user came for: one connection
 * serves every modality its service does.
 *
 * [id] is stable ("openrouter"); a connection does not store it — a connection's service is whichever one
 * its base URL matches ([VendorDescriptor.serviceFor]), so editing the URL re-files it.
 */
data class ServiceDescriptor(
    val id: String,
    val name: String,
    val vendor: VendorId,
    /** Where a new connection points. Blank for a custom endpoint the user types. */
    val baseUrl: String,
    val modalities: Set<Modality>,
    /** False where a keyless server is a normal setup (a self-hosted Ollama). */
    val requiresKey: Boolean = true,
    /** Where to get a key. */
    val helpUrl: String? = null,
    /** One line on what it is, for the catalogue. */
    val blurb: String? = null,
    /** A catch-all for any server on the vendor's wire: the URL is the user's to type. */
    val custom: Boolean = false,
)

/**
 * Everything the UI needs about a vendor: its name and the services it offers.
 *
 * [modalities] is what the vendor CAN serve — the union of its services'. What one connection actually
 * serves can be narrower (an Ollama endpoint on the OpenAI-compatible wire has no speech or image
 * endpoints); the connection's own runtime answers that.
 */
data class VendorDescriptor(
    val id: VendorId,
    val displayName: String,
    val services: List<ServiceDescriptor>,
) {
    val modalities: Set<Modality> get() = services.flatMapTo(LinkedHashSet()) { it.modalities }

    /** The service whose endpoint is [baseUrl]; the vendor's custom service (or its first) otherwise. */
    fun serviceFor(baseUrl: String): ServiceDescriptor {
        val url = baseUrl.trim().trimEnd('/')
        return services.firstOrNull { !it.custom && it.baseUrl.trimEnd('/') == url }
            ?: services.firstOrNull { it.custom }
            ?: services.first()
    }
}

/**
 * A kind of API, contributed in the DI graph (`single { … } bind Vendor::class`) exactly like every other
 * capability. [connect] turns one of the user's connections into the providers it serves.
 */
interface Vendor {
    val descriptor: VendorDescriptor

    /**
     * The providers [connection] serves, built once for the life of the connection. [config] is the
     * connection's live base URL + key (a pasted key takes effect on the next call); [scope] is the
     * connection's own, cancelled when the user removes it or moves it to another endpoint — anything
     * long-lived goes in it. [connection]'s base URL is fixed for the runtime's life, so what the endpoint
     * serves (its speech and image rows) is decided here once.
     */
    fun connect(connection: Connection, config: StateFlow<ProviderConfig?>, scope: CoroutineScope): ConnectionRuntime
}

/**
 * One connection's providers, by capability. Typed slots rather than one object downcast per registry: a
 * registry reads exactly the slot it collects, and a vendor that does not serve a capability leaves it null.
 * Every non-null slot's `id` is [id].
 */
class ConnectionRuntime(
    val connection: Connection,
    val chat: ChatProvider? = null,
    val manageable: Manageable? = null,
    val image: ImageProvider? = null,
    val speech: SpeechProvider? = null,
    /** The curated speech models this connection serves (its vendor's, narrowed to what its endpoint has). */
    val speechModels: List<CloudSpeechModelSpec> = emptyList(),
    /** The curated image models this connection serves. */
    val imageModels: List<ImageModelSpec> = emptyList(),
) {
    val id: ProviderId get() = connection.providerId
}

/**
 * The live providers of every connection. Null until the connections document has been read: "not read
 * yet" must not look like "nothing connected", or every cloud pick would read as missing on a cold start.
 */
interface ConnectionRuntimes {
    val runtimes: StateFlow<List<ConnectionRuntime>?>
}

/** Every vendor this application contributed, in binding order, and the services they offer. */
class VendorRegistry(vendors: Collection<Vendor>) {
    private val byId: Map<String, Vendor> = LinkedHashMap<String, Vendor>().apply {
        vendors.forEach { vendor ->
            val clash = put(vendor.descriptor.id.value, vendor)
            require(clash == null) { "Two vendors claim id '${vendor.descriptor.id.value}'" }
        }
    }

    val all: Collection<Vendor> get() = byId.values
    val descriptors: List<VendorDescriptor> get() = byId.values.map { it.descriptor }

    /** Every connectable service, each once, by name — custom endpoints last. Independent of binding order. */
    val services: List<ServiceDescriptor> =
        descriptors.flatMap { it.services }.distinctBy { it.id }
            .sortedWith(compareBy<ServiceDescriptor>({ it.custom }, { it.name.lowercase() }))

    init {
        val ids = descriptors.flatMap { d -> d.services.map { it.id } }
        require(ids.size == ids.toSet().size) { "Two services share an id: $ids" }
    }

    operator fun get(id: VendorId): Vendor? = byId[id.value]
    operator fun get(id: String): Vendor? = byId[id]

    fun service(id: String): ServiceDescriptor? = services.firstOrNull { it.id == id }

    /** The service [connection] is: the one its vendor recognises its endpoint as. */
    fun serviceOf(connection: Connection): ServiceDescriptor? = byId[connection.vendor]?.descriptor?.serviceFor(connection.baseUrl)

    /** The services that can serve [modality], in catalogue order. */
    fun servicesFor(modality: Modality): List<ServiceDescriptor> = services.filter { modality in it.modalities }
}
