package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.parseJsonElement
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The step-to-content, annotation-to-source, usage and finish-reason mappings, each pinned on its own
 * so a fixture test that fails names the mapping rather than the whole call.
 */
class GoogleInteractionsOutputsTest {

    private val ids = IdGenerator(prefix = "gen-", random = Random(7))

    private fun steps(json: String): List<InteractionsStep> =
        ProviderJson.decodeFromJsonElement(ListSerializer(InteractionsStep.serializer()), parseJsonElement(json))

    private fun parse(json: String, interactionId: String? = null) =
        parseGoogleInteractionsOutputs(steps(json), ids, interactionId)

    // --- steps to content ------------------------------------------------------------------------

    @Test
    fun `a thought step is reasoning carrying its signature and joined summary text`() {
        val parsed = parse(
            """[{"type":"thought","signature":"thought-sig-AAA","summary":[{"type":"text","text":"I am thinking."},""" +
                """{"type":"image","data":"AQ=="},{"type":"text","text":"Still am."}]}]""",
        )
        assertEquals(
            listOf(Content.Reasoning("I am thinking.\nStill am.", googleMeta("signature" to "thought-sig-AAA"))),
            parsed.content,
        )
        assertTrue(!parsed.hasFunctionCall)
    }

    @Test
    fun `agentic video processing steps are custom parts carrying their ids and signatures`() {
        // "emits custom parts with IDs and signatures".
        val parsed = parse(
            """[{"type":"processing_call","id":"processing-1","signature":"call-signature"},""" +
                """{"type":"processing_result","call_id":"processing-1","signature":"result-signature"}]""",
            interactionId = "interaction-1",
        )

        assertEquals(
            listOf(
                Content.Custom(
                    "google.processing_call",
                    googleMeta("signature" to "call-signature", "interactionId" to "interaction-1", "processingId" to "processing-1"),
                ),
                Content.Custom(
                    "google.processing_result",
                    googleMeta("signature" to "result-signature", "interactionId" to "interaction-1", "processingCallId" to "processing-1"),
                ),
            ),
            parsed.content,
        )
    }

    @Test
    fun `every output part is stamped with the interaction id, and a function call sets the flag`() {
        val parsed = parse(
            """[{"type":"thought","signature":"thought-sig","summary":[{"type":"text","text":"planning..."}]},""" +
                """{"type":"model_output","content":[{"type":"text","text":"answer"}]},""" +
                """{"type":"function_call","id":"call_x","name":"getWeather","arguments":{"loc":"NYC"},"signature":"fn-sig"}]""",
            interactionId = "v1_test-interaction",
        )
        assertTrue(parsed.hasFunctionCall)
        assertEquals(
            listOf(
                Content.Reasoning("planning...", googleMeta("signature" to "thought-sig", "interactionId" to "v1_test-interaction")),
                Content.Text("answer", googleMeta("interactionId" to "v1_test-interaction")),
                Content.ToolCall(
                    "call_x",
                    "getWeather",
                    """{"loc":"NYC"}""",
                    providerMetadata = googleMeta("signature" to "fn-sig", "interactionId" to "v1_test-interaction"),
                ),
            ),
            parsed.content,
        )
    }

    @Test
    fun `no signature and no id means no metadata, and the server's echo of the input is skipped`() {
        val parsed = parse(
            """[{"type":"user_input","content":[{"type":"text","text":"hi"}]},""" +
                """{"type":"model_output","content":[{"type":"text","text":"hello"}]}]""",
        )
        assertEquals(listOf(Content.Text("hello")), parsed.content)
    }

