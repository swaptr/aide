package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `providerMetadata` for a reasoning item: the two values that make it replayable.
 *
 * Nothing else in the pipeline understands either one, which is exactly why neither can be lost —
 * see `ProviderMetadata`. [encryptedContent] is present only when the request asked for it.
 */
internal fun reasoningMetadata(
    itemId: String?,
    encryptedContent: String?,
    namespace: String = OPENAI_PROVIDER_ID,
): ProviderMetadata? {
    if (itemId == null && encryptedContent == null) return null
    return mapOf(
        namespace to buildJsonObject {
            itemId?.let { put(OPENAI_ITEM_ID_KEY, it) }
            encryptedContent?.let { put(OPENAI_ENCRYPTED_REASONING_KEY, it) }
        },
    )
}

internal fun itemMetadata(itemId: String?, namespace: String = OPENAI_PROVIDER_ID): ProviderMetadata? =
    itemId?.let { mapOf(namespace to buildJsonObject { put(OPENAI_ITEM_ID_KEY, it) }) }

/**
 * `providerMetadata` for a client-run tool call: the item id, plus the three facts a replay has to
 * send back verbatim — whether the call was async, the tool's namespace, and who made it. A
 * programmatic caller arrives as `{type: "program", caller_id}` and is filed as
 * `{type: "program", callerId}`, the reference's spelling, so a prompt written against it reads the
 * same on every host. Nothing here is understood by the layers above, which is exactly why none of it
 * may be dropped: a denied result for a programmatic call is refused on replay by reading `caller` back.
 */
internal fun toolCallMetadata(
    itemId: String?,
    async: Boolean? = null,
    toolNamespace: String? = null,
    caller: JsonObject? = null,
    namespace: String = OPENAI_PROVIDER_ID,
): ProviderMetadata? {
    if (itemId == null && async == null && toolNamespace == null && caller == null) return null
    return mapOf(
        namespace to buildJsonObject {
            itemId?.let { put(OPENAI_ITEM_ID_KEY, it) }
            async?.let { put("async", it) }
            toolNamespace?.let { put("namespace", it) }
            caller?.let { put("caller", it.toCallerMetadata()) }
        },
    )
}

private fun JsonObject.toCallerMetadata(): JsonObject =
    if ((this["type"] as? JsonPrimitive)?.content == "program") {
        buildJsonObject {
            put("type", "program")
            this@toCallerMetadata["caller_id"]?.let { put("callerId", it) }
        }
    } else {
        this
    }

/**
 * [mayExcludeCached] is xAI's departure — see [ResponsesQuirks.usageMayExcludeCachedTokens]. The tell is
 * arithmetic (cached exceeding the reported input), so a vendor that DOES include cached tokens is
 * unaffected even with the flag on.
 */
internal fun OpenAIResponsesUsage?.toUsage(
    raw: JsonObject? = null,
    mayExcludeCached: Boolean = false,
): Usage {
    if (this == null) return Usage(raw = raw)
    val cached = inputTokensDetails?.cachedTokens ?: 0
    val cacheWrite = inputTokensDetails?.cacheWriteTokens
    val reasoning = outputTokensDetails?.reasoningTokens ?: 0
    val inputExcludesCached = mayExcludeCached && inputTokens != null && cached > inputTokens
    return Usage(
        inputTokens = Usage.InputTokens(
            total = if (inputExcludesCached) inputTokens + cached else inputTokens,
            noCache = inputTokens?.let { if (inputExcludesCached) it else it - cached - (cacheWrite ?: 0) },
            cacheRead = inputTokensDetails?.cachedTokens,
            cacheWrite = cacheWrite,
        ),
        outputTokens = Usage.OutputTokens(
            total = outputTokens,
            text = outputTokens?.let { it - reasoning },
            reasoning = outputTokensDetails?.reasoningTokens,
        ),
        raw = raw,
    )
}

/**
 * Why generation stopped.
 *
 * The Responses API reports NOTHING on a normal finish: `incomplete_details` is null both when the model
 * answered and when it asked for a tool. [hasFunctionCall] is what tells the two apart, and a caller
 * that reads `stop` where the model was waiting on a tool ends the conversation one round early.
 */
