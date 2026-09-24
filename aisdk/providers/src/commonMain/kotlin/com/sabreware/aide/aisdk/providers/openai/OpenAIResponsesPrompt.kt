package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.isRuntimeMinted
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The `input` array plus anything the conversion could not carry across. */
internal data class OpenAIResponsesInput(
    val items: List<JsonObject>,
    val warnings: List<Warning>,
)

/**
 * How this conversation is stored on OpenAI's side, which changes what a replayed turn may contain.
 *
 * With [store] true, OpenAI keeps every item and a replay refers back to it by `item_reference` — small
 * payloads, but the conversation only exists on their servers. With [store] false nothing is kept, so a
 * reasoning item has to carry its own `encrypted_content` back, which is the whole reason the
 * `include: ["reasoning.encrypted_content"]` opt-in exists.
 */
internal data class OpenAIReplayContext(
    /** `system`, `developer`, or null to drop system messages entirely. */
    val systemRole: String?,
    val store: Boolean,
    val mapping: ToolNameMapping,
    val providerToolsPresent: Set<String>,
    val customToolNames: Set<String>,
    /** A server-side conversation already holds these items; resending one is a "Duplicate item" 400. */
    val hasConversation: Boolean = false,
    /** Chaining from a previous response id: the same items are already in that chain. */
    val hasPreviousResponseId: Boolean = false,
    /** Send a non-image, non-PDF attachment anyway, for a model whose media support outran this table. */
    val passThroughUnsupportedFiles: Boolean = false,
    /**
     * Where replayed items carry their ids and payloads — the serving vendor's key, with canonical
     * `openai` always read underneath it. A prompt may mix turns served by OpenAI and by an Azure
     * deployment; both must replay.
     */
    val namespace: String = OPENAI_PROVIDER_ID,
    /** The endpoint's Open Responses extensions, which replay their own items — see [OpenResponsesExtension]. */
    val extensions: OpenResponsesExtensionRegistry = OpenResponsesExtensionRegistry.Empty,
    /** The call's provider-defined tools by name, so a history part can be joined to the extension that owns it. */
    val providerToolsByName: Map<String, Tool.ProviderDefined> = emptyMap(),
    /** See [ResponsesQuirks.explicitMessageItemType]. */
    val explicitMessageItemType: Boolean = false,
    /** See [ResponsesQuirks.strictResponseInput]. */
    val strictResponseInput: Boolean = false,
    /** See [ResponsesQuirks.defaultImageDetail]. */
    val defaultImageDetail: String? = null,
) {

    /** A part-level string option under this context's namespace, `openai` as the fallback. */
    fun partString(options: ProviderOptions?, key: String): String? =
        options.responsesString(namespace, key)

    /** A part-level boolean option under this context's namespace, `openai` as the fallback. */
    fun partBoolean(options: ProviderOptions?, key: String): Boolean? =
        (options?.get(namespace)?.get(key) as? JsonPrimitive)?.booleanOrNull
            ?: (options?.takeIf { namespace != OPENAI_PROVIDER_ID }?.get(OPENAI_PROVIDER_ID)?.get(key) as? JsonPrimitive)
                ?.booleanOrNull

    /** A part-level object option under this context's namespace, `openai` as the fallback. */
    fun partObject(options: ProviderOptions?, key: String): JsonObject? =
        options?.get(namespace)?.get(key) as? JsonObject
            ?: options?.takeIf { namespace != OPENAI_PROVIDER_ID }?.get(OPENAI_PROVIDER_ID)?.get(key) as? JsonObject

    /** A file reference under this context's namespace, `openai` as the fallback. */
    fun fileReference(reference: Map<String, String>): String? =
        reference[namespace] ?: reference[OPENAI_PROVIDER_ID]
}

