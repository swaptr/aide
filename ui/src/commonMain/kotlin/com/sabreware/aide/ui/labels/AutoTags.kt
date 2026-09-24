package com.sabreware.aide.ui.labels

import com.sabreware.aide.core.domain.browse.BrowseSpec

/**
 * A tag the app derives from what a thing IS — one option of a collection's facet (Cloud, Vision, 128K+) —
 * with how many items carry it. The user cannot rename or delete it; it changes when the thing does.
 */
data class AutoTag(val facet: String, val option: String, val label: String, val count: Int)

/** Automatic tags of one kind, titled by their facet ("Where it runs", "Capabilities"). */
data class AutoTagGroup(val title: String, val tags: List<AutoTag>)

/**
 * The automatic tags of [items]: each facet of this spec not in [except], with every option some item carries,
 * in the facet's own option order. Derived from the spec rather than listed beside it, so the tags a page
 * shows and the filters its collection offers are one definition with the same names and counts.
 */
fun <T> BrowseSpec<T>.automaticTags(items: List<T>, except: Set<String>): List<AutoTagGroup> =
    facets.filter { it.id !in except && !it.exclusive }.mapNotNull { facet ->
        val counts = HashMap<String, Int>()
        items.forEach { item -> facet.valuesOf(item).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        (facet.options ?: counts.keys.sortedBy { facet.label(it).lowercase() })
            .mapNotNull { option -> counts[option]?.let { AutoTag(facet.id, option, facet.label(option), it) } }
            .takeIf { it.isNotEmpty() }
            ?.let { AutoTagGroup(facet.label, it) }
    }