internal fun openAIFinishReason(
    reason: String?,
    hasFunctionCall: Boolean,
    quirks: ResponsesQuirks = ResponsesQuirks(),
): FinishReason = FinishReason(
    unified = reason?.let { quirks.finishReasons[it] } ?: when (reason) {
        null -> if (hasFunctionCall) FinishReason.Unified.ToolCalls else FinishReason.Unified.Stop
        "max_output_tokens" -> FinishReason.Unified.Length
        "content_filter" -> FinishReason.Unified.ContentFilter
        else -> if (hasFunctionCall) FinishReason.Unified.ToolCalls else FinishReason.Unified.Other
    },
    raw = reason,
)

/** What a completed `output` array became. */
internal data class MappedOutput(
    val content: List<Content>,
    /** A CLIENT-executed call is pending, which is what makes the finish reason `tool-calls`. */
    val hasFunctionCall: Boolean,
)

/**
 * Maps a non-streaming `output` array to the neutral content list.
 *
 * Order is preserved exactly as OpenAI emitted it, and that is load-bearing rather than tidy: a
 * reasoning item that preceded a tool call has to be replayed before that tool call, and the sequence
 * of reasoning-then-call-then-reasoning is the model's own record of how it got to the answer.
 *
 * A namespaced item — an Open Responses extension's — is handed to the extension that registered its
 * type, in [mode], and takes its place in the same order. Suspending for that reason: a codec is caller
 * code and may need to do work.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun List<OpenAIOutputItem>.toContent(
    tools: PreparedTools,
    generateId: () -> String,
    namespace: String = OPENAI_PROVIDER_ID,
    extensions: OpenResponsesExtensionRegistry = OpenResponsesExtensionRegistry.Empty,
    mode: OpenResponsesDecodeMode = OpenResponsesDecodeMode.Generate,
): MappedOutput {
    val content = mutableListOf<Content>()
    var hasFunctionCall = false
    val mapping = tools.mapping

    forEach { item ->
        when (item.type) {
            "reasoning" -> {
                // An item with no summary still carries the id and the encrypted payload. Dropping it
                // for having no readable text discards precisely the half that has to be replayed.
                val summaries = item.summary?.takeIf { it.isNotEmpty() } ?: listOf(OpenAISummaryPart(text = ""))
                summaries.forEach { summary ->
                    content += Content.Reasoning(
                        text = summary.text,
                        providerMetadata = reasoningMetadata(item.id, item.encryptedContent, namespace),
                    )
                }
            }

            "message" -> item.content.orEmpty().forEach { part ->
                content += Content.Text(
                    text = part.text.orEmpty(),
                    providerMetadata = textMetadata(item.id, item.phase, namespace),
                )
                part.annotations.forEach { annotation ->
                    annotation.toSource(generateId, namespace)?.let { content += it }
                }
            }

            "function_call" -> {
                hasFunctionCall = true
                content += Content.ToolCall(
                    toolCallId = item.callId.orEmpty(),
                    toolName = item.name.orEmpty(),
                    input = item.arguments.orEmpty(),
                    providerMetadata = toolCallMetadata(item.id, item.async, item.namespace, item.caller, namespace),
                )
            }

            "custom_tool_call" -> {
                hasFunctionCall = true
                content += Content.ToolCall(
                    toolCallId = item.callId.orEmpty(),
                    toolName = mapping.toCustomToolName(item.name.orEmpty()),
                    input = item.input.orEmpty(),
                    providerMetadata = toolCallMetadata(item.id, item.async, namespace = namespace),
                )
            }

            // File edits the CLIENT applies. The finish is `tool-calls` for the same reason a function
            // call's is: the model is waiting on the runtime, and a `stop` here ends the round with the
            // patch unapplied.
            "apply_patch_call" -> {
                hasFunctionCall = true
                content += Content.ToolCall(
                    toolCallId = item.callId.orEmpty(),
                    toolName = mapping.toCustomToolName("apply_patch"),
                    input = buildJsonObject {
                        put("callId", item.callId.orEmpty())
                        put("operation", item.operation ?: JsonNull)
                    }.toString(),
                    providerMetadata = itemMetadata(item.id, namespace),
                )
            }

            "web_search_call" -> content += providerExecutedPair(
                id = item.id.orEmpty(),
                toolName = mapping.toCustomToolName(tools.webSearchWireName),
                input = "{}",
                output = item.action.toWebSearchOutput(),
            )

            "file_search_call" -> content += providerExecutedPair(
                id = item.id.orEmpty(),
                toolName = mapping.toCustomToolName("file_search"),
                input = "{}",
                output = buildJsonObject {
                    put("queries", item.queries.toJsonArrayOrNull() ?: JsonNull)
                    put("results", item.results ?: JsonNull)
                },
            )

            "code_interpreter_call" -> content += providerExecutedPair(
                id = item.id.orEmpty(),
                toolName = mapping.toCustomToolName("code_interpreter"),
                input = buildJsonObject {
                    put("code", item.code ?: "")
                    put("containerId", item.containerId ?: "")
                }.toString(),
                output = buildJsonObject { put("outputs", item.outputs ?: JsonNull) },
            )

            "image_generation_call" -> content += providerExecutedPair(
                id = item.id.orEmpty(),
                toolName = mapping.toCustomToolName("image_generation"),
                input = "{}",
                output = buildJsonObject { put("result", item.result ?: "") },
            )

            "local_shell_call" -> content += Content.ToolCall(
                toolCallId = item.callId.orEmpty(),
                toolName = mapping.toCustomToolName("local_shell"),
                input = buildJsonObject { put("action", item.action ?: JsonNull) }.toString(),
                providerMetadata = itemMetadata(item.id, namespace),
            )

            "computer_call" -> {
                hasFunctionCall = true
                content += Content.ToolCall(
                    toolCallId = item.callId ?: item.id.orEmpty(),
                    toolName = mapping.toCustomToolName("computer"),
                    input = item.computerInput().toString(),
                    providerMetadata = itemMetadata(item.id, namespace),
                )
            }

            "mcp_call" -> {
                val toolName = "mcp.${item.name.orEmpty()}"
                val callId = item.id.orEmpty()
                content += Content.ToolCall(
                    toolCallId = callId,
                    toolName = toolName,
                    input = item.arguments.orEmpty(),
                    providerExecuted = true,
                    // The tool's schema is the MCP server's, discovered at run time — nothing in this
                    // request declared it, which is what `dynamic` says.
                    dynamic = true,
                    providerMetadata = itemMetadata(item.id, namespace),
                )
                content += Content.ToolResult(
                    toolCallId = callId,
                    toolName = toolName,
                    output = ToolOutput.Json(item.mcpResult()),
                    isError = item.error != null && item.error != JsonNull,
                    dynamic = true,
                    providerMetadata = itemMetadata(item.id, namespace),
                )
            }

            "mcp_approval_request" -> {
                val approvalId = item.approvalRequestId ?: item.id.orEmpty()
                val toolCallId = generateId()
                content += Content.ToolCall(
                    toolCallId = toolCallId,
                    toolName = "mcp.${item.name.orEmpty()}",
                    input = item.arguments.orEmpty(),
                    providerExecuted = true,
                    dynamic = true,
                    // The approval id is carried on the CALL as well as on the request part, so a
                    // replayed turn can match the two back up without the runtime keeping a side table.
                    providerMetadata = mapOf(
                        namespace to buildJsonObject {
                            item.id?.let { put(OPENAI_ITEM_ID_KEY, it) }
                            put("approvalRequestId", approvalId)
                        },
                    ),
                )
                content += Content.ToolApprovalRequest(approvalId = approvalId, toolCallId = toolCallId)
            }

            "compaction" -> content += Content.Custom(
                kind = OPENAI_COMPACTION_KIND,
                providerMetadata = mapOf(
                    namespace to buildJsonObject {
                        put("type", "compaction")
                        item.id?.let { put(OPENAI_ITEM_ID_KEY, it) }
                        item.encryptedContent?.let { put("encryptedContent", it) }
                    },
                ),
            )

            // mcp_list_tools is the server announcing what it offers; there is nothing for a caller to
            // act on, and replaying it back would claim the model produced it. A namespaced type is an
            // extension's item, and an extension that surfaces a call — whoever executes it — has
            // turned the finish into `tool-calls`, as the reference reads it.
            else -> item.extension
                ?.let { OpenResponsesExtensionItem.from(it) }
                ?.let { extensions.decodeExtensionItem(it, mode, namespace) }
                ?.let { decoded ->
                    if (decoded.any { it is Content.ToolCall }) hasFunctionCall = true
                    content += decoded
                }
        }
    }

    return MappedOutput(content, hasFunctionCall)
}

internal fun textMetadata(
    itemId: String?,
    phase: String?,
    namespace: String = OPENAI_PROVIDER_ID,
): ProviderMetadata? {
    if (itemId == null && phase == null) return null
    return mapOf(
        namespace to buildJsonObject {
            itemId?.let { put(OPENAI_ITEM_ID_KEY, it) }
            phase?.let { put("phase", it) }
        },
    )
}

/**
 * The call/result pair a server-side tool produces.
 *
 * OpenAI runs these itself and reports both halves in one item, so both halves are emitted here with
 * `providerExecuted` set. A runtime that saw only the result would have nothing to attribute it to; one
 * that saw only the call would try to execute a tool it has no implementation for.
 */