    @Test
    fun `a built-in call with an empty id is given one, and its result carries the payload`() {
        val parsed = parse(
            """[{"type":"google_search_call","id":"","arguments":{"query":"weather"}},""" +
                """{"type":"google_search_result","call_id":"abc","result":[{"url":"https://a.example","title":"A"}],"is_error":false},""" +
                """{"type":"mcp_server_tool_call","id":"m1","name":"lookup","server_name":"s","arguments":{}},""" +
                """{"type":"code_execution_result","call_id":"c1","result":"1\n"}]""",
        )
        val call = assertIs<Content.ToolCall>(parsed.content[0])
        assertEquals("google_search", call.toolName)
        assertTrue(call.providerExecuted)
        assertTrue(call.toolCallId.startsWith("gen-"))
        assertEquals("""{"query":"weather"}""", call.input)

        val result = assertIs<Content.ToolResult>(parsed.content[1])
        assertEquals(Content.ToolResult("abc", "google_search", ToolOutput.Json(parseJsonElement("""[{"url":"https://a.example","title":"A"}]"""))), result)
        val source = assertIs<Content.Source.Url>(parsed.content[2])
        assertEquals("https://a.example", source.url)
        assertEquals("A", source.title)

        assertEquals("lookup", assertIs<Content.ToolCall>(parsed.content[3]).toolName)
        assertEquals(ToolOutput.Json(parseJsonElement("\"1\\n\"")), assertIs<Content.ToolResult>(parsed.content[4]).output)
        assertTrue(!parsed.hasFunctionCall)
    }

