package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.parseJsonElement
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `prepareGoogleInteractionsTools`, case for case from the reference's own test table. */
class GoogleInteractionsToolsTest {

    private fun providerTool(id: String, args: String = "{}") = Tool.ProviderDefined(
        name = id.substringAfter('.'),
        id = id,
        args = parseJsonObject(args),
    )

    @Test
    fun `no tools, or an empty list, is no tools and no choice`() {
        val none = prepareGoogleInteractionsTools(null, ToolChoice.Auto)
        assertNull(none.tools)
        assertNull(none.toolChoice)
        assertTrue(none.warnings.isEmpty())
        val empty = prepareGoogleInteractionsTools(emptyList(), ToolChoice.Auto)
        assertNull(empty.tools)
        assertNull(empty.toolChoice)
    }

    @Test
    fun `a function tool passes its JSON Schema through and an absent description is an empty string`() {
        val prepared = prepareGoogleInteractionsTools(listOf(WEATHER_TOOL), null)
        assertEquals(
            parseJsonElement(
                """[{"type":"function","name":"getWeather","description":"Get the current weather in a location",""" +
                    """"parameters":{"type":"object","properties":{"location":{"type":"string",""" +
                    """"description":"The location to get the weather for"}},"required":["location"]}}]""",
            ),
            JsonArray(prepared.tools!!),
        )
        val bare = prepareGoogleInteractionsTools(
            listOf(Tool.Function("noop", inputSchema = parseJsonObject("""{"type":"object"}"""))),
            null,
        )
        assertEquals(JsonPrimitive(""), bare.tools!!.single()["description"])
    }

    @Test
    fun `every google built-in maps to its typed entry with the arguments re-spelled`() {
        val cases = listOf(
            Triple("google.google_search", "{}", """{"type":"google_search"}"""),
            Triple(
                "google.google_search",
                """{"searchTypes":{"webSearch":{},"imageSearch":{}}}""",
                """{"type":"google_search","search_types":["web_search","image_search"]}""",
            ),
            Triple("google.code_execution", "{}", """{"type":"code_execution"}"""),
            Triple("google.url_context", "{}", """{"type":"url_context"}"""),
            Triple(
                "google.file_search",
                """{"fileSearchStoreNames":["fileSearchStores/foo"],"topK":4,"metadataFilter":"a = \"b\""}""",
                """{"type":"file_search","file_search_store_names":["fileSearchStores/foo"],"top_k":4,"metadata_filter":"a = \"b\""}""",
            ),
            Triple(
                "google.google_maps",
                """{"latitude":37.7749,"longitude":-122.4194,"enableWidget":true}""",
                """{"type":"google_maps","latitude":37.7749,"longitude":-122.4194,"enable_widget":true}""",
            ),
            Triple("google.computer_use", "{}", """{"type":"computer_use","environment":"browser"}"""),
            Triple(
                "google.mcp_server",
                """{"name":"my-mcp","url":"https://example.com/mcp","headers":{"Authorization":"Bearer x"},"allowedTools":["foo"]}""",
                """{"type":"mcp_server","name":"my-mcp","url":"https://example.com/mcp","headers":{"Authorization":"Bearer x"},"allowed_tools":["foo"]}""",
            ),
            Triple(
                "google.retrieval",
                """{"retrievalTypes":["vertex_ai_search"],"vertexAiSearchConfig":{"datastores":["projects/p/locations/l/dataStores/d"]}}""",
                """{"type":"retrieval","retrieval_types":["vertex_ai_search"],"vertex_ai_search_config":{"datastores":["projects/p/locations/l/dataStores/d"]}}""",
            ),
            Triple("google.retrieval", "{}", """{"type":"retrieval","retrieval_types":["vertex_ai_search"]}"""),
        )
        cases.forEach { (id, args, expected) ->
            val prepared = prepareGoogleInteractionsTools(listOf(providerTool(id, args)), null)
            assertTrue(prepared.warnings.isEmpty(), id)
            assertEquals(parseJsonElement(expected), prepared.tools!!.single(), id)
        }
    }

    @Test
    fun `an unknown google tool is warned about and dropped`() {
        val prepared = prepareGoogleInteractionsTools(listOf(providerTool("google.unknown_tool")), null)
        assertNull(prepared.tools)
        assertEquals(
            listOf(
                Warning.Unsupported(
                    feature = "provider-defined tool google.unknown_tool",
                    details = "provider-defined tool google.unknown_tool is not supported by google.interactions; tool dropped.",
                ),
            ),
            prepared.warnings,
        )
    }

    @Test
    fun `tool choice maps to the wire vocabulary, with a named tool as a validated allow-list`() {
        fun choice(toolChoice: ToolChoice) = prepareGoogleInteractionsTools(listOf(WEATHER_TOOL), toolChoice).toolChoice
        assertEquals(JsonPrimitive("auto"), choice(ToolChoice.Auto))
        assertEquals(JsonPrimitive("any"), choice(ToolChoice.Required))
        assertEquals(JsonPrimitive("none"), choice(ToolChoice.None))
        assertEquals(
            parseJsonElement("""{"allowed_tools":{"mode":"validated","tools":["getWeather"]}}"""),
            choice(ToolChoice.Specific("getWeather")),
        )
    }

    @Test
    fun `a tool choice with no function tool on offer is dropped, because the API rejects it`() {
        val prepared = prepareGoogleInteractionsTools(listOf(GOOGLE_SEARCH_TOOL), ToolChoice.Required)
        assertEquals(listOf<JsonObject>(parseJsonObject("""{"type":"google_search"}""")), prepared.tools)
        assertNull(prepared.toolChoice)
    }
}
