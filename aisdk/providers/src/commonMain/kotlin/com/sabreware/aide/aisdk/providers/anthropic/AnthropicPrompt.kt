package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.NoSuchProviderReferenceError
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.ToolNameMapping
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A prompt in Anthropic's shape: the system prompt hoisted out, everything else as typed blocks. */
internal data class AnthropicConversation(
    /**
     * A bare string, or an array of text blocks when one of them carries a `cache_control`.
     *
     * Anthropic accepts both, and the array form is the only way to set a cache breakpoint on the system
     * prompt — the single most valuable one there is, since the system prompt is the largest thing that
     * never changes between turns.
     */
    val system: JsonElement?,
    val messages: List<AnthropicMessage>,
)

/**
 * Everything the conversion needs to say something back.
 *
 * Bundled because it grew: a converter that can only return messages has nowhere to report a part it had
 * to drop, and "dropped silently" is how a signature loss becomes a mystery 400 three turns later.
 */
internal class AnthropicPromptContext(
    val warnings: MutableList<Warning>,
    val cacheControls: AnthropicCacheControlBudget,
    val toolNames: ToolNameMapping = ToolNameMapping.Identity,
    /** False where a deployment rejects thinking blocks on input; the blocks are dropped with a warning. */
    val sendReasoning: Boolean = true,
    /** Betas the prompt itself requires — the Files API, PDF documents. */
    val betas: MutableSet<String> = mutableSetOf(),
)

/**
 * Converts the neutral [Prompt] into Anthropic's request shape.
 *
 * **The part that matters.** An assistant turn is replayed with its thinking blocks intact and IN ORDER:
 * a `thinking` block carries back the `signature` it arrived with, and a `redacted_thinking` block
 * carries back its encrypted `data`. Anthropic rejects the request outright if either is missing,
 * reordered, or re-serialized — `Invalid signature in thinking block` — and requires a replayed turn to
 * BEGIN with its thinking block when thinking is enabled.
 *
 * This is exactly what a provider layer that models reasoning as a display string cannot do, and it is
 * why the neutral types carry `providerMetadata` rather than a fixed set of fields.
 */
internal fun Prompt.toAnthropic(context: AnthropicPromptContext): AnthropicConversation {
    val systemBlocks = mutableListOf<JsonObject>()
    val messages = mutableListOf<AnthropicMessage>()

    var index = 0
    while (index < size) {
        val message = this[index]
        if (message is ModelMessage.System) {
            // A run of consecutive system messages is one block and is decided as one: the reference
            // groups the prompt into role blocks and hoists or inlines a whole system block, never
            // half of one. "Initial" means the very first block of the prompt.
            val runStart = index
            val run = mutableListOf<ModelMessage.System>()
            while (index < size) {
                run += this[index] as? ModelMessage.System ?: break
                index++
            }
            convertSystemBlock(run, initial = runStart == 0, systemBlocks, messages, context)
            continue
        }
        when (message) {
            is ModelMessage.User ->
                messages.addBlocks("user", message.content.mapNotNull { it.toBlock(context) })
            is ModelMessage.Assistant ->
                messages.addBlocks("assistant", message.content.mapNotNull { it.toBlock(context) })
            // Tool results ride in a USER turn — Anthropic has no tool role.
            is ModelMessage.Tool ->
                messages.addBlocks("user", message.content.mapNotNull { it.toBlock(context) })
            is ModelMessage.System -> Unit
        }
        index++
    }

    return AnthropicConversation(
        system = systemBlocks.toSystemValue(),
        messages = messages,
    )
}

/**
 * A system message after conversion: its content blocks plus the three message-level controls that
 * only exist mid-conversation.
 */
private class ConvertedSystemMessage(
    val content: List<JsonObject>,
    val clearAt: String?,
    val effort: String?,
    val toolChangeCount: Int,
)

