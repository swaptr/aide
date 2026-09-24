package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.NoSuchProviderReferenceError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The per-file options of `convert-to-anthropic-prompt.ts:100-137`: `citations`, `title`, `context`,
 * `containerUpload` — each read from the file part's `providerOptions.anthropic` and emitted on the
 * document block, plus the typed failure for a reference with no Anthropic id.
 */
class AnthropicFilePartOptionsTest {

    private fun bodyFor(part: UserPart.File): JsonObject {
        val built = AnthropicRequestBuilder.build(
            modelId = "claude-sonnet-4-5",
            options = CallOptions(prompt = listOf(ModelMessage.User(listOf(part)))),
            supportsNativeStructuredOutput = true,
            optionsNamespace = null,
        )
        return built.body
    }

    private fun firstBlock(body: JsonObject): JsonObject = body["messages"]!!.jsonArray[0]
        .jsonObject["content"]!!.jsonArray[0].jsonObject

    private fun anthropicOptions(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        mapOf(ANTHROPIC_PROVIDER_ID to buildJsonObject(build))

    @Test
    fun `citations, title and context land on an inline document`() {
        val block = firstBlock(
            bodyFor(
                UserPart.File(
                    data = FileData.Text("The capital of France is Paris."),
                    mediaType = "text/plain",
                    filename = "facts.txt",
                    providerOptions = anthropicOptions {
                        put("citations", buildJsonObject { put("enabled", true) })
                        put("title", "European facts")
                        put("context", "A reference document about Europe")
                    },
                ),
            ),
        )

        assertEquals("document", block["type"].string())
        // The caller's title WINS over the filename fallback.
        assertEquals("European facts", block["title"].string())
        assertEquals("A reference document about Europe", block["context"].string())
        // This key is the switch that makes Anthropic attach cited passages to the answer.
        assertTrue(block["citations"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `the filename is the title when the caller sets none`() {
        val block = firstBlock(
            bodyFor(
                UserPart.File(
                    data = FileData.Text("text"),
                    mediaType = "text/plain",
                    filename = "notes.txt",
                ),
            ),
        )

        assertEquals("notes.txt", block["title"].string())
        // No citations key unless asked for: an absent key is the vendor default, per the house rule.
        assertTrue("citations" !in block)
        assertTrue("context" !in block)
    }

    @Test
    fun `a PDF document carries the same options`() {
        val block = firstBlock(
            bodyFor(
                UserPart.File(
                    data = FileData.Bytes(byteArrayOf(0x25, 0x50, 0x44, 0x46)),
                    mediaType = "application/pdf",
                    providerOptions = anthropicOptions {
                        put("citations", buildJsonObject { put("enabled", true) })
                        put("title", "Quarterly report")
                    },
                ),
            ),
        )

        assertEquals("document", block["type"].string())
        assertEquals("Quarterly report", block["title"].string())
        assertTrue(block["citations"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an image never grows document metadata`() {
        val block = firstBlock(
            bodyFor(
                UserPart.File(
                    data = FileData.Bytes(byteArrayOf(1)),
                    mediaType = "image/png",
                    filename = "photo.png",
                    providerOptions = anthropicOptions { put("title", "ignored") },
                ),
            ),
        )

        assertEquals("image", block["type"].string())
        assertTrue("title" !in block)
        assertTrue("citations" !in block)
    }

    @Test
    fun `containerUpload diverts a file id into the code-execution container`() {
        val block = firstBlock(
            bodyFor(
                UserPart.File(
                    data = FileData.Reference(mapOf(ANTHROPIC_PROVIDER_ID to "file-abc")),
                    mediaType = "text/csv",
                    providerOptions = anthropicOptions { put("containerUpload", true) },
                ),
            ),
        )

        // Its own block type, no source envelope: the file goes to the container, not the conversation.
        assertEquals("container_upload", block["type"].string())
        assertEquals("file-abc", block["file_id"].string())
        assertTrue("source" !in block)
    }

    @Test
    fun `a file-id document stays bare, as the reference sends it`() {
        val block = firstBlock(
            bodyFor(
                UserPart.File(
                    data = FileData.Reference(mapOf(ANTHROPIC_PROVIDER_ID to "file-abc")),
                    mediaType = "application/pdf",
                    filename = "report.pdf",
                ),
            ),
        )

        assertEquals("document", block["type"].string())
        assertEquals("file", block["source"]!!.jsonObject["type"].string())
        // The vendor already knows the file's name; the reference sends no title here, so neither do we.
        assertTrue("title" !in block)
    }

    @Test
    fun `a reference with no Anthropic id is a typed failure, not a silent drop`() {
        val error = assertFailsWith<NoSuchProviderReferenceError> {
            bodyFor(
                UserPart.File(
                    data = FileData.Reference(mapOf("openai" to "file-openai-1")),
                    mediaType = "application/pdf",
                ),
            )
        }

        assertEquals(ANTHROPIC_PROVIDER_ID, error.provider)
        // The keys tell the caller which providers DO hold the file — the actionable half.
        assertEquals(setOf("openai"), error.reference.keys)
        assertEquals("AI_NoSuchProviderReferenceError", error.errorName)
    }
}
