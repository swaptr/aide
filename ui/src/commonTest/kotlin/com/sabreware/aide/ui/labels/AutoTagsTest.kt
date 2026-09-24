package com.sabreware.aide.ui.labels

import com.sabreware.aide.core.domain.browse.BrowseSpec
import com.sabreware.aide.core.domain.browse.Facet
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoTagsTest {
    private data class Thing(val id: String, val kind: String, val caps: List<String>)

    private val spec = BrowseSpec<Thing>(
        key = { it.id },
        text = { listOf(it.id) },
        facets = listOf(
            Facet(id = "kind", label = "Kind", valuesOf = { listOf(it.kind) }, optionLabel = { it.uppercase() }, options = listOf("b", "a", "z")),
            Facet(id = "can", label = "Can", valuesOf = { it.caps }),
            Facet(id = "tag", label = "Tag", valuesOf = { listOf("mine") }),
        ),
    )

    private val things = listOf(
        Thing("1", "a", listOf("vision", "tools")),
        Thing("2", "a", listOf("tools")),
        Thing("3", "b", emptyList()),
    )

    @Test
    fun `groups follow the facets, options keep facet order and drop what nothing carries`() {
        val groups = spec.automaticTags(things, except = setOf("tag"))
        assertEquals(listOf("Kind", "Can"), groups.map { it.title })
        assertEquals(listOf("B" to 1, "A" to 2), groups[0].tags.map { it.label to it.count })
        assertEquals(listOf("tools" to 2, "vision" to 1), groups[1].tags.map { it.option to it.count })
    }

    @Test
    fun `a facet nothing carries is not a group`() {
        val groups = spec.automaticTags(listOf(Thing("3", "b", emptyList())), except = setOf("tag"))
        assertEquals(listOf("Kind"), groups.map { it.title })
    }
}
