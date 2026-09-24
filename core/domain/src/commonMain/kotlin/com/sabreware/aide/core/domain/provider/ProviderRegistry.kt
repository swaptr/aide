package com.sabreware.aide.core.domain.provider

import com.sabreware.aide.core.domain.llm.Provider
import com.sabreware.aide.core.domain.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Every provider that serves one capability, indexed by [ProviderId]. One registry per capability
 * ([com.sabreware.aide.core.domain.llm.ChatProviderRegistry],
 * [com.sabreware.aide.core.domain.speech.SpeechProviderRegistry], …), so a consumer asks for exactly the
 * capability it needs — no base-`Provider` collection, no `filterIsInstance`, no downcast.
 *
 * **Two sources, one registry.** [static] is whatever the DI graph contributed (`getAll<ChatProvider>()`):
 * the on-device engines, fixed for the life of the process. [dynamic] is the providers of the user's
 * connections ([com.sabreware.aide.core.domain.connection.ConnectionRuntimes]), which come and go as
 * accounts are added and removed — null until the connections have been read, because "not read yet" must
 * never look like "nothing connected".
 *
 * **Painting vs acting.** [get] and [all] answer from what is known right now and are for painting; a
 * consumer about to ACT on a provider it expects to exist ([await]) waits for the connections to be read, so
 * a cold start never reports a pinned cloud engine or a chosen cloud model as gone.
 *
 * Subclass rather than parameterise at the injection site: generics erase, so `ProviderRegistry<ChatProvider>`
 * and `ProviderRegistry<Manageable>` are the same runtime type and would collide in the container. A named
 * subtype has a runtime class of its own and needs no qualifier.
 */
open class ProviderRegistry<P : Provider>(
    static: Collection<P>,
    private val dynamic: StateFlow<List<P>?> = MutableStateFlow(emptyList<P>()).asStateFlow(),
) {
    private val static: List<P> = static.toList()

    init {
        val seen = HashSet<String>()
        this.static.forEach { provider ->
            require(seen.add(provider.id.value)) { "Two providers claim id '${provider.id.value}'" }
        }
    }

    /** Every provider known right now: the contributed ones, then the connections'. */
    val all: List<P> get() = static + dynamic.value.orEmpty()

    /** [all] as it changes; null until the connections have been read. */
    val flow: Flow<List<P>?> = dynamic.map { connected -> connected?.let { static + it } }

    /** True once the connections have been read — until then, an absent id is "not yet", not "gone". */
    val isSettled: Boolean get() = dynamic.value != null

    val ids: Set<ProviderId> get() = all.mapTo(LinkedHashSet()) { it.id }

    operator fun get(id: ProviderId): P? = get(id.value)

    /** Lookup by the raw routing string (what a persisted `ModelSpec.provider` carries). */
    operator fun get(id: String): P? = static.firstOrNull { it.id.value == id } ?: dynamic.value?.firstOrNull { it.id.value == id }

    /** The provider for [id] once the connections are known; null only when it truly does not exist. */
    suspend fun await(id: ProviderId): P? {
        static.firstOrNull { it.id == id }?.let { return it }
        return dynamic.filterNotNull().first().firstOrNull { it.id == id }
    }

    /** The provider for [id], or an error naming what *is* registered — a wiring bug, never user input. */
    fun require(id: ProviderId): P = get(id)
        ?: error("No provider registered for '${id.value}' (have: ${ids.joinToString { it.value }})")

    fun isEmpty(): Boolean = all.isEmpty()
}
