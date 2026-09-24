package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolPart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One Open Responses extension: how a server's namespaced tool, items and events map onto the spec.
 *
 * The Open Responses spec reserves `type` values containing a colon — `acme:document_search` — for
 * implementors, so a server can serve a tool OpenAI never defined without colliding with one it might
 * define next month. This library knows nothing about what such an item means, and it must not guess:
 * the extension does, and this is where it says so. Three capabilities, each registered independently
 * and each a pair that only makes sense together — a wire type and the codec for it:
 *
 * - **Tool** — [toolType] with [encodeTool]: how a `Tool.ProviderDefined` whose id is this extension's
 *   [id] becomes an entry in the request's `tools` array (and, with [encodeToolChoice], how a
 *   `tool_choice` naming it is spelled).
 * - **Items** — [itemTypes] with [decodeItem]: how a completed output item of one of those types
 *   becomes spec content, in the JSON response and on `response.output_item.done` alike. With
 *   [encodeInputItem], how a call or result from history is put BACK on the wire when the original
 *   item is no longer available.
 * - **Events** — [eventTypes] with [decodeEvent]: how a namespaced streaming event becomes stream
 *   parts, with a state map that lives for one stream.
 *
 * Every decoded part is stamped with the extension's id and the item's id under the provider's
 * `providerMetadata`, and a `Content.Custom` of kind [OPEN_RESPONSES_EXTENSION_REPLAY_KIND] carrying
 * the whole original item is emitted ahead of them — that carrier is what lets the next turn replay the
 * item byte-for-byte without the extension having to reconstruct it from parts it may have simplified.
 *
 * Presence is the capability, exactly as in the reference: a null [encodeTool] means "this extension
 * does not encode a tool", and [OpenResponsesExtensionRegistry] refuses a half-declared pair rather
 * than letting a type with no codec silently match nothing. A codec that throws is treated as having
 * declined — on the request side the tool is dropped with a warning, on the stream side an error part
 * is emitted — so one misbehaving extension cannot take the whole call down with it.
 */
public class OpenResponsesExtension(
    /**
     * `<implementor>.<extension>` — also the [Tool.ProviderDefined.id] a caller declares to use the
     * extension's tool, which is what joins the tool to its codec.
     */
    public val id: String,
    /** The namespaced `type` of the tool on the wire, `<implementor>:<tool>`. Paired with [encodeTool]. */
    public val toolType: String? = null,
    /** The namespaced item types this extension decodes. Paired with [decodeItem]. */
    public val itemTypes: List<String>? = null,
    /** The namespaced streaming event types this extension decodes. Paired with [decodeEvent]. */
    public val eventTypes: List<String>? = null,
    /**
     * The tool entry's fields for a tool declared as ([name], `args`); [toolType] is added as `type`.
     * Null means the arguments cannot be encoded: the tool is dropped with a warning, and so is a
     * `tool_choice` that selected it.
     */
    public val encodeTool: (suspend (name: String, args: JsonObject) -> JsonObject?)? = null,
    /**
     * The fields of a `tool_choice` that selects this tool; [toolType] is added as `type`. Absent, the
     * choice is `{type}` alone. Null from a present encoder means the choice cannot be expressed, and
     * it is dropped with a warning.
     */
    public val encodeToolChoice: (suspend (name: String, args: JsonObject) -> JsonObject?)? = null,
    /**
     * Spec content for one completed item of one of [itemTypes]. Null means "nothing to surface" —
     * the item is then ignored, and no replay carrier is emitted for it.
     */
    public val decodeItem: (
        suspend (item: OpenResponsesExtensionItem, mode: OpenResponsesDecodeMode) -> List<Content>?
    )? = null,
    /**
     * The wire items for a call or result from HISTORY whose original item is not available — a
     * client-executed extension tool's call and the result the runtime produced for it. Every returned
     * item must be a complete extension item (type, id, status) of one of [itemTypes]; anything else
     * is refused, and the part is dropped with a warning. One item is a one-element list.
     */
    public val encodeInputItem: (
        suspend (part: OpenResponsesExtensionInputPart, tool: Tool.ProviderDefined) -> List<JsonObject>?
    )? = null,
    /**
     * Stream parts for one namespaced event. [OpenResponsesExtensionEvent] carries the whole frame;
     * `state` is the extension's own scratch space and lives exactly as long as the stream. The parts
     * must be the streaming kinds — a text, reasoning or tool-input block, a call, a result, a source, a
     * custom part — never `StreamStart`, `Finish`, `Raw` or `Error`, which the model owns.
     */
    public val decodeEvent: (
        suspend (event: OpenResponsesExtensionEvent, state: MutableMap<String, Any?>) -> List<StreamPart>?
    )? = null,
)

