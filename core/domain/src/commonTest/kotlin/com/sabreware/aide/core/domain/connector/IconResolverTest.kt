package com.sabreware.aide.core.domain.connector

import kotlin.test.Test
import kotlin.test.assertEquals

class IconResolverTest {

    private fun con(name: String, domain: String?, slug: String? = null) = Connector(
        id = "id:$name", name = name, description = "d", category = ConnectorCategory.OTHER,
        serverUrl = "https://x", authType = ConnectorAuthType.OAUTH, brandDomain = domain, iconSlug = slug,
    )

    @Test
    fun candidates_orderedAppleTouchThenFaviconThenSimpleIcons() {
        assertEquals(
            listOf(
                "https://notion.so/apple-touch-icon.png",
                "https://notion.so/favicon.ico",
                "https://cdn.simpleicons.org/notion",
            ),
            IconResolver.candidates(con("Notion", "notion.so", "notion")),
        )
    }

    @Test
    fun candidates_noDomain_onlySimpleIconsBySlug() {
        assertEquals(listOf("https://cdn.simpleicons.org/linear"), IconResolver.candidates(con("Linear", null)))
    }

    @Test
    fun slug_prefersExplicit_thenOverride_thenDerived() {
        assertEquals("notion", IconResolver.slug(con("Whatever", null, "notion")))
        assertEquals("huggingface", IconResolver.slug(con("Hugging Face", null))) // override map
        assertEquals("foobar", IconResolver.slug(con("Foo Bar", null)))           // derived alnum
    }
}
