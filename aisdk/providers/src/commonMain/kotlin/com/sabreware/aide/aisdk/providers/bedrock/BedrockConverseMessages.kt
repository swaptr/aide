package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.options.optArray
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optElement
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderJson
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The vendor options entry Bedrock reads: our provider id first, then the reference's legacy `bedrock`
 * key, so a prompt written against `@ai-sdk/amazon-bedrock` replays here without renaming its options.
 */
internal fun ProviderOptions?.bedrockEntry(): JsonObject? =
    this?.get(BEDROCK_PROVIDER_ID) ?: this?.get("bedrock")

/**
 * Prompt → Converse `system` + `messages`.
 *
 * Converse is one wire for every hosted vendor, and three of its rules are easy to violate silently:
 *
 * - **Roles alternate.** Consecutive user and tool messages must merge into ONE user message (a tool
 *   result is user content here), and consecutive assistant messages into one assistant message —
 *   sending them separately is a validation error naming neither message.
 * - **Attachments are typed unions**, `image`/`video`/`document`, each with its own closed format enum.
 *   A media type outside the enum is refused HERE rather than sent, because Bedrock's own rejection
 *   names the whole request and not the part.
 * - **Only signed or redacted reasoning is replayed.** Some hosted models (OpenAI gpt-oss) return
 *   reasoning with no signature; replaying it in a multi-turn tool loop can leak raw reasoning into the
 *   visible response, so an unsigned block is deliberately dropped — the reference does the same.
 * - **A provider-executed tool result is replayed as a `toolResult`, in place.** An assistant turn
 *   that ran a tool on the vendor's side carries the result inside the turn; Converse has no assistant
 *   form for it, so the turn is split at each result — assistant up to the call, a user message with
 *   the result, assistant again — which keeps the roles alternating and the order the model saw.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object BedrockConverseMessages {

    class Converted(val system: JsonArray, val messages: JsonArray)

    fun convert(prompt: Prompt, isMistral: Boolean): Converted {
        val system = mutableListOf<JsonElement>()
        val messages = mutableListOf<JsonElement>()
        val documents = DocumentNames()

        val blocks = groupIntoBlocks(prompt)
        blocks.forEachIndexed { index, block ->
            val isLastBlock = index == blocks.lastIndex
            when (block) {
                is Block.System -> {
                    if (messages.isNotEmpty()) {
                        throw UnsupportedFunctionalityError(
                            "Multiple system messages that are separated by user/assistant messages",
                        )
                    }
                    block.messages.forEach { message ->
                        system += buildJsonObject { put("text", message.content) }
                        message.providerOptions.cachePoint()?.let { system += it }
                    }
                }

                is Block.User -> appendToUserMessage(messages, userContent(block, documents, isMistral))

                is Block.Assistant -> appendAssistantBlock(messages, block, isLastBlock, isMistral, documents)
            }
        }
        return Converted(JsonArray(system), JsonArray(messages))
    }

    /**
     * Appends user content, merging into a trailing user message that holds a `toolResult`.
     *
     * That trailing message is a provider-executed result split out of the assistant turn before it;
     * the user's next message has to share it, or the wire sees two user turns in a row.
     */
    private fun appendToUserMessage(messages: MutableList<JsonElement>, content: List<JsonElement>) {
        val last = messages.lastOrNull() as? JsonObject
        val lastContent = last?.get("content") as? JsonArray
        val holdsToolResult = last?.optString("role") == "user" &&
            lastContent?.any { (it as? JsonObject)?.containsKey("toolResult") == true } == true
        if (holdsToolResult) {
            messages[messages.lastIndex] = JsonObject(last + ("content" to JsonArray(lastContent.orEmpty() + content)))
        } else {
            messages += buildJsonObject {
                put("role", "user")
                put("content", JsonArray(content))
            }
        }
    }

    // --- User side ---------------------------------------------------------------------------------

    private fun userContent(block: Block.User, documents: DocumentNames, isMistral: Boolean): JsonArray =
        buildJsonArray {
            block.messages.forEach { message ->
                when (message) {
                    is ModelMessage.User -> message.content.forEach { part ->
                        when (part) {
                            is UserPart.Text -> add(textBlock(part))
                            is UserPart.File -> add(fileBlock(part, documents))
                        }
                        part.providerOptions.cachePoint()?.let { add(it) }
                    }

                    is ModelMessage.Tool -> message.content.forEach { part ->
                        // An approval decision has no Converse representation; it belongs to the vendor
                        // that raised the request, and Bedrock raises none.
                        if (part is ToolPart.Result) {
                            add(toolResultBlock(part, documents, isMistral))
                            part.providerOptions.cachePoint()?.let { add(it) }
                        }
                    }

                    else -> Unit
                }
                message.providerOptions.cachePoint()?.let { add(it) }
            }
        }

    /** Guardrail-scoped text goes inside `guardContent` so the guardrail assesses it; plain text does not. */
    private fun textBlock(part: UserPart.Text): JsonObject {
        val vendor = part.providerOptions.bedrockEntry()
        if (vendor?.optBoolean("guardContent") != true) {
            return buildJsonObject { put("text", part.text) }
        }
        return buildJsonObject {
            putJsonObject("guardContent") {
                putJsonObject("text") {
                    put("text", part.text)
                    vendor.optArray("guardContentQualifiers")?.let { put("qualifiers", it) }
                }
            }
        }
    }

    private fun fileBlock(part: UserPart.File, documents: DocumentNames): JsonObject {
        val topLevel = part.mediaType.substringBefore('/')
        return when (val data = part.data) {
            is FileData.Reference -> throw UnsupportedFunctionalityError("file parts with provider references")

            is FileData.Url -> when (topLevel) {
                "image" -> mediaBlock("image", imageFormat(part.mediaType), s3Source(data.url))
                "video" -> mediaBlock("video", videoFormat(part.mediaType), s3Source(data.url))
                else -> throw UnsupportedFunctionalityError("File URL data")
            }

            is FileData.Text -> documentBlock(
                mediaType = part.mediaType.takeIf { '/' in it } ?: "text/plain",
                name = documents.nameFor(part.filename),
                encoded = Base64.encode(data.text.encodeToByteArray()),
                citations = part.providerOptions.citationsEnabled(),
            )

            is FileData.Bytes -> when (topLevel) {
                "image" -> {
                    val block = mediaBlock("image", imageFormat(part.mediaType), bytesSource(data.bytes))
                    if (part.providerOptions.bedrockEntry()?.optBoolean("guardContent") == true) {
                        buildJsonObject { put("guardContent", block) }
                    } else {
                        block
                    }
                }
                "video" -> mediaBlock("video", videoFormat(part.mediaType), bytesSource(data.bytes))
                else -> documentBlock(
                    mediaType = part.mediaType,
                    name = documents.nameFor(part.filename),
                    encoded = Base64.encode(data.bytes),
                    citations = part.providerOptions.citationsEnabled(),
                )
            }
        }
    }

    private fun toolResultBlock(part: ToolPart.Result, documents: DocumentNames, isMistral: Boolean): JsonObject =
        toolResultBlock(part.toolCallId, part.output, documents, isMistral)

    private fun toolResultBlock(
        toolCallId: String,
        output: ToolOutput,
        documents: DocumentNames,
        isMistral: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonObject("toolResult") {
            put("toolUseId", normalizeToolCallId(toolCallId, isMistral))
            put("content", toolResultContent(output, documents))
        }
    }

    private fun toolResultContent(output: ToolOutput, documents: DocumentNames): JsonArray = buildJsonArray {
        when (output) {
            is ToolOutput.Text -> add(buildJsonObject { put("text", output.value) })
            is ToolOutput.ErrorText -> add(buildJsonObject { put("text", output.value) })
            is ToolOutput.ExecutionDenied -> add(
                buildJsonObject { put("text", output.reason ?: "Tool call execution denied.") },
            )
            is ToolOutput.Json -> add(buildJsonObject { put("text", encode(output.value)) })
            is ToolOutput.ErrorJson -> add(buildJsonObject { put("text", encode(output.value)) })
            is ToolOutput.Multipart -> output.value.forEach { item ->
                when (item) {
                    is ToolOutput.Multipart.Item.Text -> add(buildJsonObject { put("text", item.text) })
                    is ToolOutput.Multipart.Item.File -> add(toolResultFile(item, documents))
                    is ToolOutput.Multipart.Item.Custom ->
                        throw UnsupportedFunctionalityError("unsupported tool content part type: custom")
                }
            }
        }
    }

    private fun toolResultFile(item: ToolOutput.Multipart.Item.File, documents: DocumentNames): JsonObject {
        val topLevel = item.mediaType.substringBefore('/')
        val source = when (val data = item.data) {
            is FileData.Bytes -> bytesSource(data.bytes)
            is FileData.Url ->
                if (topLevel == "image" || topLevel == "video") {
                    s3Source(data.url)
                } else {
                    throw UnsupportedFunctionalityError("tool result file data of type \"url\"")
                }
            else -> throw UnsupportedFunctionalityError("tool result file data")
        }
        return when (topLevel) {
            "image" -> mediaBlock("image", imageFormat(item.mediaType), source)
            "video" -> mediaBlock("video", videoFormat(item.mediaType), source)
            else -> {
                val bytes = (item.data as? FileData.Bytes)?.bytes
                    ?: throw UnsupportedFunctionalityError("tool result file data")
                documentBlock(
                    mediaType = item.mediaType,
                    name = documents.nameFor(item.filename),
                    encoded = Base64.encode(bytes),
                    citations = item.providerOptions.citationsEnabled(),
                )
            }
        }
    }

    // --- Assistant side ----------------------------------------------------------------------------

    /**
     * One assistant block → one or more messages.
     *
     * Assistant parts accumulate into an assistant message and provider-executed results into a user
     * message; each kind is flushed the moment the other kind appears, so a turn of call, result, call,
     * result, text comes out as five alternating messages in the model's own order. A cache point lands
     * on whichever list its part went to, and a message-level one on whichever list its last part did.
     */
    @Suppress("LongMethod")
    private fun appendAssistantBlock(
        messages: MutableList<JsonElement>,
        block: Block.Assistant,
        isLastBlock: Boolean,
        isMistral: Boolean,
        documents: DocumentNames,
    ) {
        var assistant = mutableListOf<JsonElement>()
        var toolResults = mutableListOf<JsonElement>()

        fun flushAssistant() {
            if (assistant.any { (it as? JsonObject)?.containsKey("cachePoint") != true }) {
                messages += buildJsonObject {
                    put("role", "assistant")
                    put("content", JsonArray(assistant))
                }
            }
            assistant = mutableListOf()
        }

        fun flushToolResults() {
            if (toolResults.isNotEmpty()) {
                appendToUserMessage(messages, toolResults)
                toolResults = mutableListOf()
            }
        }

        block.messages.forEachIndexed { messageIndex, message ->
            val isLastMessage = messageIndex == block.messages.lastIndex
            val hasReasoning = message.content.any { it is AssistantPart.Reasoning }
            message.content.forEachIndexed { partIndex, part ->
                val isLastPart = partIndex == message.content.lastIndex
                if (part !is AssistantPart.ToolResult) flushToolResults()
                val target = if (part is AssistantPart.ToolResult) toolResults else assistant
                val blocksBefore = target.size
                when (part) {
                    is AssistantPart.Text -> {
                        // An empty text block alongside nothing else is noise; alongside reasoning it
                        // keeps the part count aligned with what the model actually produced.
                        if (part.text.isNotBlank() || hasReasoning) {
                            // Bedrock rejects trailing whitespace on a pre-filled assistant turn, so
                            // only the very last text of the very last message is trimmed.
                            val trim = isLastBlock && isLastMessage && isLastPart
                            target += buildJsonObject { put("text", if (trim) part.text.trim() else part.text) }
                        }
                    }
                    is AssistantPart.Reasoning -> reasoningBlock(part)?.let { target += it }
                    is AssistantPart.ToolCall -> target += toolUseBlock(part, isMistral)
                    is AssistantPart.ToolResult -> {
                        flushAssistant()
                        target += toolResultBlock(part.toolCallId, part.output, documents, isMistral)
                    }
                    else -> Unit
                }
                // Only after a block this turn actually emitted. A cache point marks the end of a
                // cacheable prefix, so one emitted after a part that produced nothing — an approval
                // request, an unsigned reasoning block — marks a boundary that is not there.
                if (target.size > blocksBefore) part.providerOptions.cachePoint()?.let { target += it }
            }
            val lastTarget = if (message.content.lastOrNull() is AssistantPart.ToolResult) toolResults else assistant
            message.providerOptions.cachePoint()?.let { lastTarget += it }
        }

        flushToolResults()
        flushAssistant()
    }

    /**
     * Signed text, a redacted payload, or nothing.
     *
     * A signature validates the exact original bytes, so signed text is replayed UNTRIMMED. Unsigned
     * reasoning is dropped on purpose — see the class doc.
     */
    private fun reasoningBlock(part: AssistantPart.Reasoning): JsonObject? {
        val vendor = part.providerOptions.bedrockEntry() ?: return null
        vendor.optString(BEDROCK_SIGNATURE_KEY)?.let { signature ->
            return buildJsonObject {
                putJsonObject("reasoningContent") {
                    putJsonObject("reasoningText") {
                        put("text", part.text)
                        put("signature", signature)
                    }
                }
            }
        }
        vendor.optString(BEDROCK_REDACTED_CONTENT_KEY)?.let { redacted ->
            return buildJsonObject {
                putJsonObject("reasoningContent") { put("redactedContent", redacted) }
            }
        }
        vendor.optString(BEDROCK_REDACTED_DATA_KEY)?.let { data ->
            return buildJsonObject {
                putJsonObject("reasoningContent") {
                    putJsonObject("redactedReasoning") { put("data", data) }
                }
            }
        }
        return null
    }

    private fun toolUseBlock(part: AssistantPart.ToolCall, isMistral: Boolean): JsonObject = buildJsonObject {
        putJsonObject("toolUse") {
            put("toolUseId", normalizeToolCallId(part.toolCallId, isMistral))
            put("name", sanitizeToolName(part.toolName))
            put("input", toolInput(part.input))
        }
    }

    /** Converse requires an OBJECT input; anything else is wrapped rather than rejected downstream. */
    private fun toolInput(input: String): JsonObject {
        val parsed = runCatching {
            ProviderJson.decodeFromString(JsonElement.serializer(), input.ifBlank { "{}" })
        }.getOrNull()
        return parsed as? JsonObject
            ?: buildJsonObject { put("rawInvalidInput", parsed ?: JsonPrimitive(input)) }
    }

    // --- Shared pieces -----------------------------------------------------------------------------

    private fun mediaBlock(kind: String, format: String, source: JsonObject): JsonObject = buildJsonObject {
        putJsonObject(kind) {
            put("format", format)
            put("source", source)
        }
    }

    private fun bytesSource(bytes: ByteArray): JsonObject =
        buildJsonObject { put("bytes", Base64.encode(bytes)) }

    /** Bedrock fetches URLs only from S3; handing it any other host is a validation error. */
    private fun s3Source(url: String): JsonObject {
        if (!url.startsWith("s3://")) throw UnsupportedFunctionalityError("File URL data")
        return buildJsonObject { putJsonObject("s3Location") { put("uri", url) } }
    }

    private fun documentBlock(mediaType: String, name: String, encoded: String, citations: Boolean): JsonObject =
        buildJsonObject {
            putJsonObject("document") {
                put("format", documentFormat(mediaType))
                put("name", name)
                putJsonObject("source") { put("bytes", encoded) }
                if (citations) putJsonObject("citations") { put("enabled", true) }
            }
        }

    private fun imageFormat(mediaType: String): String = IMAGE_FORMATS[mediaType]
        ?: throw UnsupportedFunctionalityError(
            functionality = "image mime type: $mediaType",
            message = "Unsupported image mime type: $mediaType, " +
                "expected one of: ${IMAGE_FORMATS.keys.joinToString(", ")}",
        )

    private fun videoFormat(mediaType: String): String = VIDEO_FORMATS[mediaType]
        ?: throw UnsupportedFunctionalityError(
            functionality = "video mime type: $mediaType",
            message = "Unsupported video mime type: $mediaType, " +
                "expected one of: ${VIDEO_FORMATS.keys.joinToString(", ")}",
        )

    private fun documentFormat(mediaType: String): String = DOCUMENT_FORMATS[mediaType]
        ?: throw UnsupportedFunctionalityError(
            functionality = "file mime type: $mediaType",
            message = "Unsupported file mime type: $mediaType, " +
                "expected one of: ${DOCUMENT_FORMATS.keys.joinToString(", ")}",
        )

    private fun encode(value: JsonElement): String =
        ProviderJson.encodeToString(JsonElement.serializer(), value)

    private fun ProviderOptions?.cachePoint(): JsonObject? =
        bedrockEntry()?.optElement("cachePoint")?.let { config ->
            buildJsonObject { put("cachePoint", config) }
        }

    private fun ProviderOptions?.citationsEnabled(): Boolean =
        bedrockEntry()?.optObject("citations")?.optBoolean("enabled") == true

    /**
     * Documents need names; an unnamed one gets `document-N`, and a named one is reduced to what
     * Bedrock accepts by [sanitizeDocumentName] — falling back to `document-N` when nothing survives.
     */
    private class DocumentNames {
        private var counter = 0
        fun nameFor(filename: String?): String {
            val sanitized = filename?.let(::sanitizeDocumentName)?.takeIf { it.isNotEmpty() }
            return sanitized ?: "document-${++counter}"
        }
    }

    // --- Grouping ----------------------------------------------------------------------------------

    private sealed interface Block {
        class System(val messages: List<ModelMessage.System>) : Block
        class User(val messages: List<ModelMessage>) : Block
        class Assistant(val messages: List<ModelMessage.Assistant>) : Block
    }

    /** Adjacent same-role turns fold into one message; a tool turn is user content on this wire. */
    private fun groupIntoBlocks(prompt: Prompt): List<Block> {
        val blocks = mutableListOf<Block>()
        var run = mutableListOf<ModelMessage>()
        var runRole: Char? = null

        fun flush() {
            if (run.isEmpty()) return
            blocks += when (runRole) {
                's' -> Block.System(run.map { it as ModelMessage.System })
                'a' -> Block.Assistant(run.map { it as ModelMessage.Assistant })
                else -> Block.User(run.toList())
            }
            run = mutableListOf()
        }

        for (message in prompt) {
            val role = when (message) {
                is ModelMessage.System -> 's'
                is ModelMessage.Assistant -> 'a'
                is ModelMessage.User, is ModelMessage.Tool -> 'u'
            }
            if (role != runRole) {
                flush()
                runRole = role
            }
            run += message
        }
        flush()
        return blocks
    }
}

