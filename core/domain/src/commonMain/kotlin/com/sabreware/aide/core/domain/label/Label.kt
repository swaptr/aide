package com.sabreware.aide.core.domain.label

import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.common.persist.Durability
import com.sabreware.aide.core.common.persist.PersistedDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Anything the user can name, tag and pin — a model, a connection, and whatever comes next.
 *
 * Labelling is ONE system rather than a name field on each thing: a downloaded model, a cloud model and a
 * connection are renamed, tagged and pinned the same way, through the same actions and the same sheets, and
 * share one tag vocabulary. A new kind of thing gains all of it by minting a subject — nothing about it has to
 * grow a `customName` column.
 *
 * [key] is `"<kind>:<id>"`. An id may itself contain `:` (cloud model ids are `"<connectionId>:<wireId>"`);
 * only the first separator splits.
 */
@JvmInline
value class LabelSubject(val key: String) {
    val kind: String get() = key.substringBefore(SEPARATOR)
    val id: String get() = key.substringAfter(SEPARATOR)

    companion object {
        const val MODEL = "model"
        const val CONNECTION = "connection"
        const val CHAT = "chat"
        const val CONNECTOR = "connector"
        private const val SEPARATOR = ':'

        fun model(id: String): LabelSubject = LabelSubject("$MODEL$SEPARATOR$id")
        fun connection(id: String): LabelSubject = LabelSubject("$CONNECTION$SEPARATOR$id")
        fun chat(id: String): LabelSubject = LabelSubject("$CHAT$SEPARATOR$id")
        fun connector(url: String): LabelSubject = LabelSubject("$CONNECTOR$SEPARATOR$url")

        /** True for [connectionId] itself and for every model it serves — what removing it takes with it. */
        fun ownedBy(connectionId: String): (LabelSubject) -> Boolean {
            val self = connection(connectionId).key
            val models = "${model(connectionId).key}$SEPARATOR"
            return { it.key == self || it.key.startsWith(models) }
        }
    }
}

/** The user's marks on one subject. A label with nothing set is not stored. */
@Serializable
data class Label(
    /** The user's name for it; null shows the thing's own name. */
    val alias: String? = null,
    /** Tag names, in the order the user added them. Each is in [Labels.tags]. */
    val tags: List<String> = emptyList(),
    /** When it was pinned (epoch ms), for a stable pinned order; null when not pinned. */
    val pinnedAt: Long? = null,
) {
    val pinned: Boolean get() = pinnedAt != null
    val isEmpty: Boolean get() = alias == null && tags.isEmpty() && pinnedAt == null
}

/**
 * The [LabelDocuments.Labels] document: every subject's label, and the tag vocabulary.
 *
 * Tags are first-class, not a by-product of assignment: the user can create one before using it, rename it
 * everywhere at once, and delete it from everything. Every transform keeps the invariant that a subject only
 * carries tags that exist in [tags].
 */
