package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.NoSuchProviderReferenceError
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.google.GOOGLE_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.google.stringOrNull
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** The `input` steps, the hoisted system instruction, and whatever had to be dropped on the way. */
internal data class ConvertedInteractionsInput(
    val input: List<InteractionsStep>,
    val systemInstruction: String?,
    val warnings: List<Warning>,
)

/**
 * The neutral prompt as Interactions `input` steps.
 *
 * A prior assistant turn fans out into one step per logical block: adjacent text and generated files
 * coalesce into one `model_output` step, the way the API emits them; a reasoning part becomes a
 * `thought` step carrying its signature; a tool call becomes a `function_call` step. A user turn — and
 * the tool-result turn that answers a round — is a `user_input` step, with each result a
 * `function_result` block INSIDE it, because that is a content block on this surface and not a step.
 *
 * **Compaction is the stateful mode's whole point.** With [previousInteractionId] set the server already
 * holds every turn up to that interaction, so an assistant turn stamped with that id is not sent again,
 * and the tool-result turn that answered its calls goes with it. Sending them anyway is not harmless: the
 * model would see its own answer twice. The one incoherent combination — a previous id AND `store:
 * false`, which names a record the caller said not to keep — is warned about and sent uncompacted, so
 * the server sees the whole history rather than a gap.
 */
internal fun Prompt.toGoogleInteractionsInput(
    previousInteractionId: String?,
    store: Boolean?,
    mediaResolution: String?,
): ConvertedInteractionsInput {
    val warnings = mutableListOf<Warning>()

    val incoherent = previousInteractionId != null && store == false
    if (incoherent) {
        warnings += Warning.Other(
            "google.interactions: providerOptions.google.previousInteractionId was set together with " +
                "store: false. These are incoherent (the prior interaction cannot be referenced when nothing " +
                "was stored on the server); the full history will be sent and previous_interaction_id will " +
                "still be emitted.",
        )
    }
    val messages = if (previousInteractionId != null && store != false) {
        compactForPreviousInteraction(previousInteractionId)
    } else {
        this
    }

    val systemTexts = mutableListOf<String>()
    val steps = mutableListOf<InteractionsStep>()
    val converter = FilePartConverter(warnings, mediaResolution)

    messages.forEach { message ->
        when (message) {
            is ModelMessage.System -> systemTexts += message.content

            is ModelMessage.User -> {
                val content = message.content.mapNotNull { part ->
                    when (part) {
                        is UserPart.Text -> textBlock(part.text)
                        is UserPart.File -> converter.toContent(part.data, part.mediaType, part.providerOptions)
                    }
                }
                val merged = mergeAdjacentText(content)
                if (merged.isNotEmpty()) steps += InteractionsStep(type = USER_INPUT, content = merged)
            }

            is ModelMessage.Assistant -> steps += assistantSteps(message, converter, warnings)

            is ModelMessage.Tool -> {
                val content = message.content.mapNotNull { part ->
                    when (part) {
                        is ToolPart.Result -> converter.toFunctionResult(part)
                        // Runtime-to-approver bookkeeping. The API has no representation for it and needs
                        // none: the model sees the call and, a turn later, its result or the denial.
                        is ToolPart.ApprovalResponse -> null
                    }
                }
                if (content.isNotEmpty()) steps += InteractionsStep(type = USER_INPUT, content = content)
            }
        }
    }

    return ConvertedInteractionsInput(
        input = steps,
        systemInstruction = systemTexts.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        warnings = warnings,
    )
}

/**
 * One assistant turn as steps.
 *
 * Text and files accumulate into a pending `model_output`; a reasoning part or a tool call flushes it
 * first, so step ORDER is the order the model produced the parts — which is what the API validates the
 * signatures against.
 */
