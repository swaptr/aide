package com.sabreware.aide.aisdk.providers.cerebras

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Two corrections Cerebras' chat body needs, both of which fail quietly without it.
 *
 * **Validated against Cerebras' own API reference (`inference-docs.cerebras.ai/api-reference/chat-completions`,
 * checked 2026-09-01), not against the vendored port.** Both were confirmed there:
 *
 * - **`max_tokens` does not exist on this API** — only `max_completion_tokens`. It is not a deprecated
 *   alias; it is absent from the schema. This library writes `max_tokens` for every non-reasoning model,
 *   so without the move a caller's generation ceiling is simply not applied.
 * - **An assistant turn carries prior reasoning on `reasoning`**, not the `reasoning_content` that
 *   DeepSeek-style servers use and that this library replays by default. Replayed under the wrong name it
 *   is ignored, and the model re-derives a chain of thought the caller already paid for.
 *
 * Both live here rather than in a wrapper because they rewrite what the ENGINE wrote — the ceiling field
 * it chose, and the messages array, which is a reserved key a wrapper cannot touch.
 */
internal fun cerebrasRequestBody(body: JsonObject): JsonObject {
    var result = body
    (result["max_tokens"])?.let { ceiling ->
        result = JsonObject(result - "max_tokens" + ("max_completion_tokens" to ceiling))
    }
    val messages = result["messages"] as? JsonArray ?: return result
    if (messages.none { it.carriesReasoningContent() }) return result
    return JsonObject(result + ("messages" to JsonArray(messages.map { it.withCerebrasReasoning() })))
}

private fun JsonElement.carriesReasoningContent(): Boolean {
    val message = this as? JsonObject ?: return false
    return message["role"].isAssistant() && message["reasoning_content"] != null
}

/**
 * Renames one assistant turn's reasoning channel.
 *
 * A turn that already carries `reasoning` keeps it: the vendor's own name wins over the translation,
 * which is what lets a caller who knows the wire write it directly.
 */
private fun JsonElement.withCerebrasReasoning(): JsonElement {
    val message = this as? JsonObject ?: return this
    val reasoning = message["reasoning_content"] ?: return this
    if (!message["role"].isAssistant()) return this
    val without = message - "reasoning_content"
    return if (message["reasoning"] != null) JsonObject(without) else JsonObject(without + ("reasoning" to reasoning))
}

private fun JsonElement?.isAssistant(): Boolean =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content == "assistant"