/**
 * Where a system block lands: hoisted into the top-level `system`, or inlined as `role: system`
 * messages.
 *
 * The first block always hoists — that IS the system prompt. A later block hoists too when nothing
 * has claimed the top-level slot yet and the block carries nothing that only makes sense inline,
 * preserving the plain-text hoisting this converter always did. Otherwise it is sent mid-conversation,
 * which is the only place tool changes, `clear_at` and a per-turn effort are valid: the API rejects
 * all three on the initial system prompt, so on the hoisted path they are dropped with a warning
 * rather than sent as a 400.
 */
private fun convertSystemBlock(
    run: List<ModelMessage.System>,
    initial: Boolean,
    systemBlocks: MutableList<JsonObject>,
    messages: MutableList<AnthropicMessage>,
    context: AnthropicPromptContext,
) {
    val converted = run.map { it.toSystemMessage(context) }
    val toolChangeCount = converted.sumOf { it.toolChangeCount }
    val hasInlineOptions = converted.any { it.clearAt != null || it.effort != null }

    if (initial || (systemBlocks.isEmpty() && toolChangeCount == 0 && !hasInlineOptions)) {
        if (toolChangeCount > 0) {
            context.warnings += Warning.Other(
                "tool changes on the initial system message are not supported by Anthropic. " +
                    "Configure the initial tool set via the tools option instead. " +
                    "The tool changes have been ignored.",
            )
        }
        converted.forEach { message ->
            if (message.clearAt != null || message.effort != null) {
                context.warnings += Warning.Other(
                    "clearAt and effort on the initial system message are not supported by Anthropic. " +
                        "These options have been ignored.",
                )
            }
        }
        systemBlocks += converted.flatMap { message ->
            message.content.filter { it["type"]?.stringOrNull() == "text" }
        }
        return
    }

    context.betas += MID_CONVERSATION_SYSTEM_BETA
    converted.forEach { message ->
        messages += AnthropicMessage(
            role = "system",
            content = message.content,
            clearAt = message.clearAt,
            outputConfig = message.effort?.let { effort -> buildJsonObject { put("effort", effort) } },
        )
        if (message.toolChangeCount > 0) context.betas += MID_CONVERSATION_TOOL_CHANGES_BETA
        if (message.clearAt != null) context.betas += MID_CONVERSATION_CLEAR_AT_BETA
        if (message.effort != null) context.betas += MID_CONVERSATION_EFFORT_BETA
    }
}

/**
 * One system message's blocks and controls, read from `providerOptions.anthropic`.
 *
 * `clearAt` and `effort` are validated against the values the API defines and dropped with a warning
 * otherwise — the reference refuses them at its option parser, and an unknown value sent through is a
 * 400 naming only the request. A message carrying only controls may have empty text, and an empty
 * text block is itself rejected, so none is emitted for it.
 */
private fun ModelMessage.System.toSystemMessage(context: AnthropicPromptContext): ConvertedSystemMessage {
    val anthropic = providerOptions?.get(ANTHROPIC_PROVIDER_ID)
    val clearAt = anthropic?.stringOrNull("clearAt")?.let { value ->
        value.takeIf { it == SYSTEM_CLEAR_AT_NEXT_USER_MESSAGE } ?: run {
            context.warnings += Warning.Other(
                "system message clearAt \"$value\" is not supported; only " +
                    "\"$SYSTEM_CLEAR_AT_NEXT_USER_MESSAGE\" is. It has been ignored.",
            )
            null
        }
    }
    val effort = anthropic?.stringOrNull("effort")?.let { value ->
        value.takeIf { it in AnthropicEffortLevels } ?: run {
            context.warnings += Warning.Other("system message effort \"$value\" is not a known level. It has been ignored.")
            null
        }
    }
    val toolChanges = (anthropic?.get("toolChanges") as? JsonArray)
        ?.mapNotNull { change -> (change as? JsonObject)?.toToolChangeBlock(context) }
        .orEmpty()

    val blocks = mutableListOf<JsonObject>()
    if (content.isNotEmpty() || (toolChanges.isEmpty() && clearAt == null && effort == null)) {
        blocks += buildJsonObject {
            put("type", "text")
            put("text", content)
            context.cacheControls.take(providerOptions, "system message", context.warnings)
                ?.let { put("cache_control", it) }
        }
    }
    blocks += toolChanges
    return ConvertedSystemMessage(blocks, clearAt, effort, toolChangeCount = toolChanges.size)
}

