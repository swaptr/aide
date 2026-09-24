package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Declaring a tool the vendor owns.
 *
 * The pairing this exists to keep honest is id and wire name. A provider matches an incoming call back
 * to its definition by id, never by the name — the caller is free to rename the tool for the model, and
 * a match on the name loses the call the moment it does.
 */
class ProviderToolsTest {

    private val webSearch = providerExecutedTool(
        id = "anthropic.web_search_20250305",
        wireName = "web_search",
        inputSchema = buildJsonObject { put("type", "object") },
        outputSchema = buildJsonObject { put("type", "array") },
    )

    private val textEditor = providerDefinedTool(
        id = "anthropic.text_editor_20250728",
        wireName = "str_replace_based_edit_tool",
        inputSchema = buildJsonObject { put("type", "object") },
    )

    @Test
    fun `a tool is named by the vendor unless the caller renames it`() {
        val default = webSearch()
        val renamed = webSearch(name = "search_the_web")

        assertEquals("web_search", default.name)
        assertEquals("search_the_web", renamed.name)
        // The id is what joins both back to the definition, so it does not move with the name.
        assertEquals("anthropic.web_search_20250305", renamed.id)
    }

    @Test
    fun `the vendor's configuration blob passes through untouched`() {
        // Passing it as data is what lets a new vendor option ship with no change here.
        val args = buildJsonObject { put("max_uses", 5) }

        assertEquals(args, webSearch(args).args)
    }

    @Test
    fun `who executes the tool is recorded, since the runtime cannot dispatch what it does not hold`() {
        assertTrue(webSearch.providerExecuted)
        assertFalse(textEditor.providerExecuted)
    }

    @Test
    fun `a deferred result is declared, not inferred`() {
        val programmatic = providerExecutedTool(
            id = "openai.code_interpreter",
            wireName = "code_interpreter",
            inputSchema = buildJsonObject { put("type", "object") },
            outputSchema = buildJsonObject { put("type", "object") },
            supportsDeferredResults = true,
        )

        // A runtime requiring every call to be answered within its turn would otherwise report a missing
        // result for a call that is merely still running.
        assertTrue(programmatic.supportsDeferredResults)
        assertFalse(webSearch.supportsDeferredResults)
    }

    @Test
    fun `an id that names no vendor is refused`() {
        // Two providers with a `web_search` tool would be indistinguishable to a runtime matching on id,
        // which is the one thing the id is for.
        assertFailsWith<IllegalArgumentException> {
            providerDefinedTool("web_search", "web_search", buildJsonObject { })
        }
        assertFailsWith<IllegalArgumentException> {
            providerDefinedTool(".web_search", "web_search", buildJsonObject { })
        }
    }

    @Test
    fun `factories compose with the name mapping on the id`() {
        val names = listOf(webSearch, textEditor).wireToolNames()
        val tools: List<Tool> = listOf(webSearch(name = "search_the_web"), textEditor())

        val mapping = ToolNameMapping.from(tools, names)

        // Out: what the caller called it becomes what the vendor calls it.
        assertEquals("web_search", mapping.toProviderToolName("search_the_web"))
        // Back: the vendor's fixed name becomes the caller's, or a call arrives naming a tool the client
        // has never heard of.
        assertEquals("search_the_web", mapping.toCustomToolName("web_search"))
        assertEquals("str_replace_based_edit_tool", mapping.toProviderToolName("str_replace_based_edit_tool"))
    }

    @Test
    fun `the behavioural flags ride onto the instantiated tool`() {
        val executed = providerExecutedTool(
            id = "vendor.search",
            wireName = "search",
            inputSchema = JsonObject(emptyMap()),
            outputSchema = JsonObject(emptyMap()),
            supportsDeferredResults = true,
        )(JsonObject(emptyMap()))

        // The runtime decides who dispatches a call and whether an unanswered one is an error off
        // these two; a factory that captured them and dropped them made both undecidable.
        assertTrue(executed.providerExecuted)
        assertTrue(executed.supportsDeferredResults)

        val defined = providerDefinedTool(
            id = "vendor.editor",
            wireName = "editor",
            inputSchema = JsonObject(emptyMap()),
        )()
        assertFalse(defined.providerExecuted)
        assertFalse(defined.supportsDeferredResults)
    }
}