/** Mistral models on Bedrock, including region-prefixed inference profiles. */
internal fun isMistralModel(modelId: String): Boolean = "mistral." in modelId

/**
 * Mistral on Bedrock requires tool call ids matching `^[a-zA-Z0-9]{9}$`, while Bedrock mints ids like
 * `tooluse_bpe71yCfRu2b5i-nKGDr5g`. The first nine alphanumerics keep the id stable in both directions —
 * call and result normalize identically, so they still correlate.
 */
internal fun normalizeToolCallId(toolCallId: String, isMistral: Boolean): String {
    if (!isMistral) return toolCallId
    return toolCallId.filter { it.isLetterOrDigit() }.take(TOOL_ID_LENGTH)
}

/** Converse tool names are `[a-zA-Z0-9_-]`; anything else is stripped rather than rejected. */
internal fun sanitizeToolName(toolName: String): String =
    toolName.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "_" }

/**
 * A filename reduced to Bedrock's document-name constraints: alphanumerics, single spaces, hyphens,
 * parentheses and square brackets, at most 200 characters, with the extension gone.
 *
 * Bedrock rejects the whole request for a name outside that set — an apostrophe in `John's report`
 * was enough — and names the request rather than the document. The extension is cut at the FIRST dot,
 * as the reference's `stripFileExtension` does. Non-ASCII letters are dropped rather than transliterated,
 * so a name that was entirely non-Latin comes back empty and the caller gets `document-N`.
 */