/**
 * Converts the neutral [Prompt] into the Responses API's `input` items.
 *
 * **The part that matters.** A reasoning item is replayed with its `id` and its `encrypted_content`
 * intact, in the position it originally occupied among the tool calls. OpenAI matches the item to the
 * chain of thought it holds; a replay missing either one is not rejected — which is worse than
 * Anthropic's outright 400, because nothing tells the caller anything went wrong. The model simply
 * starts thinking from scratch, and the bill arrives with the reasoning tokens counted again.
 *
 * Two rules follow from that and are enforced here:
 *
 * - **Summaries for one item are grouped back into one item.** OpenAI emits several `summary_text`
 *   parts under a single reasoning id; the neutral layer sees them as several `Reasoning` parts. Sending
 *   them back as several reasoning items claims a chain of thought the model never had.
 * - **A reasoning item with no way to identify itself is dropped, with a warning.** Under `store: false`
 *   an item without `encrypted_content` carries nothing; sending it is a request for the model to
 *   reconstruct from a summary it did not write. This is the exact counterpart of the Anthropic codec's
 *   refusal to replay an unsigned thinking block.
 *
 * An Open Responses extension's items take precedence over all of it: a part carrying the extension's
 * replay bookkeeping is the extension's to replay (the carrier's item goes out verbatim, once; a part
 * decoded from it is skipped), and a call or result of an extension tool declared on this call is
 * encoded by that extension rather than as a `function_call`.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
internal suspend fun Prompt.toOpenAIResponsesInput(context: OpenAIReplayContext): OpenAIResponsesInput {
    val items = mutableListOf<JsonObject>()
    val warnings = mutableListOf<Warning>()
    val replayedExtensionItems = mutableSetOf<String>()
    // Calls a hosted program made, by call id: a denied result for one of them has no wire form.
    val programmaticToolCallIds = mutableSetOf<String>()

    forEach { message ->
        when (message) {
            is ModelMessage.System -> when (val role = context.systemRole) {
                null -> warnings += Warning.Other("System messages are removed for this model.")
                else -> items += buildJsonObject {
                    if (context.explicitMessageItemType) put("type", "message")
                    put("role", role)
                    put("content", message.content)
                }
            }

            is ModelMessage.User -> items += buildJsonObject {
                if (context.explicitMessageItemType) put("type", "message")
                put("role", "user")
                putJsonArray("content") {
                    message.content.forEachIndexed { index, part ->
                        add(part.toInputPart(index, context))
                    }
                }
            }

            is ModelMessage.Assistant -> {
                // Reasoning summaries that share an item id are folded back into the one item they came
                // from, so the index of the slot has to survive the loop.
                val reasoningSlots = mutableMapOf<String, Int>()
                val reasoningState = mutableMapOf<String, ReasoningItem>()

                message.content.forEach { part ->
                    if (context.replayExtension(part.providerOptions, items, replayedExtensionItems)) return@forEach
                    if (!context.extensions.isEmpty &&
                        context.encodeExtensionHistory(part.asExtensionInput(), items, warnings)
                    ) {
                        return@forEach
                    }
                    when (part) {
                        is AssistantPart.Text -> {
                            val id = context.partString(part.providerOptions, OPENAI_ITEM_ID_KEY)
                            when {
                                context.hasConversation && id != null -> Unit
                                // The spec's own input schemas, which an open-responses server may
                                // validate against — see ResponsesQuirks.strictResponseInput.
                                context.strictResponseInput -> items += strictAssistantMessage(id, part.text)
                                context.store && id != null -> items += itemReference(id)
                                else -> items += buildJsonObject {
                                    if (context.explicitMessageItemType) put("type", "message")
                                    put("role", "assistant")
                                    putJsonArray("content") {
                                        add(
                                            buildJsonObject {
                                                put("type", "output_text")
                                                put("text", part.text)
                                            },
                                        )
                                    }
                                    id?.let { put("id", it) }
                                    context.partString(part.providerOptions, "phase")
                                        ?.let { put("phase", it) }
                                }
                            }
                        }

                        is AssistantPart.Reasoning -> {
                            val itemId = context.partString(part.providerOptions, OPENAI_ITEM_ID_KEY)
                            val encrypted =
                                context.partString(part.providerOptions, OPENAI_ENCRYPTED_REASONING_KEY)
                            when {
                                // Already in the chain OpenAI is holding; resending duplicates it.
                                (context.hasConversation || context.hasPreviousResponseId) && itemId != null -> Unit

                                context.store && itemId != null -> {
                                    // One reference per item, however many summaries it produced.
                                    if (reasoningSlots.put(itemId, items.size) == null) {
                                        items += itemReference(itemId)
                                    }
                                }

                                itemId != null || encrypted != null -> {
                                    val key = itemId ?: encrypted!!
                                    val slot = reasoningSlots[key]
                                    val state = reasoningState.getOrPut(key) { ReasoningItem(itemId) }
                                    if (part.text.isNotEmpty()) state.summaries += part.text
                                    // The last part carrying encrypted content wins: OpenAI attaches it
                                    // to the item, not to an individual summary.
                                    if (encrypted != null) state.encrypted = encrypted
                                    if (slot == null) {
                                        reasoningSlots[key] = items.size
                                        items += state.toJson()
                                    } else {
                                        items[slot] = state.toJson()
                                    }
                                }

                                else -> warnings += Warning.Other(
                                    "A reasoning part with neither an OpenAI item id nor encrypted " +
                                        "content cannot be replayed and was dropped.",
                                )
                            }
                        }

                        is AssistantPart.ToolCall -> appendToolCall(items, part, context, programmaticToolCallIds)

                        is AssistantPart.ToolResult -> {
                            val denied = part.output is ToolOutput.ExecutionDenied
                            val itemId = context.partString(part.providerOptions, OPENAI_ITEM_ID_KEY)
                                ?: part.toolCallId
                            when {
                                // A denied approval has no item on OpenAI's side to point at.
                                denied || context.hasConversation -> Unit
                                context.store -> items += itemReference(itemId)
                                else -> warnings += Warning.Other(
                                    "Results from the OpenAI tool ${part.toolName} are held by OpenAI " +
                                        "and cannot be replayed while store is false.",
                                )
                            }
                        }

                        is AssistantPart.Custom -> {
                            if (part.kind == OPENAI_COMPACTION_KIND) {
                                appendCompaction(items, part.providerOptions, context)
                            } else {
                                warnings += Warning.Other(
                                    "The assistant part '${part.kind}' has no Responses API item.",
                                )
                            }
                        }

                        is AssistantPart.File, is AssistantPart.ReasoningFile -> warnings += Warning.Other(
                            "The Responses API has no input item for a model-generated file; it was dropped.",
                        )

                        // Skipped here on purpose, and not a gap: the answering ApprovalResponse is
                        // what replays this exchange, and it already emits the item reference that
                        // brings the pending request back with it. Emitting the request here as well
                        // would send OpenAI the same item twice.
                        is AssistantPart.ApprovalRequest -> Unit
                    }
                }
            }

            is ModelMessage.Tool -> message.content.forEach { part ->
                if (part is ToolPart.Result) {
                    if (context.replayExtension(part.providerOptions, items, replayedExtensionItems)) return@forEach
                    if (!context.extensions.isEmpty &&
                        context.encodeExtensionHistory(OpenResponsesExtensionInputPart.Result(part), items, warnings)
                    ) {
                        return@forEach
                    }
                }
                appendToolPart(items, part, context, warnings, programmaticToolCallIds)
            }
        }
    }

    // A reasoning item that reached this point with no encrypted content and no id claims a chain of
    // thought it cannot prove. Under `store: false` OpenAI has nothing on file to match it against.
    if (!context.store) {
        val orphaned = items.count { it.isUnbackedReasoning() }
        if (orphaned > 0) {
            warnings += Warning.Other(
                "$orphaned reasoning item(s) had no encrypted content and were dropped; set " +
                    "store to true, or request include=[\"$OPENAI_INCLUDE_ENCRYPTED_REASONING\"].",
            )
            items.removeAll { it.isUnbackedReasoning() }
        }
    }

    return OpenAIResponsesInput(items, warnings)
}

/**
 * True when [options] carry an extension's replay bookkeeping and the part has been dealt with: the
 * carrier's item replayed — once, however many parts point at it — or a decoded part skipped because
 * its carrier already did. Keyed by `type:id`, the reference's dedupe key.
 */
