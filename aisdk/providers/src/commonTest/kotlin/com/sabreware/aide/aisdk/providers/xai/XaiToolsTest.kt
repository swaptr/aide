package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * xAI's eight agent tools, pinned against `xai/src/tool/` and `xai-responses-prepare-tools.ts`.
 *
 * The properties that matter are the ones a wrong value makes silent: an id the vendor does not
 * recognise, a wire name that does not match what a result comes back under, and the executed flag —
 * which decides whether the runtime dispatches a call xAI is already running.
 */
class XaiToolsTest {

    @Test
    fun `every agent tool is provider-executed and answers within its turn`() {
        // All eight are createProviderExecutedToolFactory on the reference side. Declaring one
        // client-executed would make the loop try to run "search X" itself and then report the call
        // unanswered; declaring one deferred would let a genuinely missing result pass as pending.
        XaiTools.all.forEach { factory ->
            val tool = factory()
            assertTrue(tool.providerExecuted, "${factory.id} must be provider-executed")
            assertFalse(tool.supportsDeferredResults, "${factory.id} answers within the turn")
        }
        assertEquals(8, XaiTools.all.size)
    }

    @Test
    fun `each id and wire name matches the vendor`() {
        assertEquals(
            mapOf(
                "xai.web_search" to "web_search",
                "xai.x_search" to "x_search",
                // The one whose two names differ: the spec calls it code_execution, xAI's wire type is
                // code_interpreter, and only the id keeps the two ends joined.
                "xai.code_execution" to "code_interpreter",
                "xai.view_image" to "view_image",
                "xai.view_x_video" to "view_x_video",
                "xai.image_generation" to "image_generation",
                "xai.file_search" to "file_search",
                "xai.mcp" to "mcp",
            ),
            xaiProviderToolNames,
        )
    }

    @Test
    fun `a tool carries its id so a renamed one still resolves`() {
        val renamed = XaiTools.webSearch(name = "search_the_web")

        assertEquals("search_the_web", renamed.name)
        assertEquals("xai.web_search", (renamed as Tool.ProviderDefined).id)
    }

    @Test
    fun `web search arguments reach the wire in xAI's spelling`() {
        val tool = XaiTools.webSearch(
            buildJsonObject {
                put("allowedDomains", buildJsonArray { add("example.com") })
                put("enableImageSearch", true)
            },
        )

        val body = xaiToolBody(tool.id, tool.name, tool.args)

        assertEquals("web_search", body["type"].toString().trim('"'))
        assertTrue(body.containsKey("allowed_domains"))
        assertTrue(body.containsKey("enable_image_search"))
        // The camelCase originals must not also travel: xAI ignores unknown keys, so a request with
        // both reads as configured while only half of it applies.
        assertFalse(body.containsKey("allowedDomains"))
        assertFalse(body.containsKey("enableImageSearch"))
    }

    @Test
    fun `an MCP tool's headers are never walked into`() {
        val tool = XaiTools.mcpServer(
            buildJsonObject {
                put("serverUrl", "https://mcp.example.com")
                // Arbitrary HTTP header NAMES used as keys. A recursive camel-to-snake pass would
                // rewrite this to `x_custom_header` and the server would never see it.
                put("headers", buildJsonObject { put("xCustomHeader", "value") })
            },
        )

        val body = xaiToolBody(tool.id, tool.name, tool.args)

        assertEquals("mcp", body["type"].toString().trim('"'))
        assertTrue(body.containsKey("server_url"))
        assertEquals("""{"xCustomHeader":"value"}""", body["headers"].toString())
    }

    @Test
    fun `an option xAI ships later still reaches the wire`() {
        val tool = XaiTools.xSearch(buildJsonObject { put("someFutureKnob", 3) })

        // Undocumented keys are copied verbatim rather than dropped, so a new vendor option does not
        // need a release here to be usable.
        assertTrue(xaiToolBody(tool.id, tool.name, tool.args).containsKey("someFutureKnob"))
    }
}