/**
 * `{type: tool_addition | tool_removal, toolName}` → the API's tool-change block, with the tool named
 * the way the request's `tools` array names it.
 */
private fun JsonObject.toToolChangeBlock(context: AnthropicPromptContext): JsonObject? {
    val type = stringOrNull("type")
    val toolName = stringOrNull("toolName")
    if (type !in TOOL_CHANGE_TYPES || toolName == null) {
        context.warnings += Warning.Other("unsupported system message tool change: $this")
        return null
    }
    return buildJsonObject {
        put("type", type)
        put(
            "tool",
            buildJsonObject {
                put("type", "tool_reference")
                put("name", context.toolNames.toProviderToolName(toolName))
            },
        )
    }
}

/** The one `clear_at` value the API defines. */
public const val SYSTEM_CLEAR_AT_NEXT_USER_MESSAGE: String = "next_user_message"

private val TOOL_CHANGE_TYPES = setOf("tool_addition", "tool_removal")

private const val MID_CONVERSATION_SYSTEM_BETA = "mid-conversation-system-2026-04-07"
private const val MID_CONVERSATION_TOOL_CHANGES_BETA = "mid-conversation-tool-changes-2026-07-01"
private const val MID_CONVERSATION_CLEAR_AT_BETA = "mid-conversation-system-clear-at-2026-08-21"
private const val MID_CONVERSATION_EFFORT_BETA = "mid-conversation-effort-2026-08-01"

/**
 * The string form unless a breakpoint is set, in which case the array form.
 *
 * Sending the array form unconditionally would be simpler and is a needless wire change for every caller
 * that never asked for caching.
 */
private fun List<JsonObject>.toSystemValue(): JsonElement? = when {
    isEmpty() -> null
    none { "cache_control" in it } -> JsonPrimitive(joinToString("\n\n") { it["text"].asString() })
    else -> JsonArray(this)
}

/**
 * Appends blocks, merging into the previous turn when the role repeats.
 *
 * Anthropic requires alternating roles. Several tool results for one round arrive as separate neutral
 * messages and must land in a single user turn, or the request is rejected for consecutive user turns.
 */
private fun MutableList<AnthropicMessage>.addBlocks(role: String, blocks: List<JsonObject>) {
    if (blocks.isEmpty()) return
    val last = lastOrNull()
    if (last != null && last.role == role) {
        this[lastIndex] = last.copy(content = last.content + blocks)
    } else {
        add(AnthropicMessage(role = role, content = blocks))
    }
}

private fun UserPart.toBlock(context: AnthropicPromptContext): JsonObject? {
    val cacheControl = context.cacheControls.take(providerOptions, "user message part", context.warnings)
    return when (this) {
        is UserPart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
            cacheControl?.let { put("cache_control", it) }
        }
        is UserPart.File ->
            data.toSourceBlock(mediaType, filename, cacheControl, context, providerOptions)
    }
}