internal fun sanitizeDocumentName(filename: String): String = filename
    .substringBefore('.')
    .replace(WHITESPACE_RUN, " ")
    .replace(DISALLOWED_DOCUMENT_NAME_CHARS, "")
    .trim()
    .take(MAX_DOCUMENT_NAME_LENGTH)
    .trim()

private val WHITESPACE_RUN = Regex("\\s+")
private val DISALLOWED_DOCUMENT_NAME_CHARS = Regex("[^a-zA-Z0-9 ()\\[\\]-]")
private const val MAX_DOCUMENT_NAME_LENGTH = 200

/** The reasoning-metadata keys this provider writes and reads back, under [BEDROCK_PROVIDER_ID]. */
internal const val BEDROCK_SIGNATURE_KEY: String = "signature"
internal const val BEDROCK_REDACTED_DATA_KEY: String = "redactedData"
internal const val BEDROCK_REDACTED_CONTENT_KEY: String = "redactedContent"

private const val TOOL_ID_LENGTH = 9

/** `ImageBlock`'s closed format enum — a type outside it is refused before the wire. */
private val IMAGE_FORMATS = mapOf(
    "image/jpeg" to "jpeg",
    "image/png" to "png",
    "image/gif" to "gif",
    "image/webp" to "webp",
)

/** `DocumentBlock`'s closed format enum. */
private val DOCUMENT_FORMATS = mapOf(
    "application/pdf" to "pdf",
    "text/csv" to "csv",
    "application/msword" to "doc",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
    "application/vnd.ms-excel" to "xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
    "text/html" to "html",
    "text/plain" to "txt",
    "text/markdown" to "md",
)

/** `VideoBlock`'s closed format enum. */
private val VIDEO_FORMATS = mapOf(
    "video/x-matroska" to "mkv",
    "video/quicktime" to "mov",
    "video/mp4" to "mp4",
    "video/webm" to "webm",
    "video/x-flv" to "flv",
    "video/mpeg" to "mpeg",
    "video/mpg" to "mpg",
    "video/wmv" to "wmv",
    "video/x-ms-wmv" to "wmv",
    "video/3gpp" to "three_gp",
)