private fun providerExecutedPair(
    id: String,
    toolName: String,
    input: String,
    output: JsonElement,
): List<Content> = listOf(
    Content.ToolCall(
        toolCallId = id,
        toolName = toolName,
        input = input,
        providerExecuted = true,
    ),
    Content.ToolResult(
        toolCallId = id,
        toolName = toolName,
        output = ToolOutput.Json(output),
    ),
)

/**
 * A web-search citation becomes a first-class source.
 *
 * This is the affordance that makes a searched answer checkable: the URLs the model actually read,
 * attached to the turn, rather than a footnote it may or may not have written into its prose.
 */
internal fun OpenAIAnnotation.toSource(
    generateId: () -> String,
    namespace: String = OPENAI_PROVIDER_ID,
): Content.Source? = when (type) {
    "url_citation" -> Content.Source.Url(
        id = generateId(),
        url = url.orEmpty(),
        title = title,
        providerMetadata = mapOf(namespace to buildJsonObject { put("type", type) }),
    )
    "file_citation", "container_file_citation" -> Content.Source.Document(
        id = generateId(),
        mediaType = "text/plain",
        title = filename ?: fileId.orEmpty(),
        filename = filename,
        providerMetadata = annotationMetadata(namespace),
    )
    "file_path" -> Content.Source.Document(
        id = generateId(),
        mediaType = "application/octet-stream",
        title = fileId.orEmpty(),
        filename = fileId,
        providerMetadata = annotationMetadata(namespace),
    )
    else -> null
}

