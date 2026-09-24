package com.sabreware.aide.data.connector

import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.core.domain.connector.ConnectorCategory
import com.sabreware.aide.core.domain.connector.ConnectorSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectorMergeTest {

    private fun con(
        id: String,
        name: String,
        domain: String?,
        rank: Int?,
        source: ConnectorSource,
        url: String = "https://$name",
        website: String? = null,
        repo: String? = null,
    ) = Connector(
        id = id, name = name, description = "d", category = ConnectorCategory.OTHER,
        serverUrl = url, authType = ConnectorAuthType.OAUTH, brandDomain = domain, iconSlug = null,
        popularityRank = rank, source = source, websiteUrl = website, repositoryUrl = repo,
    )

    @Test
    fun curatedWinsUrlAndRank_registryFillsBlanks() {
        val curated = con("c:notion", "Notion", "notion.so", 1, ConnectorSource.CURATED, url = "https://mcp.notion.com/mcp")
        val registry = con("r:notion", "Notion", "notion.so", null, ConnectorSource.REGISTRY, url = "https://other", website = "https://notion.so", repo = "https://github.com/x")
        val m = ConnectorMerge.merge(listOf(curated), listOf(registry)).single()
        assertEquals("https://mcp.notion.com/mcp", m.serverUrl) // curated wins
        assertEquals(1, m.popularityRank)                       // curated wins
        assertEquals("https://notion.so", m.websiteUrl)         // registry fills blank
        assertEquals("https://github.com/x", m.repositoryUrl)   // registry fills blank
        assertEquals(ConnectorSource.MERGED, m.source)
    }

    @Test
    fun registryOnlyPassesThrough() {
        val merged = ConnectorMerge.merge(emptyList(), listOf(con("r:foo", "Foo", "foo.com", null, ConnectorSource.REGISTRY)))
        assertEquals(listOf("Foo"), merged.map { it.name })
        assertEquals(ConnectorSource.REGISTRY, merged.single().source)
    }

    @Test
    fun sortsRankedBeforeAlphabeticalUnranked() {
        val merged = ConnectorMerge.merge(
            listOf(
                con("c:a", "Apple", "apple.com", 5, ConnectorSource.CURATED),
                con("c:z", "Zeta", "zeta.com", 1, ConnectorSource.CURATED),
            ),
            listOf(
                con("r:m", "Mango", "mango.com", null, ConnectorSource.REGISTRY),
                con("r:b", "Banana", "banana.com", null, ConnectorSource.REGISTRY),
            ),
        )
        assertEquals(listOf("Zeta", "Apple", "Banana", "Mango"), merged.map { it.name })
    }

    @Test
    fun dedupsDuplicateRegistryIds_soLazyColumnKeysStayUnique() {
        // Registry repeats a `name` with differing websiteUrl → same id, different brandDomain.
        val merged = ConnectorMerge.merge(
            emptyList(),
            listOf(
                con("io.x/dup", "Dup A", "a.com", null, ConnectorSource.REGISTRY),
                con("io.x/dup", "Dup B", "b.com", null, ConnectorSource.REGISTRY),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(merged.map { it.id }.distinct().size, merged.size)
    }

    @Test
    fun dedupByDomainEvenWhenNamesDiffer() {
        val merged = ConnectorMerge.merge(
            listOf(con("c:gh", "GitHub", "github.com", 1, ConnectorSource.CURATED)),
            listOf(con("r:gh", "GitHub MCP", "github.com", null, ConnectorSource.REGISTRY)),
        )
        assertEquals(1, merged.size)
        assertEquals("GitHub", merged.single().name)
    }

    @Test
    fun mergeAll_foldsInPriorityOrder_firstWinsLaterFill() {
        // Same brand across three sources: the first (highest-priority) wins URL/rank; later sources only fill blanks.
        val a = con("a:gh", "GitHub", "github.com", 1, ConnectorSource.CURATED, url = "https://curated")
        val b = con("b:gh", "GitHub", "github.com", 9, ConnectorSource.REGISTRY, url = "https://b", website = "https://github.com")
        val c = con("c:gh", "GitHub", "github.com", 9, ConnectorSource.REGISTRY, url = "https://c", website = "https://other", repo = "https://repo")
        val m = ConnectorMerge.mergeAll(listOf(listOf(a), listOf(b), listOf(c))).single()
        assertEquals("https://curated", m.serverUrl)        // first source wins
        assertEquals(1, m.popularityRank)                   // first source wins
        assertEquals("https://github.com", m.websiteUrl)    // filled by b (first non-null)
        assertEquals("https://repo", m.repositoryUrl)       // filled by c
        assertEquals(ConnectorSource.MERGED, m.source)
    }

    @Test
    fun mergeAll_emptyInputs_returnEmpty() {
        assertEquals(emptyList<String>(), ConnectorMerge.mergeAll(emptyList()).map { it.name })
        assertEquals(emptyList<String>(), ConnectorMerge.mergeAll(listOf(emptyList(), emptyList())).map { it.name })
    }

    @Test
    fun mergeAll_sourceUniqueEntriesPassThroughWithTheirSource() {
        val m = ConnectorMerge.mergeAll(
            listOf(
                listOf(con("c:a", "Alpha", "alpha.com", 1, ConnectorSource.CURATED)),
                listOf(con("r:b", "Beta", "beta.com", null, ConnectorSource.REGISTRY)),
            ),
        )
        assertEquals(listOf("Alpha", "Beta"), m.map { it.name })
        assertEquals(ConnectorSource.CURATED, m.first { it.name == "Alpha" }.source)
        assertEquals(ConnectorSource.REGISTRY, m.first { it.name == "Beta" }.source)
    }
}