private fun AssistantPart.toBlock(context: AnthropicPromptContext): JsonObject? = when (this) {
    is AssistantPart.Text -> buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    // The replay path. Redacted blocks are a distinct wire type carrying only their encrypted payload;
    // ordinary thinking carries its text plus the signature that proves authorship.
    is AssistantPart.Reasoning -> reasoningBlock(context)

    is AssistantPart.ToolCall -> buildJsonObject {
        // A provider-executed call is the vendor's OWN tool running on the vendor's servers. Replaying it
        // as `tool_use` tells Anthropic to expect a client `tool_result` that will never arrive.
        put("type", if (providerExecuted) "server_tool_use" else "tool_use")
        put("id", toolCallId)
        put("name", context.toolNames.toProviderToolName(toolName))
        put("input", runCatching { ProviderJson.parseToJsonElement(input) }.getOrElse { buildJsonObject { } })
    }

    is AssistantPart.ToolResult -> providerToolResultBlock(context)

    // Bookkeeping between the runtime and whoever decides, not content for the model — and Anthropic
    // has no wire form for it (it reads the answer from whether the tool result is present at all,
    // which is why the matching ApprovalResponse is dropped too). Silently, with no warning: the
    // exchange is working exactly as designed when it does not reach the wire.
    is AssistantPart.ApprovalRequest -> null

    // Anthropic's assistant turn admits text, thinking and tool blocks and nothing else, so a generated
    // file has no replay form. Say so: a caller replaying an image the model drew would otherwise see it
    // vanish from the transcript with no explanation.
    is AssistantPart.File, is AssistantPart.ReasoningFile -> {
        context.warnings += Warning.Other("Anthropic cannot replay a generated file in an assistant turn")
        null
    }

    // The escape hatch, honoured. A block this library never modelled is replayed exactly as the mapper
    // filed it away, which is what keeps a vendor addition from needing a release here to survive a turn.
    is AssistantPart.Custom -> providerOptions?.get(ANTHROPIC_PROVIDER_ID)?.get(ANTHROPIC_BLOCK_KEY)
        as? JsonObject
        ?: run {
            context.warnings += Warning.Other("unsupported custom assistant part '$kind'")
            null
        }
}

/**
 * A thinking block, or nothing.
 *
 * The unsigned case is the one that matters. A reasoning block whose signature was lost upstream cannot
 * be replayed as `thinking` — Anthropic rejects an unsigned one — and it used to be replayed as
 * `{"type":"text","text":""}` instead, which Anthropic rejects even harder: *text content blocks must be
 * non-empty*. So a turn that had merely lost its trace became a hard 400 on every subsequent request in
 * that conversation. Dropping the block degrades the reply; keeping it ends the conversation.
 */
private fun AssistantPart.Reasoning.reasoningBlock(context: AnthropicPromptContext): JsonObject? {
    if (!context.sendReasoning) {
        context.warnings += Warning.Other("sending reasoning content is disabled for this model")
        return null
    }
    val anthropic = providerOptions?.get(ANTHROPIC_PROVIDER_ID)
    val redacted = anthropic?.stringOrNull(ANTHROPIC_REDACTED_KEY)
    val signature = anthropic?.stringOrNull(ANTHROPIC_SIGNATURE_KEY)
    // Neither payload can carry a cache breakpoint: Anthropic caches a thinking block implicitly, with
    // the turn it belongs to. Running it through the budget is how a caller that set one is told.
    context.cacheControls.take(providerOptions, "thinking block", context.warnings, canCache = false)
    return when {
        redacted != null -> buildJsonObject {
            put("type", "redacted_thinking")
            put("data", redacted)
        }
        signature != null -> buildJsonObject {
            put("type", "thinking")
            put("thinking", text)
            put("signature", signature)
        }
        else -> {
            context.warnings += Warning.Other("unsupported reasoning metadata")
            null
        }
    }
}

/**
 * The result of a tool ANTHROPIC ran, replayed under the block type it arrived as.
 *
 * `web_search_tool_result` and `code_execution_tool_result` are distinct wire types, and the mapper files
 * the one it saw under [ANTHROPIC_BLOCK_TYPE_KEY] for exactly this moment — guessing a type here would
 * replay a search result as a code execution.
 */
