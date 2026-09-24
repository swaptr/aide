package com.sabreware.aide.aisdk.providers.azure

import com.sabreware.aide.aisdk.util.wireToolNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Azure's tool surface, against `azure-openai-tools.ts`.
 *
 * The load-bearing assertion is the id prefix. Azure hosts OpenAI's Responses API, so its tools ARE
 * OpenAI's — the reference re-exports the objects unchanged — and our Responses model matches an
 * incoming tool back to its definition by id. An `azure.`-prefixed id would compile, ship, and then
 * match nothing the model reports back.
 */
class AzureToolsTest {

    @Test
    fun `the five tools are the reference's five, under OpenAI's ids`() {
        assertEquals(
            listOf(
                "openai.web_search",
                "openai.web_search_preview",
                "openai.file_search",
                "openai.code_interpreter",
                "openai.image_generation",
            ),
            AzureTools.all.map { it.id },
        )
    }

    @Test
    fun `every one is provider-executed, because the deployment runs them`() {
        // A client-executed tool here would mean the runtime dispatching something it has no
        // implementation for — the call would hang unanswered.
        assertTrue(AzureTools.all.all { it.providerExecuted })
        assertTrue(AzureTools.all.none { it.supportsDeferredResults })
    }

    @Test
    fun `the wire names drop the namespace, which is what tool-name mapping needs`() {
        assertEquals(
            mapOf(
                "openai.web_search" to "web_search",
                "openai.web_search_preview" to "web_search_preview",
                "openai.file_search" to "file_search",
                "openai.code_interpreter" to "code_interpreter",
                "openai.image_generation" to "image_generation",
            ),
            AzureTools.all.wireToolNames(),
        )
    }

    @Test
    fun `code interpreter is the one tool whose input the model actually fills in`() {
        // Everything else is activated by being offered; this one carries the code and the container.
        val properties = AzureTools.codeInterpreter.inputSchema["properties"]
        assertTrue(properties != null && "code" in properties.toString())
        assertTrue("containerId" in properties.toString())
        assertTrue(AzureTools.webSearch.inputSchema.isEmpty())
    }

    @Test
    fun `an instantiated tool keeps the vendor name and the caller's args`() {
        val tool = AzureTools.fileSearch(args = buildJsonObject { put("vector_store_ids", "vs_1") })

        assertEquals("file_search", tool.name)
        assertEquals("openai.file_search", tool.id)
        assertTrue(tool.providerExecuted)
    }
}