private fun OpenAIAnnotation.annotationMetadata(namespace: String): ProviderMetadata = mapOf(
    namespace to buildJsonObject {
        put("type", type)
        fileId?.let { put("fileId", it) }
        containerId?.let { put("containerId", it) }
        index?.let { put("index", it) }
    },
)

/**
 * A `web_search_call.action`, normalized to the shape the reference's own web-search tool declares.
 *
 * The three actions are search, open_page and find_in_page, and the last two carry only a URL — a client
 * that assumes every web search produced a query list gets an empty result for a page the model read.
 */
internal fun JsonObject?.toWebSearchOutput(): JsonObject {
    val action = this ?: return JsonObject(emptyMap())
    val type = (action["type"] as? JsonPrimitive)?.content
    return buildJsonObject {
        put(
            "action",
            buildJsonObject {
                when (type) {
                    "search" -> {
                        put("type", "search")
                        action["query"]?.let { put("query", it) }
                        action["queries"]?.let { put("queries", it) }
                    }
                    "open_page" -> {
                        put("type", "openPage")
                        action["url"]?.let { put("url", it) }
                    }
                    "find_in_page" -> {
                        put("type", "findInPage")
                        action["url"]?.let { put("url", it) }
                        action["pattern"]?.let { put("pattern", it) }
                    }
                    else -> type?.let { put("type", it) }
                }
            },
        )
        // Only present with include=["web_search_call.action.sources"], which the request adds itself.
        action["sources"]?.let { put("sources", it) }
    }
}

private fun OpenAIOutputItem.mcpResult(): JsonObject = buildJsonObject {
    put("type", "call")
    serverLabel?.let { put("serverLabel", it) }
    name?.let { put("name", it) }
    arguments?.let { put("arguments", it) }
    output?.takeIf { it != JsonNull }?.let { put("output", it) }
    error?.takeIf { it != JsonNull }?.let { put("error", it) }
}

/**
 * A computer call's input, kept in OpenAI's own wire shape.
 *
 * Deliberately NOT re-modelled into camelCase. A computer action is a growing union — click, drag,
 * keypress, scroll, and whatever the next model can do — and a hand-written mapping in each direction is
 * a place for a field to be dropped on the way out and then be missing on the way back. Passing the
 * vendor's own object through means a round trip is byte-identical by construction.
 */
private fun OpenAIOutputItem.computerInput(): JsonObject = buildJsonObject {
    actions?.let { put("actions", it) }
    action?.let { put("action", it) }
    pendingSafetyChecks?.let { put("pending_safety_checks", it) }
    status?.let { put("status", it) }
}

private fun List<String>?.toJsonArrayOrNull(): JsonElement? = this?.let { list ->
    kotlinx.serialization.json.JsonArray(list.map { JsonPrimitive(it) })
}