    @Test
    fun `image and video blocks are files, decoded or by URL, and skipped when they carry neither`() {
        val parsed = parse(
            """[{"type":"model_output","content":[""" +
                """{"type":"image","data":"aGVsbG8=","mime_type":"image/png"},""" +
                """{"type":"image","uri":"https://example.com/img.png","mime_type":"image/webp"},""" +
                """{"type":"image"},""" +
                """{"type":"video","data":"AAAAIGZ0eXBpc29t"},""" +
                """{"type":"video","uri":"https://example.com/clip.mp4"},""" +
                """{"type":"video","uri":""}]}]""",
            interactionId = "v1_x",
        )
        val stamp = googleMeta("interactionId" to "v1_x")
        assertEquals(
            listOf(
                Content.File("image/png", FileData.Bytes("hello".encodeToByteArray()), stamp),
                Content.File("image/webp", FileData.Url("https://example.com/img.png"), stamp),
                Content.File("video/mp4", FileData.Bytes(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d)), stamp),
                Content.File("video/mp4", FileData.Url("https://example.com/clip.mp4"), stamp),
            ),
            parsed.content,
        )
    }

    // --- sources ---------------------------------------------------------------------------------

    private fun annotation(json: String): InteractionsAnnotation =
        ProviderJson.decodeFromJsonElement(InteractionsAnnotation.serializer(), parseJsonElement(json))

    @Test
    fun `citations map to sources by kind, and a citation with nothing to cite is skipped`() {
        val url = assertIs<Content.Source.Url>(
            annotation("""{"type":"url_citation","url":"https://example.com","title":"Example"}""").toSource(ids),
        )
        assertEquals("https://example.com", url.url)
        assertEquals("Example", url.title)
        assertNull(annotation("""{"type":"url_citation"}""").toSource(ids))

        val fileAsUrl = assertIs<Content.Source.Url>(
            annotation("""{"type":"file_citation","document_uri":"https://docs.example/a.pdf","file_name":"a.pdf"}""").toSource(ids),
        )
        assertEquals("a.pdf", fileAsUrl.title)
        val document = assertIs<Content.Source.Document>(
            annotation("""{"type":"file_citation","document_uri":"gs://bucket/report.pdf"}""").toSource(ids),
        )
        assertEquals("application/pdf", document.mediaType)
        assertEquals("report.pdf", document.filename)
        assertEquals("report.pdf", document.title)

        val place = assertIs<Content.Source.Url>(
            annotation("""{"type":"place_citation","url":"https://maps.example/p","name":"Cafe"}""").toSource(ids),
        )
        assertEquals("Cafe", place.title)
        assertNull(annotation("""{"type":"something_else","url":"https://x"}""").toSource(ids))
    }

    @Test
    fun `a text block's annotations are de-duplicated by URL`() {
        val sources = listOf(
            annotation("""{"type":"url_citation","url":"https://a.example"}"""),
            annotation("""{"type":"url_citation","url":"https://a.example","title":"again"}"""),
            annotation("""{"type":"url_citation","url":"https://b.example"}"""),
        ).toSources(ids)
        assertEquals(listOf("https://a.example", "https://b.example"), sources.map { (it as Content.Source.Url).url })
        assertTrue((null as List<InteractionsAnnotation>?).toSources(ids).isEmpty())
    }

    @Test
    fun `built-in results yield what they fetched, and code execution yields nothing`() {
        fun urls(type: String, result: String) =
            builtinToolResultToSources(type, parseJsonElement(result), ids).map { (it as? Content.Source.Url)?.url ?: (it as Content.Source.Document).filename }

        assertEquals(
            listOf("https://ok.example", "https://nostatus.example"),
            urls(
                "url_context_result",
                """[{"url":"https://ok.example","status":"success"},{"url":"https://bad.example","status":"error"},{"url":"https://nostatus.example"}]""",
            ),
        )
        assertEquals(
            listOf("https://r.example"),
            urls("google_search_result", """[{"search_suggestions":"<div/>"},{"url":"https://r.example","title":"R"}]"""),
        )
        assertEquals(
            listOf("https://place.example"),
            urls("google_maps_result", """[{"places":[{"name":"P","url":"https://place.example"},{"name":"no url"}]}]"""),
        )
        assertEquals(
            listOf("https://f.example", "notes.md"),
            urls("file_search_result", """[{"url":"https://f.example","title":"F"},{"document_uri":"gs://b/notes.md"},{}]"""),
        )
        assertTrue(urls("code_execution_result", "\"1\\n\"").isEmpty())
        assertTrue(urls("google_search_result", "null").isEmpty())
    }

    // --- usage, finish reason, timestamps --------------------------------------------------------

    @Test
    fun `usage adds thought tokens to the output total and keeps the whole object raw`() {
        val raw = parseJsonObject(
            """{"total_tokens":58,"total_input_tokens":7,"total_cached_tokens":2,"total_output_tokens":19,""" +
                """"total_thought_tokens":32,"grounding_tool_count":[{"type":"google_search","count":1}]}""",
        )
        assertEquals(
            Usage(
                inputTokens = Usage.InputTokens(total = 7, noCache = 5, cacheRead = 2),
                outputTokens = Usage.OutputTokens(total = 51, text = 19, reasoning = 32),
                raw = raw,
            ),
            raw.toUsage(),
        )
        assertEquals(Usage(), (null as JsonObject?).toUsage())
        val partial = parseJsonObject("""{"total_output_tokens":3}""")
        assertEquals(
            Usage(outputTokens = Usage.OutputTokens(total = 3, text = 3), raw = partial),
            partial.toUsage(),
        )
        assertEquals(
            mapOf("video" to 57_920, "text" to 12),
            parseJsonObject("""{"output_tokens_by_modality":[{"modality":"video","tokens":57920},{"modality":"text","tokens":12},{"tokens":1}]}""")
                .outputTokensByModality(),
        )
        assertNull(raw.outputTokensByModality())
    }

    @Test
    fun `the status maps to the finish vocabulary, with a function call turning completed into tool-calls`() {
        fun unified(status: String?, hasFunctionCall: Boolean = false) =
            interactionsFinishReason(status, hasFunctionCall).unified
        assertEquals(FinishReason.Unified.Stop, unified("completed"))
        assertEquals(FinishReason.Unified.ToolCalls, unified("completed", hasFunctionCall = true))
        assertEquals(FinishReason.Unified.ToolCalls, unified("requires_action"))
        assertEquals(FinishReason.Unified.Error, unified("failed"))
        assertEquals(FinishReason.Unified.Length, unified("incomplete"))
        assertEquals(FinishReason.Unified.Other, unified("cancelled"))
        assertEquals(FinishReason.Unified.Other, unified("in_progress"))
        assertEquals(FinishReason.Unified.Other, unified(null))
        assertEquals("requires_action", interactionsFinishReason("requires_action", false).raw)
    }

    @Test
    fun `created parses as epoch millis and garbage is null`() {
        assertEquals(1_778_871_115_000, parseCreatedMillis("2026-05-15T18:51:55Z"))
        assertNull(parseCreatedMillis("yesterday"))
        assertNull(parseCreatedMillis(null))
    }

    @Test
    fun `a result step without a payload is a JSON null result`() {
        val parsed = parse("""[{"type":"url_context_result","call_id":"u1"}]""")
        assertEquals(ToolOutput.Json(JsonNull), assertIs<Content.ToolResult>(parsed.content.single()).output)
    }
}
