package com.sabreware.aide.core.domain.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowseSpecTest {

    private data class Item(
        val id: String,
        val name: String,
        val blurb: String = "",
        val where: String = "cloud",
        val tags: List<String> = emptyList(),
        val archived: Boolean = false,
    )

    private val location = Facet<Item>(id = "where", label = "Location", valuesOf = { listOf(it.where) })
    private val tag = Facet<Item>(id = "tag", label = "Tag", valuesOf = { it.tags })
    private val view = Facet<Item>(
        id = "view",
        label = "View",
        valuesOf = { if (it.archived) listOf("archived") else listOf("all") },
        options = listOf("all", "archived"),
        hideEmpty = false,
        exclusive = true,
        default = "all",
    )

    private fun spec(group: Grouping<Item>? = null, facets: List<Facet<Item>> = listOf(location, tag)) = BrowseSpec(
        key = { it.id },
        text = { listOf(it.name, it.blurb) },
        facets = facets,
        order = compareBy { it.name },
        group = group,
    )

    private val gpt = Item("1", "GPT Fast", blurb = "general chat", where = "cloud", tags = listOf("work"))
    private val llama = Item("2", "Llama", blurb = "a fast local model", where = "device", tags = listOf("work", "fun"))
    private val claude = Item("3", "Claude", blurb = "thoughtful", where = "cloud", tags = listOf("fun"))
    private val qwen = Item("4", "Qwen", blurb = "coder", where = "lan")
    private val items = listOf(gpt, llama, claude, qwen)

    private fun ids(result: BrowseResult<Item>) = result.items.map { it.id }

    @Test
    fun `a name hit outranks the same hit in another field`() {
        val result = spec().run(items, BrowseQuery("fast"))

        assertEquals(listOf("1", "2"), ids(result))
    }

    @Test
    fun `every token must match somewhere`() {
        assertEquals(listOf("2"), ids(spec().run(items, BrowseQuery("fast local"))))
        assertTrue(spec().run(items, BrowseQuery("fast nowhere")).isEmpty)
    }

    @Test
    fun `no text keeps the spec order`() {
        assertEquals(listOf("3", "1", "2", "4"), ids(spec().run(items, BrowseQuery())))
    }

    @Test
    fun `options within one facet widen and facets together narrow`() {
        val either = spec().run(items, BrowseQuery(filters = mapOf("where" to setOf("device", "lan"))))
        assertEquals(setOf("2", "4"), ids(either).toSet())

        val both = spec().run(items, BrowseQuery(filters = mapOf("where" to setOf("cloud"), "tag" to setOf("fun"))))
        assertEquals(listOf("3"), ids(both))
    }

    @Test
    fun `a facet's counts ignore its own choice but honour the others`() {
        val result = spec().run(items, BrowseQuery(filters = mapOf("where" to setOf("cloud"), "tag" to setOf("work"))))
        val where = result.facets.single { it.id == "where" }.options.associate { it.id to it.count }
        val tags = result.facets.single { it.id == "tag" }.options.associate { it.id to it.count }

        // Location counted over tag=work: gpt (cloud), llama (device).
        assertEquals(mapOf("cloud" to 1, "device" to 1), where)
        // Tag counted over location=cloud: gpt (work), claude (fun).
        assertEquals(mapOf("work" to 1, "fun" to 1), tags)
        assertEquals(listOf("where" to "cloud", "tag" to "work"), result.chosen.map { (f, o) -> f.id to o.id })
    }

    @Test
    fun `an exclusive facet's default applies without reading as a choice`() {
        val all = items + Item("5", "Old", archived = true)
        val result = spec(facets = listOf(view)).run(all, BrowseQuery())

        assertFalse("5" in ids(result), "the default view hides archived items")
        val state = result.facets.single { it.id == "view" }
        assertTrue(state.exclusive)
        val default = state.options.single { it.id == "all" }
        assertTrue(default.selected && default.isDefault)
        assertTrue(state.selected.isEmpty(), "a default is not a chosen pill")
        assertTrue(result.chosen.isEmpty())

        val archived = spec(facets = listOf(view)).run(all, BrowseQuery().choose("view", "archived"))
        assertEquals(listOf("5"), ids(archived))
        assertEquals(listOf("archived"), archived.chosen.map { it.second.id })
    }

    @Test
    fun `choosing an exclusive option again returns to the default`() {
        val query = BrowseQuery().choose("view", "archived").choose("view", "archived")
        assertNull(query.filters["view"])
    }

    @Test
    fun `grouping sections by rank, and collapses to one list while searching unless asked`() {
        val grouping = Grouping<Item>(of = { it.where }, rank = { listOf("device", "lan", "cloud").indexOf(it) })
        val grouped = spec(group = grouping).run(items, BrowseQuery())
        assertEquals(listOf("device", "lan", "cloud"), grouped.sections.map { it.title })
        assertEquals(listOf("3", "1"), grouped.sections.last().items.map { it.id })

        val searching = spec(group = grouping).run(items, BrowseQuery("fast"))
        assertEquals(listOf<String?>(null), searching.sections.map { it.title })

        val kept = spec(group = Grouping(of = { it.where }, rank = grouping.rank, whileSearching = true))
            .run(items, BrowseQuery("fast"))
        assertEquals(listOf("device", "cloud"), kept.sections.map { it.title })
    }
}