@Serializable
data class Labels(
    val bySubject: Map<String, Label> = emptyMap(),
    val tags: List<String> = emptyList(),
) {
    operator fun get(subject: LabelSubject): Label = bySubject[subject.key] ?: NONE

    /** [subject]'s alias, or [fallback] when it has none. */
    fun nameOf(subject: LabelSubject, fallback: String): String = get(subject).alias ?: fallback

    fun rename(subject: LabelSubject, alias: String?, originalName: String? = null): Labels {
        val clean = alias?.trim()?.takeIf { it.isNotEmpty() && it != originalName }
        return edit(subject) { it.copy(alias = clean) }
    }

    fun setPinned(subject: LabelSubject, pinned: Boolean, now: Long): Labels =
        edit(subject) { if (pinned == it.pinned) it else it.copy(pinnedAt = if (pinned) now else null) }

    /** Sets [subject]'s tags; any tag not yet in the vocabulary is created. */
    fun setTags(subject: LabelSubject, tags: List<String>): Labels {
        val clean = normalizeTags(tags)
        return withTags(clean).edit(subject) { label -> label.copy(tags = clean.map { canonical(it) ?: it }) }
    }

    /** Adds [name] to the vocabulary (a no-op when it exists, whatever its case). */
    fun createTag(name: String): Labels = withTags(listOf(name))

    /** Renames a tag everywhere. Renaming onto an existing tag merges the two. */
    fun renameTag(from: String, to: String): Labels {
        val target = to.trim().takeIf { it.isNotEmpty() } ?: return this
        val old = canonical(from) ?: return this
        val existing = canonical(target)?.takeIf { it != old }
        val renamed = existing ?: target
        return copy(
            tags = if (existing != null) tags - old else tags.map { if (it == old) renamed else it },
            bySubject = bySubject.mapValues { (_, label) ->
                label.copy(tags = label.tags.map { if (it == old) renamed else it }.distinctBy { it.lowercase() })
            },
        )
    }

    /** Deletes a tag from the vocabulary and from everything that carried it. */
    fun deleteTag(name: String): Labels {
        val old = canonical(name) ?: return this
        return copy(
            tags = tags - old,
            bySubject = bySubject.mapValues { (_, label) -> label.copy(tags = label.tags - old) }
                .filterValues { !it.isEmpty },
        )
    }

    /** Drops every subject [matches] selects — what removing a connection does to its labels. */
    fun without(matches: (LabelSubject) -> Boolean): Labels =
        copy(bySubject = bySubject.filterKeys { !matches(LabelSubject(it)) })

    /** Subjects pinned, oldest pin first. */
    val pinned: List<LabelSubject>
        get() = bySubject.entries.filter { it.value.pinned }.sortedBy { it.value.pinnedAt }.map { LabelSubject(it.key) }

    /** Subjects carrying [tag]. */
    fun tagged(tag: String): Set<LabelSubject> =
        bySubject.filterValues { label -> label.tags.any { it.equals(tag, ignoreCase = true) } }
            .keys.mapTo(HashSet(), ::LabelSubject)

    /** How many subjects carry each tag, in vocabulary order. */
    val tagUsage: Map<String, Int>
        get() = tags.associateWith { tag -> bySubject.values.count { tag in it.tags } }

    private fun canonical(name: String): String? = tags.firstOrNull { it.equals(name.trim(), ignoreCase = true) }

    private fun withTags(names: List<String>): Labels {
        val added = normalizeTags(names).filter { canonical(it) == null }
        return if (added.isEmpty()) this else copy(tags = tags + added)
    }

    private fun edit(subject: LabelSubject, transform: (Label) -> Label): Labels {
        val next = transform(get(subject))
        return copy(bySubject = if (next.isEmpty) bySubject - subject.key else bySubject + (subject.key to next))
    }

    companion object {
        val NONE = Label()
    }
}

/** Tags as the user typed them: trimmed, blank-free, de-duplicated case-insensitively. */
fun normalizeTags(tags: List<String>): List<String> =
    tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

/** The label documents this module owns; [all] is checked against `core/domain/schemas/documents/`. */
object LabelDocuments {
    val Labels = PersistedDocument(
        name = "labels",
        version = 1,
        // Type position: the bare name here would resolve to this property, not the class.
        serializer = serializer<com.sabreware.aide.core.domain.label.Labels>(),
        default = Labels(),
        // The user's own words: an unreadable file keeps a copy rather than silently resetting.
        durability = Durability.Intent,
    )

    val all: List<PersistedDocument<*>> = listOf(Labels)
}

/** The user's labels (see [LabelDocuments.Labels]); a named port because generics erase in the container. */
interface LabelStore {
    /** [DocState.Loading] until the file's first read lands. */
    val state: StateFlow<DocState<Labels>>

    /** Atomic read-modify-write against the file (never a replay value). */
    suspend fun update(transform: (Labels) -> Labels)
}

/** The Ready values of [LabelStore.state]; nothing while it is still loading. */
val LabelStore.labels: Flow<Labels>
    get() = state.filterIsInstance<DocState.Ready<Labels>>().map { it.value }

/** The current labels, or empty while loading. For painting only — never the read half of a write. */
val LabelStore.labelsNow: Labels
    get() = (state.value as? DocState.Ready)?.value ?: Labels()

/**
 * The Tag facet over any labelled collection: [subject] names each item's label subject, and the options are
 * the user's tag vocabulary in its own order — so the same filter works on models, connections, or anything
 * that is ever labelled.
 */
fun <T> Labels.tagFacet(subject: (T) -> LabelSubject): com.sabreware.aide.core.domain.browse.Facet<T> =
    com.sabreware.aide.core.domain.browse.Facet(
        id = TAG_FACET,
        label = "Tag",
        valuesOf = { item -> this[subject(item)].tags },
        options = tags,
    )

/** The id of [tagFacet], for a surface that preselects a tag. */
const val TAG_FACET = "tag"