private fun OpenAIReplayContext.replayExtension(
    options: ProviderOptions?,
    items: MutableList<JsonObject>,
    replayed: MutableSet<String>,
): Boolean {
    if (extensions.isEmpty) return false
    val replay = extensions.replayOf(partObject(options, OPEN_RESPONSES_EXTENSION_KEY)) ?: return false
    if (replay is ExtensionReplay.Item) {
        val item = replay.item
        if (replayed.add("${item.type}:${item.id}")) items += item.json
    }
    return true
}

/**
 * True when [part] is a call or result of an extension tool declared on THIS call and has been dealt
 * with — encoded by the extension, or dropped with a warning because the extension could not.
 */
private suspend fun OpenAIReplayContext.encodeExtensionHistory(
    part: OpenResponsesExtensionInputPart?,
    items: MutableList<JsonObject>,
    warnings: MutableList<Warning>,
): Boolean {
    if (part == null || extensions.isEmpty) return false
    val tool = providerToolsByName[part.toolName] ?: return false
    if (extensions.byProviderToolId[tool.id] == null) return false
    val encoded = extensions.encodeExtensionInputPart(part, tool)
    if (encoded == null) {
        val kind = if (part is OpenResponsesExtensionInputPart.Call) "call" else "result"
        warnings += Warning.Unsupported(
            feature = "providerTool:${tool.name}",
            details = "The open-responses extension for ${tool.id} could not encode a $kind from history; dropped.",
        )
    } else {
        items += encoded
    }
    return true
}

