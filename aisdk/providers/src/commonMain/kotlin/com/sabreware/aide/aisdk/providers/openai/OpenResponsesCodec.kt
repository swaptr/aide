package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.Warning
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// The glue between an OpenResponsesExtension's codecs and the Responses model — everything that has to
// hold for extensions across the request builder, the two output mappers and the prompt converter, kept
// in one place so the four cannot drift: what a decoded item is stamped with, what the replay carrier
// looks like, and what an encoder's failure means.

/**
 * A completed extension item as spec content: the replay carrier first, then the extension's own parts,
 * each stamped with `{id, itemId}` under [namespace].
 *
 * Null where no extension claims the item's type, or where the extension had nothing to surface. In
 * both cases nothing is emitted — not even the carrier — because a carrier with no parts would replay
 * an item the caller never saw.
 */
internal suspend fun OpenResponsesExtensionRegistry.decodeExtensionItem(
    item: OpenResponsesExtensionItem,
    mode: OpenResponsesDecodeMode,
    namespace: String,
): List<Content>? {
    val extension = byItemType[item.type] ?: return null
    val decoded = extension.decodeItem?.invoke(item, mode) ?: return null
    val reference = buildJsonObject {
        put("id", extension.id)
        put("itemId", item.id)
    }
    return listOf(replayCarrier(extension, item, namespace)) +
        decoded.map { it.withExtensionMetadata(namespace, reference) }
}

private fun replayCarrier(
    extension: OpenResponsesExtension,
    item: OpenResponsesExtensionItem,
    namespace: String,
): Content.Custom = Content.Custom(
    kind = OPEN_RESPONSES_EXTENSION_REPLAY_KIND,
    providerMetadata = mapOf(
        namespace to buildJsonObject {
            putJsonObject(OPEN_RESPONSES_EXTENSION_KEY) {
                put("id", extension.id)
                put("item", item.json)
            }
        },
    ),
)

/** The part with `openResponsesExtension` merged into its metadata under [namespace], the rest intact. */
private fun Content.withExtensionMetadata(namespace: String, reference: JsonObject): Content {
    val existing = providerMetadata.orEmpty()
    val own = existing[namespace].orEmpty()
    val merged: ProviderMetadata = existing + (namespace to JsonObject(own + (OPEN_RESPONSES_EXTENSION_KEY to reference)))
    return withProviderMetadata(merged)
}

private fun Content.withProviderMetadata(metadata: ProviderMetadata): Content = when (this) {
    is Content.Text -> copy(providerMetadata = metadata)
    is Content.Reasoning -> copy(providerMetadata = metadata)
    is Content.File -> copy(providerMetadata = metadata)
    is Content.ReasoningFile -> copy(providerMetadata = metadata)
    is Content.ToolCall -> copy(providerMetadata = metadata)
    is Content.ToolResult -> copy(providerMetadata = metadata)
    is Content.ToolApprovalRequest -> copy(providerMetadata = metadata)
    is Content.Source.Url -> copy(providerMetadata = metadata)
    is Content.Source.Document -> copy(providerMetadata = metadata)
    is Content.Custom -> copy(providerMetadata = metadata)
}

/**
 * The `tools` entry for an extension tool: the encoder's fields with the extension's [OpenResponsesExtension.toolType]
 * as `type` — added LAST, so an encoder cannot mislabel its own tool.
 *
 * Null where the extension declined or threw. A throwing encoder is a declined one rather than a
 * failed request: the caller gets the same warning either way, and a bug in one extension does not take
 * the rest of the call's tools with it.
 */
internal suspend fun OpenResponsesExtension.encodeToolEntry(tool: Tool.ProviderDefined): JsonObject? {
    val type = toolType ?: return null
    val fields = quietly { encodeTool?.invoke(tool.name, tool.args) } ?: return null
    return buildJsonObject {
        fields.forEach { (key, value) -> put(key, value) }
        put("type", type)
    }
}

/**
 * The `tool_choice` that selects an extension tool, or null with a warning where the extension has an
 * encoder for it and that encoder declined.
 *
 * No encoder means the bare `{type}` — the reference's default — because most tools need nothing more
 * to be named.
 */
internal suspend fun OpenResponsesExtension.encodeToolChoiceEntry(
    tool: Tool.ProviderDefined,
    warnings: MutableList<Warning>,
): JsonObject? {
    val type = toolType ?: return null
    val encoder = encodeToolChoice
    val fields = if (encoder == null) JsonObject(emptyMap()) else quietly { encoder(tool.name, tool.args) }
    if (fields == null) {
        warnings += Warning.Unsupported(
            feature = "toolChoice",
            details = "The open-responses extension for ${tool.id} could not encode a tool choice selecting it; dropped.",
        )
        return null
    }
    return buildJsonObject {
        fields.forEach { (key, value) -> put(key, value) }
        put("type", type)
    }
}

/**
 * History put back on the wire by the extension that owns [tool], or null where it cannot be.
 *
 * Every returned item has to be a complete extension item of one of the extension's own item types —
 * the reference's rule, and the right one: an encoder that returns a `function_call` for its own tool
 * would have the next response answered under a type nothing here decodes.
 */
internal suspend fun OpenResponsesExtensionRegistry.encodeExtensionInputPart(
    part: OpenResponsesExtensionInputPart,
    tool: Tool.ProviderDefined,
): List<JsonObject>? {
    val extension = byProviderToolId[tool.id] ?: return null
    val encode = extension.encodeInputItem ?: return null
    val itemTypes = extension.itemTypes ?: return null
    val items = quietly { encode(part, tool) } ?: return null
    if (items.isEmpty()) return null
    val valid = items.all { json ->
        OpenResponsesExtensionItem.from(json)?.let { it.type in itemTypes } ?: false
    }
    return items.takeIf { valid }
}

/**
 * What a history part's `openResponsesExtension` bookkeeping says to do with it.
 *
 * [Item] is the carrier: replay the original item. [Consumed] is a part decoded FROM an item — it
 * carries only `{id, itemId}` and its carrier has already replayed it, so it is skipped rather than
 * re-encoded. Null means the part is not an extension's, or names an extension this endpoint does not
 * have, and takes the ordinary path.
 */
internal sealed interface ExtensionReplay {
    data class Item(val item: OpenResponsesExtensionItem) : ExtensionReplay
    data object Consumed : ExtensionReplay
}

internal fun OpenResponsesExtensionRegistry.replayOf(bookkeeping: JsonObject?): ExtensionReplay? {
    val data = bookkeeping ?: return null
    val id = data.stringField("id") ?: return null
    val extension = byExtensionId[id] ?: return null
    val item = (data["item"] as? JsonObject)?.let { OpenResponsesExtensionItem.from(it) }
    if (item != null && extension.itemTypes?.contains(item.type) == true) return ExtensionReplay.Item(item)
    return if (data.stringField("itemId") != null) ExtensionReplay.Consumed else null
}

/**
 * Runs a codec, treating anything it throws as "declined".
 *
 * Cancellation is rethrown first: an extension is caller code running inside the call's coroutine, and
 * a cancelled call must actually cancel rather than continue with the tool quietly missing.
 */
private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
    null
}