private fun AssistantPart.ToolResult.providerToolResultBlock(context: AnthropicPromptContext): JsonObject? {
    val blockType = providerOptions?.get(ANTHROPIC_PROVIDER_ID)?.stringOrNull(ANTHROPIC_BLOCK_TYPE_KEY)
    if (blockType == null) {
        context.warnings += Warning.Other(
            "cannot replay the provider-executed result for '$toolName': its Anthropic block type is unknown",
        )
        return null
    }
    return buildJsonObject {
        put("type", blockType)
        put("tool_use_id", toolCallId)
        put("content", output.toResultContent(context))
    }
}

/**
 * The toolset a result belongs to, from the result part's own options or the output's.
 *
 * Both are checked because a caller round-trips this value from the call's `providerMetadata`, and which
 * of the two halves it lands on is the caller's choice, not the wire's.
 */
private fun ToolPart.Result.toolsetName(): String? =
    providerOptions?.get(ANTHROPIC_PROVIDER_ID)?.get(ANTHROPIC_TOOLSET_KEY)?.stringOrNull()
        ?: output.providerOptions?.get(ANTHROPIC_PROVIDER_ID)?.get(ANTHROPIC_TOOLSET_KEY)?.stringOrNull()

private fun ToolPart.toBlock(context: AnthropicPromptContext): JsonObject? = when (this) {
    is ToolPart.Result -> buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        // A client toolset member's result MUST echo the toolset it belongs to; omitting it is a
        // rejection, not a degradation. The value rides back from the call that asked for it.
        toolsetName()?.let { put("toolset_name", it) }
        put("content", output.toResultContent(context))
        if (output.isFailure()) {
            // Flagged rather than described: a model that cannot tell failure from a string containing
            // the word "error" retries the wrong thing.
            put("is_error", true)
        }
        // The result's own options are checked too, because a large tool result is the second most
        // valuable breakpoint after the system prompt and it attaches to the output, not the part.
        val cacheControl = context.cacheControls.take(providerOptions, "tool result part", context.warnings)
            ?: context.cacheControls.take(output.providerOptions, "tool result", context.warnings)
        cacheControl?.let { put("cache_control", it) }
    }

    // A decision on an approval request has no Anthropic wire form: the API asks for approval through MCP
    // and reads the answer from whether the tool result is present at all.
    is ToolPart.ApprovalResponse -> null
}

private fun ToolOutput.isFailure(): Boolean =
    this is ToolOutput.ErrorText || this is ToolOutput.ErrorJson || this is ToolOutput.ExecutionDenied

/**
 * Anthropic's `tool_result.content`: a string, or an array of blocks.
 *
 * Multipart output takes the array form. Joining it to a string, which is what this used to do, throws
 * away every image a tool returned — the whole reason a tool result is allowed to be multi-modal.
 */
private fun ToolOutput.toResultContent(context: AnthropicPromptContext): JsonElement = when (this) {
    is ToolOutput.Text -> JsonPrimitive(value)
    is ToolOutput.Json -> JsonPrimitive(value.toString())
    is ToolOutput.ErrorText -> JsonPrimitive(value)
    is ToolOutput.ErrorJson -> JsonPrimitive(value.toString())
    is ToolOutput.ExecutionDenied -> JsonPrimitive(reason ?: "Execution denied.")
    is ToolOutput.Multipart -> buildJsonArray {
        value.forEach { item ->
            when (item) {
                is ToolOutput.Multipart.Item.Text -> add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", item.text)
                    },
                )
                is ToolOutput.Multipart.Item.File ->
                    item.data.toSourceBlock(item.mediaType, item.filename, null, context)?.let { add(it) }
                is ToolOutput.Multipart.Item.Custom -> context.warnings +=
                    Warning.Other("unsupported custom tool content part")
            }
        }
    }
}

/**
 * The per-file options Anthropic defines, read from the part's `providerOptions.anthropic`.
 *
 * Each one exists because a document is more than its bytes to the citations machinery: [title] and
 * [context] are what the model cites the document AS, [citations] turns the cited-passage plumbing on
 * for this one document, and [containerUpload] diverts the file into the code-execution container
 * instead of the conversation. Ref: `anthropicFilePartProviderOptions`
 * (`anthropic-language-model-options.ts:31`).
 */