private fun AssistantPart.asExtensionInput(): OpenResponsesExtensionInputPart? = when (this) {
    is AssistantPart.ToolCall -> OpenResponsesExtensionInputPart.Call(this)
    // A result is a result wherever the runtime filed it; the extension sees one shape.
    is AssistantPart.ToolResult -> OpenResponsesExtensionInputPart.Result(
        ToolPart.Result(
            toolCallId = toolCallId,
            toolName = toolName,
            output = output,
            providerOptions = providerOptions,
        ),
    )
    else -> null
}

/** One reasoning item under construction: several summaries, one encrypted payload, one id. */
private class ReasoningItem(val id: String?) {
    val summaries = mutableListOf<String>()
    var encrypted: String? = null

    fun toJson(): JsonObject = buildJsonObject {
        put("type", "reasoning")
        id?.let { put("id", it) }
        encrypted?.let { put("encrypted_content", it) }
        putJsonArray("summary") {
            summaries.forEach { text ->
                add(
                    buildJsonObject {
                        put("type", "summary_text")
                        put("text", text)
                    },
                )
            }
        }
    }
}

private fun JsonObject.isUnbackedReasoning(): Boolean =
    this["type"]?.jsonPrimitive?.content == "reasoning" && "encrypted_content" !in this && "id" !in this

private fun itemReference(id: String): JsonObject = buildJsonObject {
    put("type", "item_reference")
    put("id", id)
}

/**
 * An assistant text part in the `open-responses` spec's strict input shapes: with no item id, the
 * "easy" message whose `content` is the bare string; with one, the complete output item it was.
 */
private fun strictAssistantMessage(id: String?, text: String): JsonObject = if (id == null) {
    buildJsonObject {
        put("type", "message")
        put("role", "assistant")
        put("content", text)
    }
} else {
    buildJsonObject {
        put("id", id)
        put("type", "message")
        put("status", "completed")
        put("role", "assistant")
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "output_text")
                    put("text", text)
                    put("annotations", JsonArray(emptyList()))
                    put("logprobs", JsonArray(emptyList()))
                },
            )
        }
    }
}

private fun JsonObject?.isProgramCaller(): Boolean = stringOrNull("type") == "program"

