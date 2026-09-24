package com.sabreware.aide.core.domain.browse

/**
 * Search, filter and group ANY collection — models, connections, chats, connectors — with one engine.
 *
 * A collection describes itself once as a [BrowseSpec]: how to key an item, which text to search, which
 * [Facet]s narrow it and how to group it. [BrowseSpec.run] applies a [BrowseQuery] and answers everything a
 * surface draws: the matching items (ranked by relevance while searching), the groups, and every facet's
 * options WITH counts. The UI kit (`com.sabreware.aide.core.designsystem.browse`) renders that result the same
 * way on every surface, so a new searchable thing is a spec, never a new search screen.
 *
 * Pure and synchronous: it runs over whatever list the caller has — a local snapshot, or the page a remote
 * search just returned (text matched server-side, facets applied here). Counts are faceted-search counts:
 * an option's count is what the collection would hold if that option were ALSO chosen, so a user never
 * picks an option that empties the list without being told.
 */
class BrowseSpec<T>(
    /** Stable identity, for list keys and selection. */
    val key: (T) -> String,
    /** Searchable text, most important first: the first entry is the item's name and ranks highest. */
    val text: (T) -> List<String>,
    val facets: List<Facet<T>> = emptyList(),
    /** Order when not searching (a search orders by relevance, then by this). Null keeps the input order. */
    val order: Comparator<T>? = null,
    /** Section an item is listed under; null lists everything in one section. */
    val group: Grouping<T>? = null,
) {
    /** This spec with another way of sectioning — one collection, listed by what matters on that page. */
    fun withGroup(group: Grouping<T>?): BrowseSpec<T> = BrowseSpec(key, text, facets, order, group)

    /** Everything [query] selects from [items], with the facet options that could narrow it further. */
    fun run(items: List<T>, query: BrowseQuery): BrowseResult<T> {
        val tokens = query.tokens
        val scored = if (tokens.isEmpty()) items.map { it to 0 } else items.mapNotNull { item -> score(item, tokens)?.let { item to it } }

        val facetsById = facets.associateBy { it.id }
        // An exclusive facet with nothing chosen still applies its default ("All" hides the archived).
        val active = facets.mapNotNull { facet ->
            val chosen = query.filters[facet.id].orEmpty()
            val effective = if (chosen.isEmpty()) setOfNotNull(facet.default) else chosen
            effective.takeIf { it.isNotEmpty() }?.let { facet.id to it }
        }.toMap()
        fun passes(item: T, except: String? = null): Boolean = active.all { (facetId, chosen) ->
            facetId == except || facetsById.getValue(facetId).valuesOf(item).any { it in chosen }
        }

        val matched = scored.filter { (item, _) -> passes(item) }
        val ordered = matched.sortedWith(
            compareByDescending<Pair<T, Int>> { it.second }.let { byScore ->
                order?.let { o -> byScore.thenBy(o) { it.first } } ?: byScore
            },
        ).map { it.first }

        val facetStates = facets.map { facet ->
            // Counted against every OTHER active facet, so each count answers "what if I also chose this?".
            val pool = scored.filter { (item, _) -> passes(item, except = facet.id) }.map { it.first }
            val counts = HashMap<String, Int>()
            pool.forEach { item -> facet.valuesOf(item).toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            val chosen = active[facet.id].orEmpty()
            val explicit = query.filters[facet.id].orEmpty()
            val ids = (facet.options ?: counts.keys.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { facet.label(it).lowercase() })) +
                chosen.filter { facet.options?.contains(it) != true && it !in counts }
            FacetState(
                id = facet.id,
                label = facet.label,
                options = ids.distinct()
                    .map { FacetOption(it, facet.label(it), counts[it] ?: 0, it in chosen, isDefault = it in chosen && it !in explicit) }
                    .filter { it.count > 0 || it.selected || !facet.hideEmpty },
                exclusive = facet.exclusive,
            )
        }.filter { state -> state.options.size > 1 || state.options.any { it.selected && !it.isDefault } }

        val sections = group?.let { grouping ->
            if (tokens.isNotEmpty() && !grouping.whileSearching) listOf(BrowseSection(null, ordered))
            else ordered.groupBy(grouping.of).entries
                .sortedWith(compareBy<Map.Entry<String?, List<T>>> { grouping.rank(it.key) }.thenBy { it.key.orEmpty() })
                .map { (title, list) -> BrowseSection(title, list) }
        } ?: listOf(BrowseSection(null, ordered))

        return BrowseResult(ordered, sections.filter { it.items.isNotEmpty() }, facetStates, total = items.size, query = query)
    }

    /**
     * Relevance of [item] for [tokens], or null when a token matches nowhere. Every token must match some
     * field (so each word narrows); the name outranks the rest, and whole-word and prefix hits outrank a
     * hit mid-word.
     */
    private fun score(item: T, tokens: List<String>): Int? {
        val fields = text(item).map { it.lowercase() }
        var total = 0
        for (token in tokens) {
            var best = 0
            fields.forEachIndexed { index, field ->
                val hit = when {
                    field == token -> EXACT
                    field.startsWith(token) -> PREFIX
                    field.split(WORD_BREAK).any { it.startsWith(token) } -> WORD_PREFIX
                    token in field -> SUBSTRING
                    else -> 0
                }
                val weighted = if (index == 0) hit * NAME_WEIGHT else hit
                if (weighted > best) best = weighted
            }
            if (best == 0) return null
            total += best
        }
        return total
    }

    private companion object {
        const val EXACT = 100
        const val PREFIX = 60
        const val WORD_PREFIX = 40
        const val SUBSTRING = 10
        const val NAME_WEIGHT = 2
        val WORD_BREAK = Regex("[\\s._:/\\-]+")
    }
}