private fun assistantSteps(
    message: ModelMessage.Assistant,
    converter: FilePartConverter,
    warnings: MutableList<Warning>,
): List<InteractionsStep> {
    val steps = mutableListOf<InteractionsStep>()
    var pending = mutableListOf<InteractionsContent>()
    fun flush() {
        if (pending.isNotEmpty()) {
            steps += InteractionsStep(type = MODEL_OUTPUT, content = pending)
            pending = mutableListOf()
        }
    }

    message.content.forEach { part ->
        when (part) {
            is AssistantPart.Text -> pending += textBlock(part.text)

            is AssistantPart.Reasoning -> {
                flush()
                steps += InteractionsStep(
                    type = THOUGHT,
                    signature = part.providerOptions.interactionsSignature(),
                    summary = listOf(textBlock(part.text)).takeIf { part.text.isNotEmpty() },
                )
            }

            is AssistantPart.File ->
                converter.toContent(part.data, part.mediaType, part.providerOptions)?.let { pending += it }

            is AssistantPart.ToolCall -> {
                flush()
                steps += InteractionsStep(
                    type = FUNCTION_CALL,
                    id = part.toolCallId,
                    name = part.toolName,
                    arguments = parseToolArguments(part.input),
                    signature = part.providerOptions.interactionsSignature(),
                )
            }

            // Runtime-to-approver bookkeeping; see the tool-turn arm.
            is AssistantPart.ApprovalRequest -> Unit

            is AssistantPart.ReasoningFile -> warnings += unsupportedAssistantPart("reasoning-file")

            // The agentic-video steps come back as the custom parts the outputs parser surfaced them as;
            // any other custom part is another provider's and has nothing this surface could replay.
            is AssistantPart.Custom -> {
                flush()
                val step = part.processingStep()
                if (step != null) {
                    steps += step
                } else {
                    warnings += Warning.Other(
                        "google.interactions: unsupported or invalid custom assistant content part " +
                            "\"${part.kind}\"; part dropped.",
                    )
                }
            }

            is AssistantPart.ToolResult -> warnings += unsupportedAssistantPart("tool-result")
        }
    }
    flush()
    return steps
}

private fun unsupportedAssistantPart(type: String): Warning =
    Warning.Other("google.interactions: unsupported assistant content part type \"$type\"; part dropped.")

/** A `processing_call` / `processing_result` step from its custom part, or null when the part is not one. */
private fun AssistantPart.Custom.processingStep(): InteractionsStep? {
    val google = providerOptions?.get(GOOGLE_PROVIDER_ID)
    val signature = providerOptions.interactionsSignature()
    return when (kind) {
        PROCESSING_CALL_KIND -> google?.stringOrNull(GOOGLE_INTERACTIONS_PROCESSING_ID_KEY)
            ?.let { InteractionsStep(type = PROCESSING_CALL, id = it, signature = signature) }
        PROCESSING_RESULT_KIND -> google?.stringOrNull(GOOGLE_INTERACTIONS_PROCESSING_CALL_ID_KEY)
            ?.let { InteractionsStep(type = PROCESSING_RESULT, callId = it, signature = signature) }
        else -> null
    }
}

/**
 * Drops the assistant turns the server already holds, and the tool results that answered them.
 *
 * A turn is dropped when ANY of its parts carries the linked interaction id; its tool-call ids are
 * remembered so the results in the following tool turn go too, because a result whose call is gone is a
 * malformed stream. A tool turn emptied that way is dropped whole.
 */
private fun Prompt.compactForPreviousInteraction(previousInteractionId: String): Prompt {
    val droppedCalls = mutableSetOf<String>()
    return mapNotNull { message ->
        when (message) {
            is ModelMessage.Assistant -> {
                val linked = message.content.any { it.providerOptions.interactionsId() == previousInteractionId }
                if (linked) {
                    message.content.filterIsInstance<AssistantPart.ToolCall>().forEach { droppedCalls += it.toolCallId }
                    null
                } else {
                    message
                }
            }

            is ModelMessage.Tool -> {
                val remaining = message.content.filter { it !is ToolPart.Result || it.toolCallId !in droppedCalls }
                if (remaining.isEmpty()) null else message.copy(content = remaining)
            }

            else -> message
        }
    }
}

/**
 * A tool call's input as the `arguments` object.
 *
 * The transport is a JSON string; the wire wants an object. Anything that parses to something other
 * than an object — a bare array, a number — is wrapped as `{"value": …}` rather than rejected, and text
 * that does not parse at all goes the same way, so a call the model made is replayed rather than lost.
 */
private fun parseToolArguments(input: String): JsonObject =
    when (val parsed = parseJsonElementOrNull(input)) {
        is JsonObject -> parsed
        null -> JsonObject(mapOf("value" to JsonPrimitive(input)))
        else -> JsonObject(mapOf("value" to parsed))
    }

/**
 * Collapses runs of adjacent text blocks into one, separated by a blank line.
 *
 * A text attachment arrives as a text block too, so a "please review:" followed by the document is two
 * blocks the model reads better as one. A block carrying annotations is left alone: its citations are
 * tied to character offsets in that block's own text.
 */