/** `{type: "program", callerId}` as filed on metadata, to `{type: "program", caller_id}` as OpenAI spells it. */
private fun JsonObject.toWireCaller(): JsonObject = if (isProgramCaller()) {
    buildJsonObject {
        put("type", "program")
        this@toWireCaller["callerId"]?.let { put("caller_id", it) }
    }
} else {
    this
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun appendToolCall(
    items: MutableList<JsonObject>,
    part: AssistantPart.ToolCall,
    context: OpenAIReplayContext,
    programmaticToolCallIds: MutableSet<String>,
) {
    val id = context.partString(part.providerOptions, OPENAI_ITEM_ID_KEY)
    // The three facts the call came in with and must go back out with — see toolCallMetadata.
    val async = context.partBoolean(part.providerOptions, "async")
    val toolNamespace = context.partString(part.providerOptions, "namespace")
    val caller = context.partObject(part.providerOptions, "caller")
    if (caller.isProgramCaller()) programmaticToolCallIds += part.toolCallId
    if (context.hasConversation && id != null) return

    val wireName = context.mapping.toProviderToolName(part.toolName)

    if (part.providerExecuted) {
        // OpenAI ran it, OpenAI holds the result. With no store there is nothing to point at, and
        // reconstructing the call without its result would leave a dangling call the model must answer.
        if (context.store && id != null) items += itemReference(id)
        return
    }

    val isProviderDefined = wireName in context.providerToolsPresent &&
        wireName in setOf("local_shell", "computer", "custom")

    // NEVER an item_reference for a plain function call, even when its item id is known: a
    // `function_call_output` refers to its call by `call_id` (`call_…`), which OpenAI cannot reconcile
    // with an item id (`fc_…`). Sending one breaks the pairing and the next request fails with
    // "No tool call found for function call output".
    if (context.store && id != null && isProviderDefined) {
        items += itemReference(id)
        return
    }
    if (context.hasPreviousResponseId && context.store && id != null && isProviderDefined) return

    val input = runCatching { ProviderJson.parseToJsonElement(part.input) as? JsonObject }.getOrNull()

    items += when {
        // The computer and local-shell calls are replayed in the SHAPE THEY ARRIVED IN. Their inputs
        // are re-serialized wire objects rather than a re-modelled camelCase schema, so a round trip
        // through this layer is byte-identical and cannot drop a field we never modelled.
        isProviderDefined && wireName == "local_shell" -> buildJsonObject {
            put("type", "local_shell_call")
            put("call_id", part.toolCallId)
            id?.let { put("id", it) }
            input?.forEach { (k, v) -> put(k, v) }
        }

        isProviderDefined && wireName == "computer" -> buildJsonObject {
            put("type", "computer_call")
            put("call_id", part.toolCallId)
            id?.let { put("id", it) }
            input?.forEach { (k, v) -> put(k, v) }
        }

        part.toolName in context.customToolNames -> buildJsonObject {
            put("type", "custom_tool_call")
            put("call_id", part.toolCallId)
            put("name", wireName)
            put("input", part.input)
            async?.let { put("async", it) }
            id?.let { put("id", it) }
        }

        else -> buildJsonObject {
            put("type", "function_call")
            put("call_id", part.toolCallId)
            put("name", wireName)
            // An empty-argument call still needs valid JSON, or OpenAI rejects the item.
            put("arguments", part.input.takeIf { it.isNotBlank() } ?: "{}")
            async?.let { put("async", it) }
            toolNamespace?.let { put("namespace", it) }
            caller?.let { put("caller", it.toWireCaller()) }
        }
    }
}

private fun appendCompaction(
    items: MutableList<JsonObject>,
    options: ProviderOptions?,
    context: OpenAIReplayContext,
) {
    val id = context.partString(options, OPENAI_ITEM_ID_KEY) ?: return
    if (context.hasConversation) return
    if (context.store) {
        items += itemReference(id)
        return
    }
    items += buildJsonObject {
        put("type", "compaction")
        put("id", id)
        context.partString(options, "encryptedContent")?.let { put("encrypted_content", it) }
    }
}

private fun appendToolPart(
    items: MutableList<JsonObject>,
    part: ToolPart,
    context: OpenAIReplayContext,
    warnings: MutableList<Warning>,
    programmaticToolCallIds: Set<String>,
) {
    when (part) {
        is ToolPart.ApprovalResponse -> {
            // A decision the RUNTIME's own gate made is not an MCP answer: its approval id was minted
            // locally, so replaying it here names a pending item this API never created. The model
            // still learns the outcome — from the tool result, or from its absence.
            if (part.isRuntimeMinted) return
            // The request item itself has to come back too, or OpenAI has no pending approval to
            // resolve — but only when it is holding it and not already replaying the chain.
            if (context.store && !context.hasConversation && !context.hasPreviousResponseId) {
                items += itemReference(part.approvalId)
            }
            items += buildJsonObject {
                put("type", "mcp_approval_response")
                put("approval_request_id", part.approvalId)
                put("approve", part.approved)
            }
        }

        is ToolPart.Result -> {
            val caller = context.partObject(part.providerOptions, "caller")
            // A programmatic call's result is consumed by the program that made it, and OpenAI has no
            // wire form for "the runtime refused": sent as text, the denial would hand the program a
            // sentence where it expects a value. Refused outright, as the reference does.
            if (part.output is ToolOutput.ExecutionDenied &&
                (caller.isProgramCaller() || part.toolCallId in programmaticToolCallIds)
            ) {
                throw UnsupportedFunctionalityError("execution-denied results for programmatic tool calls")
            }
            val wireName = context.mapping.toProviderToolName(part.toolName)
            val output = part.output.toFunctionCallOutput(warnings, context)
            items += when {
                wireName == "local_shell" && "local_shell" in context.providerToolsPresent ->
                    buildJsonObject {
                        put("type", "local_shell_call_output")
                        put("call_id", part.toolCallId)
                        put("output", output)
                    }

                wireName == "computer" && "computer" in context.providerToolsPresent -> buildJsonObject {
                    put("type", "computer_call_output")
                    put("call_id", part.toolCallId)
                    put("output", output)
                }

                part.toolName in context.customToolNames -> buildJsonObject {
                    put("type", "custom_tool_call_output")
                    put("call_id", part.toolCallId)
                    put("output", output)
                }

                else -> buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", part.toolCallId)
                    put("output", output)
                    caller?.let { put("caller", it.toWireCaller()) }
                }
            }
        }
    }
}

