package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.MediaType
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Metadata key holding OpenRouter's `reasoning_details` block for this reasoning part. */
public const val REASONING_DETAILS_KEY: String = "reasoningDetails"

/** Metadata key holding a DeepSeek-R1 style `reasoning_content` string. */
public const val REASONING_CONTENT_KEY: String = "reasoningContent"

/**
 * Converts the neutral prompt into Chat Completions messages.
 *
 * Reasoning replay is the interesting part, and it has two shapes because the ecosystem does:
 *
 * - **`reasoning_details`** (OpenRouter) — typed blocks carrying a `signature` or encrypted `data`. The
 *   documented rule is that the whole consecutive sequence must match what the model produced, so the
 *   blocks are stored opaquely and sent back untouched. Rebuilding them is what produces the corrupt
 *   signatures and duplicated blocks other clients report.
 * - **`reasoning_content`** (DeepSeek and friends) — a plain string on its own channel, echoed back by
 *   servers that require it.
 *
 * A provider layer that models reasoning as display text can carry neither, which is the whole reason
 * this port exists.
 */
internal fun Prompt.toOpenAIMessages(
    providerId: String,
    systemRole: String = "system",
): List<OpenAIMessage> = toOpenAIMessagesWithExtras(providerId, systemRole).messages

/**
 * The encoded messages plus each one's vendor extras, index-aligned.
 *
 * Kept as a parallel list rather than folded into [OpenAIMessage] because the extras are arbitrary
 * vendor JSON — Alibaba's `cache_control`, DeepSeek's and Mistral's `prefix`, Moonshot's `partial` —
 * and giving the wire model a field per vendor is the per-vendor-class shape this whole file avoids.
 * A tool turn fans out to one message per result, so alignment is by position, not by source message.
 */
internal data class EncodedMessages(
    val messages: List<OpenAIMessage>,
    val extras: List<JsonObject?>,
)

/**
 * Message-level `providerOptions`, carried through to the wire.
 *
 * The part-level door has always been open — it is how a reasoning block keeps its signature. This is
 * the message-level one, and without it a vendor rule that lives on the MESSAGE is unreachable: it
 * cannot ride in the body (which never sees the prompt) and it cannot ride through a wrapper (which
 * cannot write `messages`, a reserved key). The same reserved-key discipline applies here — a caller
 * cannot overwrite the role, the content, or the ids that pair a call with its result.
 */
internal fun Prompt.toOpenAIMessagesWithExtras(
    providerId: String,
    systemRole: String = "system",
): EncodedMessages {
    val messages = mutableListOf<OpenAIMessage>()
    val extras = mutableListOf<JsonObject?>()
    encodeMessages(providerId, systemRole) { message, extra ->
        messages += message
        extras += extra
    }
    return EncodedMessages(messages, extras)
}

private inline fun Prompt.encodeMessages(
    providerId: String,
    systemRole: String,
    emit: (OpenAIMessage, JsonObject?) -> Unit,
) {
    forEach { message ->
        val extra = message.providerOptions?.get(providerId)
            ?.filterKeys { it !in RESERVED_MESSAGE_KEYS }
            ?.takeIf { it.isNotEmpty() }
            ?.let { JsonObject(it) }
        // A tool turn fans out to one message per result; each carries the turn's extras.
        buildList { encodeInto(providerId, systemRole, message) }.forEach { emit(it, extra) }
    }
}

/**
 * Keys a vendor option may not overwrite: they carry the turn itself, not a setting on it.
 *
 * `reasoning_content` is deliberately NOT among them, though it looks like it belongs. DeepSeek requires
 * the field present on EVERY assistant turn of a tool-calling conversation — including turns where the
 * model did not reason, where the value is an empty string — and answers a request that omits it with a
 * 400. The replay path cannot produce that: it derives the field from the reasoning a turn actually
 * carried, and drops it when empty. So the vendor supplies it here, which is the one door left. A caller
 * that overwrites its own replayed reasoning is overriding a value it owns.
 */
private val RESERVED_MESSAGE_KEYS = setOf("role", "content", "tool_calls", "tool_call_id")

