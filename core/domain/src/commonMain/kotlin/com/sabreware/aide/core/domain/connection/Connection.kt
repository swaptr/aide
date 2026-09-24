package com.sabreware.aide.core.domain.connection

import com.sabreware.aide.core.common.persist.Durability
import com.sabreware.aide.core.common.persist.PersistedDocument
import com.sabreware.aide.core.domain.model.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.random.Random

/**
 * One account or endpoint the user connected: an instance of a [VendorId], named by the user.
 *
 * A connection is DATA, the vendor is code. Two OpenRouter keys, a work and a personal Anthropic account, a
 * local Ollama next to Ollama Cloud: each is its own connection with its own [id], and that id IS the
 * runtime [ProviderId] its models, catalog, engine and speech rung are keyed by. Nothing else distinguishes
 * one account of a vendor from another, which is what lets every `ProviderId`-keyed seam stay as it was.
 *
 * The API key is never part of this record: it lives in the secure store under [secretKey], so this
 * document holds nothing that needs decrypting and can be read with the rest of the app's documents.
 */
@Serializable
data class Connection(
    val id: String,
    /** [VendorId.value]; a persisted record stores strings, not value classes. */
    val vendor: String,
    /**
     * The name the connection was created with — never blank, unique among connections ("OpenRouter 2").
     * The user's own name for it, like their tags and pin, is a [com.sabreware.aide.core.domain.label.Label]
     * on [com.sabreware.aide.core.domain.label.LabelSubject.connection], the one system that names anything.
     */
    val label: String,
    val baseUrl: String,
    val createdAt: Long = 0,
) {
    val providerId: ProviderId get() = ProviderId(id)
    val vendorId: VendorId get() = VendorId(vendor)

    companion object {
        /** Secure-store key of the API key for connection [id]. */
        fun secretKey(id: String): String = "connection.$id.api_key"

        /** A fresh id for a connection of [vendor]: readable in logs, unique in practice. */
        fun newId(vendor: VendorId, random: Random = Random.Default): String {
            val suffix = (1..ID_SUFFIX_LENGTH).map { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] }.joinToString("")
            return "${vendor.value}-$suffix"
        }

        private const val ID_SUFFIX_LENGTH = 6
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}

/** The [ConnectionDocuments.Connections] document: every connection, in the order the user added them. */
@Serializable
data class Connections(val list: List<Connection> = emptyList()) {
    operator fun get(id: String): Connection? = list.firstOrNull { it.id == id }

    fun upsert(connection: Connection): Connections =
        if (list.any { it.id == connection.id }) copy(list = list.map { if (it.id == connection.id) connection else it })
        else copy(list = list + connection)

    fun without(id: String): Connections = copy(list = list.filterNot { it.id == id })

    /** [base] if no connection uses it, else the first free "base 2", "base 3", … */
    fun uniqueLabel(base: String, except: String? = null): String {
        val taken = list.filter { it.id != except }.mapTo(HashSet()) { it.label.lowercase() }
        if (base.lowercase() !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in taken }
    }
}

/**
 * What a connect or edit form submits. [apiKey] null or blank stores no key (a self-hosted server needs
 * none). [label] blank means "name it after the endpoint".
 */
data class ConnectionDraft(
    val vendor: VendorId,
    val label: String,
    val baseUrl: String,
    val apiKey: String?,
)

/**
 * The connection documents this module owns. [all] is what `PersistedDocumentSchemaTest` checks against
 * `core/domain/schemas/documents/`.
 */
object ConnectionDocuments {
    val Connections = PersistedDocument(
        name = "connections",
        version = 1,
        // Type position: the bare name here would resolve to this property, not the class.
        serializer = serializer<com.sabreware.aide.core.domain.connection.Connections>(),
        default = Connections(),
        durability = Durability.Intent,
        // An empty list reads as "nothing connected" and would drop every cloud model while the file loads.
        defaultWhileUnreadable = false,
    )

    val all: List<PersistedDocument<*>> = listOf(Connections)
}