/**
 * A tool result as OpenAI wants it: a string, or an array of input parts for a multi-modal result.
 *
 * The error arms collapse to their text rather than setting a flag, because `function_call_output` has
 * no error field at all — the Responses API's model of a failed tool is a result that says so.
 */
private fun ToolOutput.toFunctionCallOutput(
    warnings: MutableList<Warning>,
    context: OpenAIReplayContext,
): JsonElement = when (this) {
    is ToolOutput.Text -> JsonPrimitive(value)
    is ToolOutput.ErrorText -> JsonPrimitive(value)
    is ToolOutput.Json -> JsonPrimitive(value.toString())
    is ToolOutput.ErrorJson -> JsonPrimitive(value.toString())
    is ToolOutput.ExecutionDenied -> JsonPrimitive(reason ?: "Tool call execution denied.")
    is ToolOutput.Multipart -> buildJsonArray {
        value.forEach { item ->
            when (item) {
                is ToolOutput.Multipart.Item.Text -> add(
                    buildJsonObject {
                        put("type", "input_text")
                        put("text", item.text)
                    },
                )
                is ToolOutput.Multipart.Item.File -> add(
                    item.data.toInputFilePart(item.mediaType, item.filename, index = 0, context, item.providerOptions),
                )
                // A custom item carries no content of its own; there is nothing to send.
                is ToolOutput.Multipart.Item.Custom -> warnings += Warning.Other(
                    "A custom tool-result item has no Responses API representation and was dropped.",
                )
            }
        }
    }
}

private fun UserPart.toInputPart(index: Int, context: OpenAIReplayContext): JsonObject = when (this) {
    is UserPart.Text -> buildJsonObject {
        put("type", "input_text")
        put("text", text)
    }
    is UserPart.File -> {
        val reference = (data as? FileData.Reference)?.reference?.let(context::fileReference)
        when {
            reference != null -> buildJsonObject {
                val isImage = mediaType.startsWith("image/")
                put("type", if (isImage) "input_image" else "input_file")
                put("file_id", reference)
                if (isImage) context.imageDetail(providerOptions)?.let { put("detail", it) }
            }
            data is FileData.Text -> throw UnsupportedFunctionalityError("inline text file parts")
            mediaType != "application/pdf" && !mediaType.startsWith("image/") &&
                !context.passThroughUnsupportedFiles ->
                throw UnsupportedFunctionalityError("file part media type $mediaType")
            else -> data.toInputFilePart(mediaType, filename, index, context, providerOptions)
        }
    }
}

/** `detail` for an `input_image`: the part's `imageDetail`, else the endpoint's default, else absent. */
private fun OpenAIReplayContext.imageDetail(options: ProviderOptions?): String? =
    partString(options, "imageDetail") ?: defaultImageDetail

@OptIn(ExperimentalEncodingApi::class)
private fun FileData.toInputFilePart(
    mediaType: String,
    filename: String?,
    index: Int,
    context: OpenAIReplayContext,
    options: ProviderOptions?,
): JsonObject {
    val isImage = mediaType.startsWith("image/")
    return buildJsonObject {
        put("type", if (isImage) "input_image" else "input_file")
        if (isImage) context.imageDetail(options)?.let { put("detail", it) }
        when (this@toInputFilePart) {
            // A URL is passed through: the Responses API fetches it itself, so downloading and
            // re-uploading a large PDF here would be one wasted request and the bytes in memory.
            is FileData.Url -> put(if (isImage) "image_url" else "file_url", url)
            is FileData.Bytes -> {
                val dataUrl = "data:$mediaType;base64,${Base64.encode(bytes)}"
                if (isImage) {
                    put("image_url", dataUrl)
                } else {
                    // Several models infer the format from the extension and reject a file without one.
                    put("filename", filename ?: defaultFilename(mediaType, index))
                    put("file_data", dataUrl)
                }
            }
            is FileData.Reference -> context.fileReference(reference)?.let { put("file_id", it) }
            is FileData.Text -> throw UnsupportedFunctionalityError("inline text file parts")
        }
    }
}

private fun defaultFilename(mediaType: String, index: Int): String =
    if (mediaType == "application/pdf") "part-$index.pdf" else "part-$index"

internal fun JsonObject?.stringOrNull(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `Content.Custom.kind` for a server-side context compaction. */
internal const val OPENAI_COMPACTION_KIND: String = "openai.compaction"
