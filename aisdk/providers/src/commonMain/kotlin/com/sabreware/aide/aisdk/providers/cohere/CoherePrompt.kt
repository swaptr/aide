package com.sabreware.aide.aisdk.providers.cohere

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A prompt as Cohere takes it: messages, plus the documents pulled out of them.
 *
 * The split is Cohere's, not ours. A non-image attachment is not a message part on this wire at all —
 * it belongs in the request's top-level `documents[]`, which is what makes the model cite it. Attaching
 * one to the user turn instead (which is what every OpenAI-shaped converter does) drops it, and with it
 * the entire RAG half of the product.
 */
internal data class CoherePrompt(
    val messages: JsonArray,
    val documents: List<JsonObject>,
)

@OptIn(ExperimentalEncodingApi::class)
internal fun Prompt.toCohere(): CoherePrompt {
    val documents = mutableListOf<JsonObject>()
    val messages = buildJsonArray {
        this@toCohere.forEach { message ->
            when (message) {
                is ModelMessage.System -> add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", message.content)
                    },
                )

                is ModelMessage.User -> add(message.content.toCohereUser(documents))

                is ModelMessage.Assistant -> add(message.toCohere())

                // Cohere has a tool role, and one message per result. An approval response has nowhere
                // to go on this wire — the decision was already applied by whoever produced the result,
                // so dropping it loses nothing the model could act on.
                is ModelMessage.Tool -> message.content
                    .filterIsInstance<ToolPart.Result>()
                    .forEach { result -> add(result.toCohere()) }
            }
        }
    }
    return CoherePrompt(messages = messages, documents = documents)
}

/**
 * One user turn, in whichever of Cohere's two content shapes it needs.
 *
 * A turn carrying an image must send its content as an array of parts; a text-only turn sends a bare
 * string, which is the form the non-vision models accept. Choosing the array unconditionally would 400
 * on `command-r`, and choosing the string unconditionally makes `command-a-vision` blind.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun List<UserPart>.toCohereUser(documents: MutableList<JsonObject>): JsonObject {
    val parts = mutableListOf<JsonObject>()
    var hasImage = false

    forEach { part ->
        when (part) {
            is UserPart.Text -> if (part.text.isNotEmpty()) {
                parts += buildJsonObject {
                    put("type", "text")
                    put("text", part.text)
                }
            }

            is UserPart.File -> if (part.mediaType.startsWith("image/")) {
                hasImage = true
                parts += part.toImagePart()
            } else {
                documents += buildJsonObject {
                    putJsonObject("data") {
                        put("text", part.documentText())
                        part.filename?.let { put("title", it) }
                    }
                }
            }
        }
    }

    return buildJsonObject {
        put("role", "user")
        if (hasImage) {
            putJsonArray("content") { parts.forEach { add(it) } }
        } else {
            put("content", parts.joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() })
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun UserPart.File.toImagePart(): JsonObject {
    val url = when (val source = data) {
        is FileData.Url -> source.url
        is FileData.Bytes -> "data:$mediaType;base64,${Base64.encode(source.bytes)}"
        // Cohere holds no files and has no id namespace, so a reference names something it cannot
        // fetch; text data is not an image at all. Both are refused rather than sent as an empty or
        // malformed `image_url`, which the API accepts and answers about a picture it never saw.
        is FileData.Reference -> throw UnsupportedFunctionalityError(
            "image file parts with provider references",
        )
        is FileData.Text -> throw UnsupportedFunctionalityError("image file parts with text data")
    }
    return buildJsonObject {
        put("type", "image_url")
        putJsonObject("image_url") {
            put("url", url)
            // Cohere's fidelity knob, `auto` | `low` | `high`. Per-part rather than per-call, because
            // one turn can carry a thumbnail worth skimming and a diagram worth reading closely.
            (providerOptions?.get(COHERE_PROVIDER_ID)?.get("detail") as? JsonPrimitive)
                ?.takeIf { it.isString }?.let { put("detail", it.content) }
        }
    }
}

private fun UserPart.File.documentText(): String = when (val source = data) {
    is FileData.Text -> source.text
    is FileData.Bytes -> source.bytes.decodeToString()
    // A document is inlined into the request body, so there is nothing to fetch it with here. The
    // runtime downloads a URL before the prompt reaches a provider; one arriving intact means that step
    // was skipped, and silently sending the URL as the document's TEXT would have the model summarize
    // a link.
    is FileData.Url -> throw UnsupportedFunctionalityError(
        "File URL data",
        "URLs should be downloaded before the prompt reaches the provider.",
    )
    is FileData.Reference -> throw UnsupportedFunctionalityError("file parts with provider references")
}

/**
 * One assistant turn.
 *
 * The text is kept even when the turn also carries tool calls, where the reference drops it. A model
 * that narrated before calling a tool said something the next round should still be able to read, and
 * Cohere accepts the pair — dropping it loses transcript for no wire reason.
 */
private fun ModelMessage.Assistant.toCohere(): JsonObject = buildJsonObject {
    put("role", "assistant")
    val text = content.filterIsInstance<AssistantPart.Text>().joinToString("") { it.text }
    if (text.isNotEmpty()) put("content", text)
    // The reasoning goes back as tool_plan, which is the only channel this wire replays it on —
    // discarded, the model re-derives its plan from scratch on every round of a tool loop.
    content.filterIsInstance<AssistantPart.Reasoning>()
        .joinToString("\n") { it.text }
        .takeIf { it.isNotEmpty() }
        ?.let { put("tool_plan", it) }
    val calls = content.filterIsInstance<AssistantPart.ToolCall>()
    if (calls.isNotEmpty()) {
        putJsonArray("tool_calls") {
            calls.forEach { call ->
                add(
                    buildJsonObject {
                        put("id", call.toolCallId)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", call.toolName)
                            put("arguments", call.input)
                        }
                    },
                )
            }
        }
    }
}

private fun ToolPart.Result.toCohere(): JsonObject = buildJsonObject {
    put("role", "tool")
    put("tool_call_id", toolCallId)
    put(
        "content",
        when (val out = output) {
            is ToolOutput.Text -> out.value
            is ToolOutput.Json -> out.value.toString()
            is ToolOutput.ErrorText -> "Error: ${out.value}"
            is ToolOutput.ErrorJson -> "Error: ${out.value}"
            is ToolOutput.ExecutionDenied -> "Error: ${out.reason ?: "Execution denied."}"
            is ToolOutput.Multipart -> out.value.joinToString("\n") { item ->
                when (item) {
                    is ToolOutput.Multipart.Item.Text -> item.text
                    is ToolOutput.Multipart.Item.File -> "[${item.mediaType}]"
                    // A custom item carries no content of its own — only providerOptions.
                    is ToolOutput.Multipart.Item.Custom -> ""
                }
            }
        },
    )
}