/** Whether an item is being decoded from the JSON response or from a completed stream item. */
public enum class OpenResponsesDecodeMode { Generate, Stream }

/**
 * A completed namespaced output item: a `type` containing a colon, a string `id` and a string
 * `status`, plus the untouched object for the extension to read the rest from.
 *
 * The three fields are the reference's own predicate for "this is an extension item"; an object with a
 * namespaced type that lacks either is not one, and is ignored rather than handed to a codec that will
 * have to guess.
 */
public class OpenResponsesExtensionItem private constructor(
    public val type: String,
    public val id: String,
    public val status: String,
    /** The whole item as the server sent it — [type], [id] and [status] included. */
    public val json: JsonObject,
) {

    public companion object {

        /** The item [json] describes, or null where it is not a complete extension item. */
        public fun from(json: JsonObject): OpenResponsesExtensionItem? {
            val type = json.namespacedType() ?: return null
            val id = json.stringField("id") ?: return null
            val status = json.stringField("status") ?: return null
            return OpenResponsesExtensionItem(type, id, status, json)
        }
    }
}

/**
 * A namespaced streaming event: a `type` containing a colon and a numeric `sequence_number`, plus the
 * untouched frame.
 */
public class OpenResponsesExtensionEvent private constructor(
    public val type: String,
    public val sequenceNumber: Int,
    /** The whole frame as the server sent it. */
    public val json: JsonObject,
) {

    public companion object {

        /** The event [json] describes, or null where it is not an extension event. */
        public fun from(json: JsonObject): OpenResponsesExtensionEvent? {
            val type = json.namespacedType() ?: return null
            val sequence = (json["sequence_number"] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.intOrNull
                ?: return null
            return OpenResponsesExtensionEvent(type, sequence, json)
        }
    }
}

/**
 * A history part an extension may have to put back on the wire: the call the model made through its
 * tool, or the result the runtime produced for it.
 *
 * Two arms rather than the spec's three classes because a result is a result wherever the prompt
 * files it — an assistant-turn `AssistantPart.ToolResult` and a tool-turn `ToolPart.Result` carry the
 * same fields, and an extension should not have to care which turn the runtime chose.
 */
public sealed interface OpenResponsesExtensionInputPart {

    /** The tool the part belongs to, by the name the caller gave it. */
    public val toolName: String

    public data class Call(val call: AssistantPart.ToolCall) : OpenResponsesExtensionInputPart {
        override val toolName: String get() = call.toolName
    }

    public data class Result(val result: ToolPart.Result) : OpenResponsesExtensionInputPart {
        override val toolName: String get() = result.toolName
    }
}

/**
 * The extensions one Open Responses endpoint was configured with, indexed by every key the model has
 * to look one up by.
 *
 * Built once per provider and validated on construction, because every rule here is one that would
 * otherwise fail silently at run time: a tool type outside the extension's own namespace would match
 * items belonging to someone else's extension; two extensions claiming one item type would decode it
 * twice or, worse, once each depending on registration order; a type declared without its codec would
 * match and then do nothing. Each is an `IllegalArgumentException` at construction, worded as the
 * reference words it.
 */
public class OpenResponsesExtensionRegistry(extensions: List<OpenResponsesExtension> = emptyList()) {

    public val byExtensionId: Map<String, OpenResponsesExtension>
    public val byProviderToolId: Map<String, OpenResponsesExtension>
    public val byToolType: Map<String, OpenResponsesExtension>
    public val byItemType: Map<String, OpenResponsesExtension>
    public val byEventType: Map<String, OpenResponsesExtension>

    init {
        val ids = mutableMapOf<String, OpenResponsesExtension>()
        val toolIds = mutableMapOf<String, OpenResponsesExtension>()
        val toolTypes = mutableMapOf<String, OpenResponsesExtension>()
        val itemTypes = mutableMapOf<String, OpenResponsesExtension>()
        val eventTypes = mutableMapOf<String, OpenResponsesExtension>()

        extensions.forEach { extension ->
            val separator = extension.id.indexOf('.')
            require(separator > 0) {
                "Open Responses extension ID ${extension.id} must use <implementor>.<extension> format."
            }
            val namespace = extension.id.substring(0, separator)
            ids.register(extension.id, extension, "id")

            registerTool(extension, namespace, toolIds, toolTypes)
            registerItems(extension, namespace, itemTypes)
            registerEvents(extension, namespace, eventTypes)

            require(extension.encodeTool != null || extension.decodeItem != null || extension.decodeEvent != null) {
                "Open Responses extension ${extension.id} must register a tool, item, or event capability."
            }
        }

        byExtensionId = ids
        byProviderToolId = toolIds
        byToolType = toolTypes
        byItemType = itemTypes
        byEventType = eventTypes
    }

    /** True for the registry an endpoint with no extensions gets; every lookup is then a miss. */
    public val isEmpty: Boolean get() = byExtensionId.isEmpty()

    private fun registerTool(
        extension: OpenResponsesExtension,
        namespace: String,
        toolIds: MutableMap<String, OpenResponsesExtension>,
        toolTypes: MutableMap<String, OpenResponsesExtension>,
    ) {
        val toolType = extension.toolType
        val hasEncoder = extension.encodeTool != null
        require((toolType != null) == hasEncoder) {
            "Open Responses extension ${extension.id} must provide toolType and encodeTool together."
        }
        require(extension.encodeToolChoice == null || hasEncoder) {
            "Open Responses extension ${extension.id} cannot provide encodeToolChoice without toolType and encodeTool."
        }
        if (toolType == null) return
        requireNamespaced(extension.id, namespace, toolType, "toolType")
        toolIds.register(extension.id, extension, "provider-tool id")
        toolTypes.register(toolType, extension, "toolType")
    }

    private fun registerItems(
        extension: OpenResponsesExtension,
        namespace: String,
        itemTypes: MutableMap<String, OpenResponsesExtension>,
    ) {
        val types = extension.itemTypes
        val hasDecoder = extension.decodeItem != null
        require((types != null) == hasDecoder) {
            "Open Responses extension ${extension.id} must provide itemTypes and decodeItem together."
        }
        require(extension.encodeInputItem == null || hasDecoder) {
            "Open Responses extension ${extension.id} cannot provide encodeInputItem without itemTypes and decodeItem."
        }
        if (types == null) return
        require(types.isNotEmpty()) {
            "Open Responses extension ${extension.id} must register at least one item type."
        }
        types.forEach { type ->
            requireNamespaced(extension.id, namespace, type, "itemTypes")
            itemTypes.register(type, extension, "item type")
        }
    }

    private fun registerEvents(
        extension: OpenResponsesExtension,
        namespace: String,
        eventTypes: MutableMap<String, OpenResponsesExtension>,
    ) {
        val types = extension.eventTypes
        require((types != null) == (extension.decodeEvent != null)) {
            "Open Responses extension ${extension.id} must provide eventTypes and decodeEvent together."
        }
        if (types == null) return
        require(types.isNotEmpty()) {
            "Open Responses extension ${extension.id} must register at least one event type."
        }
        types.forEach { type ->
            requireNamespaced(extension.id, namespace, type, "eventTypes")
            eventTypes.register(type, extension, "event type")
        }
    }

    /**
     * A wire type has to sit in the extension's OWN namespace — the part of its id before the dot.
     * Without this rule `acme.search` could claim `other:search_call` and decode another implementor's
     * items as its own.
     */
    private fun requireNamespaced(extensionId: String, namespace: String, type: String, field: String) {
        require(type.contains(':') && type.substringBefore(':') == namespace) {
            "Open Responses extension $extensionId has invalid $field value $type. " +
                "Extension wire types must use the $namespace: namespace."
        }
    }

    private fun MutableMap<String, OpenResponsesExtension>.register(
        key: String,
        extension: OpenResponsesExtension,
        field: String,
    ) {
        val existing = this[key]
        require(existing == null) {
            "Open Responses extension ${extension.id} cannot register $field $key " +
                "because it is already registered by ${existing?.id}."
        }
        this[key] = extension
    }

    public companion object {
        /** No extensions — what OpenAI's own endpoint, and every other Responses vendor, runs with. */
        public val Empty: OpenResponsesExtensionRegistry = OpenResponsesExtensionRegistry()
    }
}

/**
 * The `Content.Custom.kind` of the replay carrier emitted ahead of every decoded extension item.
 *
 * Its `providerMetadata`, under the provider's key, holds `openResponsesExtension: {id, item}` — the
 * extension that decoded the item and the item itself, verbatim. Handed back as an assistant part on
 * the next turn, the item is replayed as it arrived; the parts decoded from it, which carry only
 * `{id, itemId}`, are recognized as already replayed and skipped.
 */
public const val OPEN_RESPONSES_EXTENSION_REPLAY_KIND: String = "open-responses.extension-replay"

/** The key under the provider's `providerMetadata` / `providerOptions` that extension bookkeeping lives at. */
public const val OPEN_RESPONSES_EXTENSION_KEY: String = "openResponsesExtension"

/** The `type` of an object, when it is a string containing a colon — the spec's mark of an extension. */
internal fun JsonObject.namespacedType(): String? = stringField("type")?.takeIf { it.contains(':') }

internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
