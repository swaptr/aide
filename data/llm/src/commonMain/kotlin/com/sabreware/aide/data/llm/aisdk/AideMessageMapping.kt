package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.core.common.media.AttachmentBytesReader
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json

/**
 * AIDE's conversation, in the shape `:aisdk` speaks.
 *
 * The signature is the whole reason this function is careful. AIDE persists it in
 * [AidePart.Thinking.providerMetadata] — a redacted block is one of those with empty text and a payload,
 * not a part type of its own; `:aisdk` carries both opaquely under the provider's namespace, and the
 * provider puts them back on the wire in the shape that vendor demands. Nothing in between has to
 * understand them — which is exactly why nothing in between can lose them.
 *
 * The payload crosses unchanged in both directions — `:aisdk` uses the same provider-namespaced shape
 * AIDE persists, so this mapping never has to name a vendor or know what a signature is.
 */
@OptIn(ExperimentalEncodingApi::class)
public fun List<AideMessage>.toAisdkPrompt(
    readBytes: AttachmentBytesReader = AttachmentBytesReader { null },
): Prompt = buildList {
    this@toAisdkPrompt.forEach { message ->
        when (message.role) {
            AideRole.System -> {
                val text = message.parts.filterIsInstance<AidePart.Text>().joinToString("\n") { it.text }
                if (text.isNotEmpty()) add(ModelMessage.System(text))
            }

            AideRole.User -> {
                val parts = message.parts.mapNotNull { it.toUserPart(readBytes) }
                if (parts.isNotEmpty()) add(ModelMessage.User(parts))
            }

            AideRole.Model -> {
                val parts = message.parts.mapNotNull { it.toAssistantPart() }
                if (parts.isNotEmpty()) add(ModelMessage.Assistant(parts))
            }

            AideRole.Tool -> {
                val results = message.parts.filterIsInstance<AidePart.ToolResponse>()
                    .mapNotNull { it.toResultPart() }
                if (results.isNotEmpty()) add(ModelMessage.Tool(results))
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun AidePart.toUserPart(readBytes: AttachmentBytesReader): UserPart? = when (this) {
    // An empty text part is not a blank message, it is a vendor 400: Anthropic rejects an empty text
    // block outright. Emptiness reaches here from a whitespace-only compose box and from a tool round
    // that produced no prose, and neither is worth losing the turn over.
    is AidePart.Text -> UserPart.Text(text).takeIf { text.isNotBlank() }

    is AidePart.ImageBytes -> Base64.decode(bytesBase64).let {
        UserPart.File(FileData.Bytes(it), mediaType ?: it.sniffed(AideMessage.JPEG_MEDIA_TYPE))
    }

    // commonMain cannot touch the filesystem, so the platform supplies the reader. A file we cannot read
    // is dropped rather than sent as an empty attachment, which several vendors reject outright.
    is AidePart.ImageFile -> readBytes.read(path)?.let {
        UserPart.File(FileData.Bytes(it), mediaType ?: it.sniffed(AideMessage.JPEG_MEDIA_TYPE))
    }

    is AidePart.AudioFile -> readBytes.read(path)?.let {
        UserPart.File(FileData.Bytes(it), it.sniffed("audio/mpeg"))
    }

    is AidePart.DocumentFile -> readBytes.read(path)?.let {
        UserPart.File(FileData.Bytes(it), mediaType, filename = name)
    }

    // A tool response inside a user turn is AIDE's older shape; the Tool role carries it now.
    else -> null
}

/**
 * The declared media type, or what the bytes themselves say, or [fallback].
 *
 * The declared type is absent more often than it looks — a clipboard paste, a file picked off disk, a
 * legacy row persisted before the field existed. Defaulting straight to `image/jpeg` sends a PNG
 * mislabelled and the vendor answers "unsupported image format" for a format it supports perfectly well.
 */
private fun ByteArray.sniffed(fallback: String): String = MediaType.detect(this) ?: fallback

private fun AidePart.toAssistantPart(): AssistantPart? = when (this) {
    is AidePart.Text -> AssistantPart.Text(text).takeIf { text.isNotBlank() }

    // Straight across: both sides carry the same opaque, provider-namespaced shape, so nothing here
    // has to know what a signature is.
    is AidePart.Thinking -> AssistantPart.Reasoning(
        text = text,
        providerOptions = providerMetadata,
    )

    is AidePart.ToolCall -> AssistantPart.ToolCall(
        toolCallId = callId,
        toolName = name,
        input = argsJson,
        // Gemini puts its thoughtSignature HERE rather than on the thought.
        providerOptions = providerMetadata,
    )

    else -> null
}

/**
 * One tool result, structured where it is structured.
 *
 * [json] is a JSON document, so [ToolOutput.Text] double-encodes it — the model receives a string that
 * happens to contain braces, and the providers that flatten a text output lose the shape entirely. The
 * text arms stay as the fallback for a legacy row that no longer parses; a result is never dropped for
 * being unparseable, because the missing half of a call/result pair is a 400 of its own.
 *
 * A null [callId] cannot survive `normalizeForWire`, which pairs every result to a call and drops the
 * orphans — so it means the caller skipped that pass. Dropping the result surfaces as an unanswered call
 * the runtime's prompt validation names; sending an empty `tool_use_id` is an unconditional vendor 400
 * with nothing in it to read.
 */
private fun AidePart.ToolResponse.toResultPart(): ToolPart.Result? {
    val id = callId ?: return null
    val parsed = runCatching { RESULT_JSON.parseToJsonElement(json) }.getOrNull()
    return ToolPart.Result(
        toolCallId = id,
        toolName = name,
        output = when {
            error != null && parsed != null -> ToolOutput.ErrorJson(parsed)
            error != null -> ToolOutput.ErrorText(error!!)
            parsed != null -> ToolOutput.Json(parsed)
            else -> ToolOutput.Text(json)
        },
    )
}

private val RESULT_JSON = Json { ignoreUnknownKeys = true }