private fun MutableList<OpenAIMessage>.encodeInto(
    providerId: String,
    systemRole: String,
    message: ModelMessage,
) {
    when (message) {
        is ModelMessage.System -> add(
            OpenAIMessage(role = systemRole, content = JsonPrimitive(message.content)),
        )

        is ModelMessage.User -> add(
            OpenAIMessage(role = "user", content = message.content.toContent(providerId)),
        )

        is ModelMessage.Assistant -> add(message.toOpenAI(providerId))

        // Each tool result is its OWN message; OpenAI has no multi-result tool turn. An approval
        // response has no representation on this wire at all — the decision was already applied by
        // whoever produced the result, so dropping it here loses nothing the model can act on.
        is ModelMessage.Tool -> message.content
            .filterIsInstance<ToolPart.Result>()
            .forEach { add(it.toOpenAI()) }
    }
}

private fun ModelMessage.Assistant.toOpenAI(providerId: String): OpenAIMessage {
    val text = content.filterIsInstance<AssistantPart.Text>().joinToString("") { it.text }
    val toolCalls = content.filterIsInstance<AssistantPart.ToolCall>().mapIndexed { index, call ->
        OpenAIToolCall(
            id = call.toolCallId,
            index = index,
            type = "function",
            function = OpenAIToolCallFunction(name = call.toolName, arguments = call.input),
            // Gemini issues a thought_signature per function call and rejects the NEXT turn if the call
            // is replayed without it. It was read off the stream and written nowhere, so anyone reaching
            // Gemini through OpenRouter, Vercel's gateway or a proxy hit that rejection on the second
            // round — the defect aisdk/DESIGN.md names as the reason this library exists.
            extraContent = call.providerOptions?.get(providerId)
                ?.get(THOUGHT_SIGNATURE_KEY)?.asStringOrNull()
                ?.let { OpenAIExtraContent(google = OpenAIGoogleExtraContent(thoughtSignature = it)) },
        )
    }

    val reasoning = content.filterIsInstance<AssistantPart.Reasoning>()
    val details = reasoning.mapNotNull { part ->
        part.providerOptions?.get(providerId)?.get(REASONING_DETAILS_KEY) as? JsonObject
    }
    val reasoningText = reasoning.mapNotNull { part ->
        part.providerOptions?.get(providerId)?.get(REASONING_CONTENT_KEY)
            ?.asStringOrNull()?.takeIf { it.isNotEmpty() }
    }.joinToString("\n").takeIf { it.isNotEmpty() }

    return OpenAIMessage(
        role = "assistant",
        content = text.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) },
        toolCalls = toolCalls.takeIf { it.isNotEmpty() },
        reasoningContent = reasoningText,
        // Verbatim and in order — never rebuilt from the display text.
        reasoningDetails = details.takeIf { it.isNotEmpty() },
    )
}

private fun ToolPart.Result.toOpenAI(): OpenAIMessage = OpenAIMessage(
    role = "tool",
    toolCallId = toolCallId,
    content = JsonPrimitive(output.toWireText()),
)

/**
 * Tool output as a single string.
 *
 * Chat Completions has no structured tool-result shape and no error flag, so a failure has to say so in
 * words. An `isError` boolean the wire cannot carry is worse than a prefix the model can read.
 */
private fun ToolOutput.toWireText(): String = when (this) {
    is ToolOutput.Text -> value
    is ToolOutput.Json -> value.toString()
    is ToolOutput.ErrorText -> "Error: $value"
    is ToolOutput.ErrorJson -> "Error: $value"
    is ToolOutput.ExecutionDenied -> "Error: ${reason ?: "Execution denied."}"
    is ToolOutput.Multipart -> value.joinToString("\n") { item ->
        when (item) {
            is ToolOutput.Multipart.Item.Text -> item.text
            is ToolOutput.Multipart.Item.File -> "[${item.mediaType}]"
            // A custom item's payload lives entirely in providerOptions, so there is nothing to
            // render — and inventing a placeholder would put words in the tool's mouth.
            is ToolOutput.Multipart.Item.Custom -> ""
        }
    }
}

/**
 * User content: a bare string when it is only text, an array of parts otherwise.
 *
 * The bare-string form matters for compatibility — several self-hosted servers (older llama.cpp builds,
 * some Ollama versions) reject the array form for text-only messages.
 */