/**
 * One way to narrow a collection: Location, Connection, Tag, Capability… An item may hold several values
 * ([valuesOf] — a model has many tags); choosing several options of one facet widens (OR), choosing across
 * facets narrows (AND), the convention every faceted search uses.
 */
class Facet<T>(
    val id: String,
    val label: String,
    val valuesOf: (T) -> Collection<String>,
    /** The option's display name; defaults to its id. */
    private val optionLabel: (String) -> String = { it },
    /** A fixed option order (and the full option set); null lists options by how many items have them. */
    val options: List<String>? = null,
    /** Options no item has are hidden unless chosen. */
    val hideEmpty: Boolean = true,
    /**
     * One option at a time — a VIEW of the collection rather than a narrowing (chats: All, Starred, Archived).
     * Choosing an option replaces the previous one.
     */
    val exclusive: Boolean = false,
    /** What an exclusive facet applies when nothing is chosen (All — which may itself exclude, e.g. archived). */
    val default: String? = null,
) {
    init {
        require(default == null || exclusive) { "Facet '$id': only an exclusive facet has a default" }
    }

    fun label(option: String): String = optionLabel(option)
}

/** How a collection is split into titled sections. [rank] orders sections; lower first. */
class Grouping<T>(
    val of: (T) -> String?,
    val rank: (String?) -> Int = { 0 },
    /** Keep sections while searching; off by default, since relevance order reads better as one list. */
    val whileSearching: Boolean = false,
)

/** What the user asked for: text plus the chosen options per facet. Primitive, so it saves and restores. */
data class BrowseQuery(
    val text: String = "",
    val filters: Map<String, Set<String>> = emptyMap(),
) {
    val tokens: List<String> get() = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    val activeFilterCount: Int get() = filters.values.sumOf { it.size }
    val isActive: Boolean get() = tokens.isNotEmpty() || activeFilterCount > 0

    fun withText(value: String): BrowseQuery = copy(text = value)

    /** Chooses [option] as the ONE option of an exclusive facet (choosing it again returns to the default). */
    fun choose(facet: String, option: String): BrowseQuery =
        copy(filters = if (filters[facet] == setOf(option)) filters - facet else filters + (facet to setOf(option)))

    fun toggle(facet: String, option: String): BrowseQuery {
        val current = filters[facet].orEmpty()
        val next = if (option in current) current - option else current + option
        return copy(filters = if (next.isEmpty()) filters - facet else filters + (facet to next))
    }

    fun select(facet: String, option: String?): BrowseQuery =
        copy(filters = if (option == null) filters - facet else filters + (facet to setOf(option)))

    fun clearFilters(): BrowseQuery = copy(filters = emptyMap())
}

data class FacetOption(
    val id: String,
    val label: String,
    val count: Int,
    val selected: Boolean,
    /** Selected only because it is an exclusive facet's default — not something the user chose. */
    val isDefault: Boolean = false,
)

data class FacetState(
    val id: String,
    val label: String,
    val options: List<FacetOption>,
    val exclusive: Boolean = false,
) {
    /** The options the user chose (a default applying on its own is not a choice to show or clear). */
    val selected: List<FacetOption> get() = options.filter { it.selected && !it.isDefault }
}

data class BrowseSection<T>(val title: String?, val items: List<T>)

data class BrowseResult<T>(
    /** Every match, in display order. */
    val items: List<T>,
    /** [items] split by the spec's grouping (one untitled section when it has none). */
    val sections: List<BrowseSection<T>>,
    /** The facets worth offering: each has more than one option, or one the user chose. */
    val facets: List<FacetState>,
    /** How many items there were before the query. */
    val total: Int,
    val query: BrowseQuery,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** Every chosen option across facets, for the removable pills above a list. */
    val chosen: List<Pair<FacetState, FacetOption>>
        get() = facets.flatMap { f -> f.selected.map { f to it } }
}
