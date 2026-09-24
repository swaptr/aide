package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.ProviderToolFactory
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The `google.tools.*` factory surface, and the invariant it exists for: a tool built from a factory
 * reaches the wire as EXACTLY the bytes a hand-declared `Tool.ProviderDefined` did before the factories
 * existed. The id → wire table is derived from the factories, so the two cannot disagree.
 */
class GoogleToolsTest {

    private val finished = TestServer.sse("""data: {"candidates":[{"finishReason":"STOP"}]}""" + "\n\n")

    private fun call() = CallOptions(
        prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))),
        reasoning = ReasoningEffort.ProviderDefault,
    )

    private suspend fun send(tools: List<Tool>, modelId: String = "gemini-3-pro"): Pair<JsonObject, List<Warning>> {
        val server = TestServer(finished)
        val parts = GoogleLanguageModel(modelId = modelId, http = server.http())
            .doStream(call().copy(tools = tools)).stream.toList()
        return server.request().bodyJson() to parts.filterIsInstance<StreamPart.StreamStart>().flatMap { it.warnings }
    }

    private suspend fun toolsArray(tool: Tool): List<JsonObject> =
        send(listOf(tool)).first.arr("tools")!!.map { it.jsonObject }

    @Test
    fun `every factory has exactly one row in the wire table, and the table nothing else`() {
        assertEquals(GoogleTools.all.map { it.id }.toSet(), googleBuiltInTools.keys)
        assertEquals(GoogleTools.all.size, GoogleTools.all.map { it.id }.toSet().size, "ids are unique")
        for (factory in GoogleTools.all) {
            assertTrue(factory.id.startsWith("$GOOGLE_PROVIDER_ID."), factory.id)
            assertEquals(factory.id.substringAfter('.'), factory.wireName)
        }
    }

    @Test
    fun `a factory-built tool sends the identical body as a hand-built declaration`() = runTest {
        val handBuilt = send(listOf(Tool.ProviderDefined("search", "google.google_search", buildJsonObject { }))).first
        val fromFactory = send(listOf(GoogleTools.googleSearch())).first

        // The caller's name never reaches Google — built-ins are keyed, not named — so the bodies match.
        assertEquals(handBuilt, fromFactory)
        assertEquals(listOf("googleSearch"), fromFactory.arr("tools")!!.map { it.jsonObject.keys.single() })
    }

    @Test
    fun `each built-in lands under its own wire key`() = runTest {
        val expected = mapOf(
            GoogleTools.googleSearch to "googleSearch",
            GoogleTools.enterpriseWebSearch to "enterpriseWebSearch",
            GoogleTools.googleMaps to "googleMaps",
            GoogleTools.urlContext to "urlContext",
            GoogleTools.fileSearch to "fileSearch",
            GoogleTools.codeExecution to "codeExecution",
            GoogleTools.vertexRagStore to "retrieval",
        )
        assertEquals(GoogleTools.all.toSet(), expected.keys, "every factory is covered")
        for ((factory, key) in expected) {
            val entry = toolsArray(factory()).single()
            assertEquals(setOf(key), entry.keys, factory.id)
        }
    }

    @Test
    fun `declaration-time args ride through whole where Google takes a blob`() = runTest {
        val search = toolsArray(
            GoogleTools.googleSearch(
                parseJsonObject("""{"timeRangeFilter":{"startTime":"2026-01-01T00:00:00Z","endTime":"2026-02-01T00:00:00Z"}}"""),
            ),
        ).single()
        assertEquals("2026-01-01T00:00:00Z", search.obj("googleSearch", "timeRangeFilter")!!["startTime"].string())

        val fileSearch = toolsArray(
            GoogleTools.fileSearch(
                buildJsonObject {
                    putJsonArray("fileSearchStoreNames") { add(kotlinx.serialization.json.JsonPrimitive("fileSearchStores/my-store")) }
                    put("topK", 3)
                },
            ),
        ).single()
        assertEquals(
            parseJsonObject("""{"fileSearchStoreNames":["fileSearchStores/my-store"],"topK":3}"""),
            fileSearch.obj("fileSearch"),
        )

        // The tools that take no configuration send an empty object whatever the caller passed.
        val maps = toolsArray(GoogleTools.googleMaps(buildJsonObject { put("ignored", true) })).single()
        assertEquals(JsonObject(emptyMap()), maps.obj("googleMaps"))
    }

    @Test
    fun `the RAG store is reshaped into Google's nested snake_case`() = runTest {
        val entry = toolsArray(
            GoogleTools.vertexRagStore(
                buildJsonObject {
                    put("ragCorpus", "projects/p/locations/l/ragCorpora/c")
                    put("topK", 5)
                },
            ),
        ).single()

        assertEquals(
            parseJsonObject(
                """{"vertex_rag_store":{"rag_resources":{"rag_corpus":"projects/p/locations/l/ragCorpora/c"},""" +
                    """"similarity_top_k":5}}""",
            ),
            entry.obj("retrieval"),
        )
    }

    @Test
    fun `every Gemini tool is provider-executed and none defers its result`() {
        for (factory in GoogleTools.all) {
            val tool = factory()
            assertTrue(tool.providerExecuted, factory.id)
            assertFalse(tool.supportsDeferredResults, factory.id)
            assertEquals(factory.wireName, tool.name)
            assertEquals(factory.id, tool.id)
        }
        // Code execution is the one whose call the model writes out, so it alone has a real input.
        val codeInput = GoogleTools.codeExecution.inputSchema.obj("properties")!!
        assertEquals(setOf("language", "code"), codeInput.keys)
        assertEquals(setOf("outcome", "output"), GoogleTools.codeExecution.outputSchema!!.obj("properties")!!.keys)
        for (factory in GoogleTools.all - GoogleTools.codeExecution) {
            assertEquals(JsonObject(emptyMap()), factory.inputSchema.obj("properties"), "${factory.id} takes no input")
        }
    }

    @Test
    fun `a tool id this provider does not model is refused with a warning, not sent`() = runTest {
        val (body, warnings) = send(listOf(Tool.ProviderDefined("ws", "openai.web_search", buildJsonObject { })))

        assertNull(body["tools"])
        warnings.assertUnsupported("provider-defined tool openai.web_search", "This provider does not model that Gemini tool.")
    }

    @Test
    fun `a factory-built tool is still gated by what the model can serve`() = runTest {
        val (body, warnings) = send(listOf(GoogleTools.fileSearch()), modelId = "gemini-2.0-flash")

        assertNull(body["tools"])
        warnings.assertUnsupported(
            "provider-defined tool google.file_search",
            "The file search tool is only supported with Gemini 2.5 and Gemini 3 models.",
        )
    }

    @Test
    fun `a renamed factory tool changes nothing on the wire`() = runTest {
        val renamed: ProviderToolFactory = GoogleTools.codeExecution
        val entry = toolsArray(renamed(name = "run_python")).single()

        assertEquals(setOf("codeExecution"), entry.keys)
    }
}