private fun mergeAdjacentText(content: List<InteractionsContent>): List<InteractionsContent> {
    if (content.size < 2) return content
    val out = mutableListOf<InteractionsContent>()
    // The run being merged is accumulated in one builder rather than re-concatenated per block: a turn
    // with many pasted attachments would otherwise copy the growing prefix once per attachment.
    var run: StringBuilder? = null
    fun flush() {
        run?.let { out += textBlock(it.toString()) }
        run = null
    }
    content.forEach { block ->
        if (block.type == TEXT && block.annotations == null) {
            val current = run
            if (current == null) run = StringBuilder(block.text.orEmpty()) else current.append("\n\n").append(block.text.orEmpty())
        } else {
            flush()
            out += block
        }
    }
    flush()
    return out
}

/**
 * Attachments and tool results, in the block shapes this surface takes.
 *
 * The block kind is the top-level media type — `image`, `audio`, `video`, with `application` and `text`
 * both landing on `document` — and the payload is inline base64, a URL the service fetches, or a Files
 * API URI the caller already holds. Text that is already characters goes as a text block: the model
 * reads it directly, and base64-ing it would make the model decode what it could have read.
 */
@OptIn(ExperimentalEncodingApi::class)
private class FilePartConverter(
    private val warnings: MutableList<Warning>,
    private val mediaResolution: String?,
) {

    fun toContent(data: FileData, mediaType: String, providerOptions: ProviderOptions? = null): InteractionsContent? {
        if (data is FileData.Text) return textBlock(data.text)
        val kind = when (mediaType.topLevel()) {
            "image" -> "image"
            "audio" -> "audio"
            "video" -> "video"
            "application", "text" -> "document"
            else -> null
        }
        if (kind == null) {
            warnings += Warning.Other(
                "google.interactions: unsupported file media type \"$mediaType\"; part dropped.",
            )
            return null
        }
        val resolution = mediaResolution?.takeIf { kind == "image" || kind == "video" }
        val processing = if (kind == "video") videoProcessing(providerOptions) else null
        return block(kind, data, mediaType).copy(resolution = resolution, processing = processing)
    }

    /**
     * `providerOptions.google.processing` on a video part, in the wire's spelling.
     *
     * `agentic` lets Gemini explore the timeline itself; `static` samples frames at a fixed rate, and its
     * configured form — clipped by `startOffset`/`endOffset`, at a chosen `fps` — is the one shape
     * re-spelled from camelCase. Anything else is warned about and dropped rather than sent for the API
     * to refuse.
     */
    private fun videoProcessing(providerOptions: ProviderOptions?): JsonElement? {
        val processing = providerOptions?.get(GOOGLE_PROVIDER_ID)?.get("processing")
            ?.takeIf { it !is JsonNull } ?: return null
        val literal = processing.stringOrNull()
        if (literal == "agentic" || literal == "static") return processing
        val config = processing as? JsonObject
        if (config?.stringOrNull("type") == "static") {
            return buildJsonObject {
                put("type", "static")
                config["startOffset"]?.takeIf { it.isNumber() }?.let { put("start_offset", it) }
                config["endOffset"]?.takeIf { it.isNumber() }?.let { put("end_offset", it) }
                config["fps"]?.takeIf { it.isNumber() }?.let { put("fps", it) }
            }
        }
        warnings += Warning.Other(
            "google.interactions: invalid providerOptions.google.processing on video file part; expected " +
                "\"agentic\", \"static\", or a static processing configuration. Option dropped.",
        )
        return null
    }

    /**
     * A tool result as a `function_result` block.
     *
     * A structured result is sent as its JSON text rather than as an object: `result` is a string or a
     * list of text/image blocks on this surface. Failure travels as `is_error` beside the text, which
     * is what stops a result that merely mentions an error from reading as one.
     */
    fun toFunctionResult(part: ToolPart.Result): InteractionsContent {
        val base = InteractionsContent(
            type = FUNCTION_RESULT,
            callId = part.toolCallId,
            name = part.toolName,
            signature = part.providerOptions.interactionsSignature(),
        )
        return when (val output = part.output) {
            is ToolOutput.Text -> base.copy(result = JsonPrimitive(output.value))
            is ToolOutput.Json -> base.copy(result = JsonPrimitive(output.value.toString()))
            is ToolOutput.ErrorText -> base.copy(isError = true, result = JsonPrimitive(output.value))
            is ToolOutput.ErrorJson -> base.copy(isError = true, result = JsonPrimitive(output.value.toString()))
            is ToolOutput.ExecutionDenied -> base.copy(
                isError = true,
                result = JsonPrimitive(output.reason ?: "Tool execution denied by user."),
            )
            is ToolOutput.Multipart -> base.copy(result = JsonArray(multipartBlocks(output).map(::encodeBlock)))
        }
    }

    /** `function_result.result` takes text and image blocks and nothing else; the rest is warned about. */
    private fun multipartBlocks(output: ToolOutput.Multipart): List<InteractionsContent> =
        output.value.mapNotNull { item ->
            when (item) {
                is ToolOutput.Multipart.Item.Text -> textBlock(item.text)
                is ToolOutput.Multipart.Item.File -> when {
                    item.mediaType.topLevel() != "image" -> {
                        warnings += Warning.Other(
                            "google.interactions: tool-result file with mediaType \"${item.mediaType}\" is not " +
                                "supported (Interactions `function_result.result` accepts only text and image " +
                                "content); part dropped.",
                        )
                        null
                    }
                    item.data is FileData.Text -> {
                        warnings += Warning.Other(
                            "google.interactions: tool-result image part with `data.type === \"text\"` is not " +
                                "representable as an image; part dropped.",
                        )
                        null
                    }
                    else -> block("image", item.data, item.mediaType)
                }
                is ToolOutput.Multipart.Item.Custom -> {
                    warnings += Warning.Other(
                        "google.interactions: tool-result content part type \"custom\" is not supported; part dropped.",
                    )
                    null
                }
            }
        }

    private fun block(kind: String, data: FileData, mediaType: String): InteractionsContent = when (data) {
        is FileData.Bytes -> InteractionsContent(
            type = kind,
            data = Base64.encode(data.bytes),
            mimeType = resolveFullMediaType(mediaType, data.bytes),
        )
        is FileData.Url -> InteractionsContent(type = kind, uri = data.url, mimeType = mediaType.takeIf { it.isFull() })
        is FileData.Reference -> InteractionsContent(
            type = kind,
            uri = data.reference[GOOGLE_PROVIDER_ID]
                ?: throw NoSuchProviderReferenceError(GOOGLE_PROVIDER_ID, data.reference),
            mimeType = mediaType.takeIf { it.isFull() },
        )
        is FileData.Text -> textBlock(data.text)
    }
}