private class FilePartOptions(anthropic: JsonObject?) {
    val containerUpload: Boolean = anthropic?.boolOrNull("containerUpload") ?: false
    val citationsEnabled: Boolean =
        (anthropic?.get("citations") as? JsonObject)?.boolOrNull("enabled") ?: false
    val title: String? = anthropic?.stringOrNull("title")
    val context: String? = anthropic?.stringOrNull("context")
}

/**
 * An image, document or container-upload block.
 *
 * Four source shapes, and each exists because the alternative costs a round trip or a re-upload: a URL is
 * passed through where Anthropic fetches it itself, a vendor file id references a file already uploaded
 * rather than sending its bytes again on every turn, and inline text goes as a text document rather than
 * base64 of the same characters.
 *
 * Document blocks — never images — additionally carry the caller's [FilePartOptions]: `title` (falling
 * back to the part's filename), `context`, and `citations: {enabled: true}`, which is the switch that
 * makes Anthropic attach cited passages to the answer. Ref: `convert-to-anthropic-prompt.ts:100-137`.
 */
@OptIn(ExperimentalEncodingApi::class)
@Suppress("LongMethod")
private fun FileData.toSourceBlock(
    mediaType: String,
    filename: String?,
    cacheControl: JsonObject?,
    context: AnthropicPromptContext,
    providerOptions: Map<String, JsonObject>? = null,
): JsonObject? {
    val isImage = mediaType.startsWith("image/")
    if (mediaType == "application/pdf") context.betas += "pdfs-2024-09-25"
    val options = FilePartOptions(providerOptions?.get(ANTHROPIC_PROVIDER_ID))

    val source = when (this) {
        is FileData.Url -> buildJsonObject {
            put("type", "url")
            put("url", url)
        }
        is FileData.Bytes -> buildJsonObject {
            put("type", "base64")
            put("media_type", mediaType)
            put("data", Base64.encode(bytes))
        }
        is FileData.Reference -> {
            // Typed, not a warning: a file this conversation depends on cannot be expressed, and a
            // caller catching this can upload to Anthropic and retry, which a log line cannot offer.
            val fileId = reference[ANTHROPIC_PROVIDER_ID]
                ?: throw NoSuchProviderReferenceError(ANTHROPIC_PROVIDER_ID, reference)
            context.betas += "files-api-2025-04-14"
            // The container upload has its own block type with no source envelope: the file goes to
            // the code-execution container, not into the conversation.
            if (options.containerUpload) {
                if (cacheControl != null) {
                    context.warnings +=
                        Warning.Other("a container upload cannot carry a cache breakpoint")
                }
                return buildJsonObject {
                    put("type", "container_upload")
                    put("file_id", fileId)
                }
            }
            buildJsonObject {
                put("type", "file")
                put("file_id", fileId)
            }
        }
        // Always a document: an image has no text form, and `text/plain` is the only media type the
        // text source accepts.
        is FileData.Text -> buildJsonObject {
            put("type", "text")
            put("media_type", "text/plain")
            put("data", text)
        }
    }

    val isDocument = !isImage || this is FileData.Text
    return buildJsonObject {
        put("type", if (isDocument) "document" else "image")
        put("source", source)
        // Only an INLINE document carries the metadata: the reference sends a file-id document bare,
        // because the vendor already knows the file's name and citations there are not supported.
        if (isDocument && this@toSourceBlock !is FileData.Reference) {
            (options.title ?: filename)?.let { put("title", it) }
            options.context?.let { put("context", it) }
            if (options.citationsEnabled) {
                put("citations", buildJsonObject { put("enabled", true) })
            }
        }
        cacheControl?.let { put("cache_control", it) }
    }
}


private fun JsonElement?.asString(): String = this?.let { it.stringOrNull() }.orEmpty()
