package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject

/**
 * Name translation for provider-defined tools.
 *
 * The failure without it is symmetrical and equally confusing in both directions: a call arrives naming
 * a tool the client has never heard of, or a result goes out naming one the vendor does not recognise.
 */
class ToolNameMappingTest {

    private val searchTool = Tool.ProviderDefined(
        name = "search_the_web",
        id = "anthropic.web_search_20250305",
        args = buildJsonObject { },
    )

    private val providerNames = mapOf("anthropic.web_search_20250305" to "web_search")

    @Test
    fun `a provider tool translates in both directions`() {
        val mapping = ToolNameMapping.from(listOf(searchTool), providerNames)

        assertEquals("web_search", mapping.toProviderToolName("search_the_web"))
        assertEquals("search_the_web", mapping.toCustomToolName("web_search"))
    }

    @Test
    fun `an unmapped name passes through unchanged`() {
        val mapping = ToolNameMapping.from(listOf(searchTool), providerNames)

        // Ordinary function tools are named the same on both sides and need no entry.
        assertEquals("my_function", mapping.toProviderToolName("my_function"))
        assertEquals("my_function", mapping.toCustomToolName("my_function"))
    }

    @Test
    fun `function tools are never mapped even if an id collides`() {
        val functionTool = Tool.Function(name = "search_the_web", inputSchema = buildJsonObject { })

        val mapping = ToolNameMapping.from(listOf(functionTool), providerNames)

        assertEquals("search_the_web", mapping.toProviderToolName("search_the_web"))
    }

    @Test
    fun `a provider tool with an unknown id is left alone`() {
        val unknown = Tool.ProviderDefined(name = "n", id = "vendor.never_heard_of_it", args = buildJsonObject { })

        val mapping = ToolNameMapping.from(listOf(unknown), providerNames)

        assertEquals("n", mapping.toProviderToolName("n"))
    }

    @Test
    fun `no tools and no names both give the identity mapping`() {
        assertEquals(ToolNameMapping.Identity, ToolNameMapping.from(null, providerNames))
        assertEquals(ToolNameMapping.Identity, ToolNameMapping.from(listOf(searchTool), emptyMap()))
        assertEquals("anything", ToolNameMapping.Identity.toProviderToolName("anything"))
    }
}