private fun List<UserPart>.toContent(providerId: String): JsonElement {
    val onlyText = all { it is UserPart.Text }
    if (onlyText) {
        return JsonPrimitive(filterIsInstance<UserPart.Text>().joinToString("") { it.text })
    }
    return buildJsonArray {
        this@toContent.forEach { part ->
            when (part) {
                is UserPart.Text -> add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", part.text)
                    },
                )
                is UserPart.File -> add(part.toPart(providerId))
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun UserPart.File.toPart(providerId: String): JsonObject {
    // A vendor-held file is named, never re-sent. This is the whole reason FileData.Reference exists:
    // without it a large attachment is re-uploaded on every turn of a conversation that references it.
    (data as? FileData.Reference)?.reference?.get(providerId)?.let { fileId ->
        return buildJsonObject {
            put("type", "file")
            putJsonObject("file") { put("file_id", fileId) }
        }
    }
    // Text that is already characters goes as text. Base64-ing it would make the model decode a
    // document it could simply have read, and several servers reject a text/* file part outright.
    (data as? FileData.Text)?.let { inline ->
        return buildJsonObject {
            put("type", "text")
            put("text", inline.text)
        }
    }
    if (data is FileData.Bytes && mediaType.startsWith("text/")) {
        return buildJsonObject {
            put("type", "text")
            put("text", (data as FileData.Bytes).bytes.decodeToString())
        }
    }

    val bytes = (data as? FileData.Bytes)?.bytes
    val resolved = bytes?.let { MediaType.detect(it) } ?: mediaType
    val url = when (val d = data) {
        is FileData.Url -> d.url
        // OpenAI takes inline bytes as a data: URL rather than a separate field.
        is FileData.Bytes -> "data:$resolved;base64,${Base64.encode(d.bytes)}"
        // Both were returned above; a reference this provider has no id for cannot be sent at all.
        is FileData.Reference, is FileData.Text -> return buildJsonObject {
            put("type", "text")
            put("text", "[unavailable attachment: $mediaType]")
        }
    }
    return when {
        // Mistral's document path reads `document_url`; OpenAI's `file` shape is accepted there and
        // then ignored, which presents as a model that cannot see the attachment.
        resolved == "application/pdf" && providerId == MISTRAL_COMPAT_ID -> buildJsonObject {
            put("type", "document_url")
            put("document_url", url)
        }
        resolved.startsWith("image/") -> buildJsonObject {
            put("type", "image_url")
            putJsonObject("image_url") { put("url", url) }
        }
        // `input_audio` takes RAW base64 and a format name, so a URL cannot be expressed here at all —
        // substringAfter would hand the endpoint an https string labelled as audio bytes, which is
        // accepted and then transcribed as noise. A URL therefore goes down the file branch instead.
        resolved.startsWith("audio/") && bytes != null -> buildJsonObject {
            put("type", "input_audio")
            putJsonObject("input_audio") {
                put("data", Base64.encode(bytes))
                put("format", audioFormatFor(resolved))
            }
        }
        resolved.startsWith("video/") -> buildJsonObject {
            put("type", "video_url")
            putJsonObject("video_url") { put("url", url) }
        }
        else -> buildJsonObject {
            put("type", "file")
            putJsonObject("file") {
                put("file_data", url)
                filename?.let { put("filename", it) }
            }
        }
    }
}

/**
 * The vendor's own name for an audio container.
 *
 * Not the media subtype: `audio/mpeg` is the correct IANA type for an MP3 and the API's enum spells it
 * `mp3`, so passing the subtype through rejects a perfectly ordinary file.
 */
private fun audioFormatFor(mediaType: String): String = when (mediaType) {
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
    "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
    "audio/ogg" -> "ogg"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/webm" -> "webm"
    else -> mediaType.substringAfter('/')
}

/** Whether a message array carries anything at all — an empty prompt is a 400 on every server. */
internal fun List<OpenAIMessage>.orThrowIfEmpty(): List<OpenAIMessage> = also {
    require(isNotEmpty()) { "prompt produced no messages" }
}

internal fun JsonArray.asObjects(): List<JsonObject> = filterIsInstance<JsonObject>()

/**
 * A stored metadata value as a string, or null if it is not one.
 *
 * `toString().removeSurrounding("\"")` was the version this replaced: it turns a stored number into the
 * digits, an object into its JSON, and a string containing a quote into a mangled one.
 */
private fun JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val MISTRAL_COMPAT_ID = "mistral"