/**
 * The full `type/subtype` the service requires, sniffed from the bytes where the caller gave a wildcard.
 *
 * A wildcard that cannot be resolved is refused rather than sent: the API rejects `image` with no
 * subtype, and a guessed one mislabels the file — which comes back as "unsupported format" for a file
 * it supports.
 */
private fun resolveFullMediaType(mediaType: String, bytes: ByteArray): String {
    if (mediaType.isFull()) return mediaType
    return MediaType.detect(bytes, mediaType.topLevel()) ?: throw UnsupportedFunctionalityError(
        functionality = "file of media type \"$mediaType\" must specify subtype since it could not be auto-detected",
    )
}

private fun encodeBlock(block: InteractionsContent): JsonElement =
    ProviderJson.encodeToJsonElement(InteractionsContent.serializer(), block)

private fun textBlock(text: String): InteractionsContent = InteractionsContent(type = TEXT, text = text)

private fun JsonElement.isNumber(): Boolean = this is JsonPrimitive && !isString && doubleOrNull != null

private fun String.topLevel(): String = substringBefore('/')

/** `type/subtype` with a real subtype — not `image`, not `image/`, not a wildcard. */
private fun String.isFull(): Boolean {
    val subtype = substringAfter('/', missingDelimiterValue = "")
    return subtype.isNotEmpty() && subtype != "*"
}

internal fun ProviderOptions?.interactionsSignature(): String? =
    this?.get(GOOGLE_PROVIDER_ID)?.stringOrNull(GOOGLE_INTERACTIONS_SIGNATURE_KEY)

internal fun ProviderOptions?.interactionsId(): String? =
    this?.get(GOOGLE_PROVIDER_ID)?.stringOrNull(GOOGLE_INTERACTIONS_ID_KEY)

internal const val USER_INPUT: String = "user_input"
internal const val MODEL_OUTPUT: String = "model_output"
internal const val THOUGHT: String = "thought"
internal const val FUNCTION_CALL: String = "function_call"
internal const val FUNCTION_RESULT: String = "function_result"
internal const val PROCESSING_CALL: String = "processing_call"
internal const val PROCESSING_RESULT: String = "processing_result"
internal const val TEXT: String = "text"

/** The custom-part kinds the agentic-video steps travel as: `{provider}.{step type}`. */
internal const val PROCESSING_CALL_KIND: String = "$GOOGLE_PROVIDER_ID.$PROCESSING_CALL"
internal const val PROCESSING_RESULT_KIND: String = "$GOOGLE_PROVIDER_ID.$PROCESSING_RESULT"
