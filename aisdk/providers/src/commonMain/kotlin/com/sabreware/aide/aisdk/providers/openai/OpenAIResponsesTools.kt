package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ToolNameMapping
import com.sabreware.aide.aisdk.util.wireToolNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Responses API's server-side tools, keyed by the stable id a caller uses — derived from
 * [OpenAITools], so the factories a caller builds tools with and the table the request builder sends
 * them by are one declaration rather than two kept in agreement by hand.
 *
 * The id is `openai.<wire name>` and the value is the name OpenAI puts on the wire. They differ only in
 * the prefix today, but the two are not the same thing and must not be assumed to be: the id is what a
 * portable prompt is written against, the wire name is what this vendor happens to call it this year.
 *
 * This table is the input to [ToolNameMapping], which translates in BOTH directions — the client's name
 * for a tool to the vendor's on the way out, and back on the way in. Without the second direction a
 * `web_search_call` arrives naming a tool the caller never registered; without the first, a result goes
 * out under a name OpenAI does not recognize.
 */
internal val OpenAIProviderToolNames: Map<String, String> = OpenAITools.all.wireToolNames()

/** The tools a call declared, in the shapes the request and the two mappers each need. */
internal data class PreparedTools(
    val tools: JsonArray?,
    val toolChoice: JsonElement?,
    val warnings: List<Warning>,
    val mapping: ToolNameMapping,
    /** Wire names of the provider tools present, which decides how their calls are REPLAYED. */
    val providerToolsPresent: Set<String>,
    /** Names declared as `openai.custom`; their calls and results use the custom-tool item types. */
    val customToolNames: Set<String>,
    /** Every provider-defined tool declared, by name — what the prompt converter joins history to. */
    val providerToolsByName: Map<String, Tool.ProviderDefined> = emptyMap(),
) {
    /**
     * Which web-search wire name this call offered.
     *
     * Both `web_search` and `web_search_preview` come back as a `web_search_call` item, so the item
     * alone cannot say which tool produced it. Naming the result after the tool that was never
     * registered leaves the caller with a result it cannot match to anything.
     */
    val webSearchWireName: String =
        if ("web_search_preview" in providerToolsPresent) "web_search_preview" else "web_search"
}

/**
 * Builds `tools` and `tool_choice`.
 *
 * A provider-defined tool's `args` is the vendor's own configuration blob and is passed through with its
 * keys renamed to OpenAI's snake_case, ONE TOOL AT A TIME rather than by a generic camel-to-snake pass.
 * The generic pass is tempting and wrong: an MCP tool's `headers` are arbitrary HTTP header names used as
 * KEYS, and a recursive rename corrupts every one of them.
 *
 * Any key not in a tool's documented set is copied verbatim, so a configuration option OpenAI ships after
 * this file was written reaches the wire without a code change here.
 *
 * An Open Responses [extensions] entry is consulted FIRST, before either vendor table: an extension
 * tool's id is the extension's own, and an endpoint that registered a codec for it is the only
 * authority on its shape. Its encoder declining drops the tool with a warning, and drops a
 * `tool_choice` that selected it, since a choice naming a tool that was not sent cannot be expressed.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal suspend fun prepareTools(
    tools: List<Tool>?,
    toolChoice: ToolChoice?,
    /** `providerOptions.openai.allowedTools` — `{toolNames: [..], mode?}`; overrides [toolChoice]. */
    allowedTools: JsonObject? = null,
    /** See [ResponsesQuirks.functionToolsOnly] — the Hugging Face router's tool surface. */
    functionToolsOnly: Boolean = false,
    /** The serving vendor's own tool table, consulted before OpenAI's — see [ResponsesQuirks]. */
    vendorToolNames: Map<String, String> = emptyMap(),
    /** The serving vendor's tool-body builder — see [ResponsesQuirks.providerToolBodies]. */
    vendorToolBody: ((String, String, JsonObject) -> JsonObject)? = null,
    /** The endpoint's Open Responses extensions — see [OpenResponsesExtension]. */
    extensions: OpenResponsesExtensionRegistry = OpenResponsesExtensionRegistry.Empty,
    /** The namespace a tool-level `async` is read under, canonical `openai` always read beneath it. */
    namespace: String = OPENAI_PROVIDER_ID,
    /** GPT-6 and later. An `async` tool on an earlier model goes out without the flag, with a warning. */
    supportsAsyncToolCalling: Boolean = true,
): PreparedTools {
    // A vendor that declares its OWN tool set is authoritative for it: xAI serves xai.* and nothing
    // of OpenAI's, so inheriting OpenAI's table would send `local_shell` to an endpoint that has never
    // heard of it — a 400 in place of the warning the caller should have got. A vendor that declares
    // NO table is hosting OpenAI's own surface (Azure), and gets OpenAI's.
    val knownToolNames = vendorToolNames.ifEmpty { OpenAIProviderToolNames }
    val mapping = ToolNameMapping.from(tools, knownToolNames)
    if (tools.isNullOrEmpty()) {
        return PreparedTools(null, null, emptyList(), mapping, emptySet(), emptySet())
    }
    val providerToolsByName = tools.filterIsInstance<Tool.ProviderDefined>().associateBy { it.name }

    val warnings = mutableListOf<Warning>()
    val out = mutableListOf<JsonObject>()
    val present = mutableSetOf<String>()
    val customNames = mutableSetOf<String>()
    val extensionTools = mutableMapOf<String, Pair<OpenResponsesExtension, Tool.ProviderDefined>>()
    val refusedExtensionTools = mutableSetOf<String>()

    tools.forEach { tool ->
        when (tool) {
            is Tool.Function -> {
                // OpenAI rejects `propertyNames`; the normalizer drops the string form and says so.
                val parameters = normalizeOpenAIJsonSchema(tool.inputSchema)
                warnings += parameters.warnings
                val async = resolveAsyncToolOption(
                    requested = (mergedResponsesOptions(tool.providerOptions, namespace)["async"] as? JsonPrimitive)
                        ?.booleanOrNull,
                    supportsAsyncToolCalling = supportsAsyncToolCalling,
                    toolName = tool.name,
                    warnings = warnings,
                )
                out += buildJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    tool.description?.let { put("description", it) }
                    put("parameters", parameters.schema)
                    async?.let { put("async", it) }
                    tool.strict?.let { put("strict", it) }
                }
            }

            is Tool.ProviderDefined -> {
                val extension = extensions.byProviderToolId[tool.id]
                if (extension != null) {
                    val entry = extension.encodeToolEntry(tool)
                    if (entry == null) {
                        warnings += Warning.Unsupported(
                            feature = "providerTool:${tool.name}",
                            details = "The open-responses extension for ${tool.id} could not encode it.",
                        )
                        refusedExtensionTools += tool.name
                    } else {
                        out += entry
                        extensionTools[tool.name] = extension to tool
                    }
                    return@forEach
                }
                val wireName = knownToolNames[tool.id].takeIf { !functionToolsOnly }
                if (wireName == null) {
                    warnings += Warning.Unsupported(
                        feature = "providerTool:${tool.name}",
                        details = if (functionToolsOnly) {
                            "This Responses endpoint serves plain function tools only."
                        } else {
                            "${tool.id} is not a Responses API tool."
                        },
                    )
                    return@forEach
                }
                present += wireName
                if (wireName == "custom") customNames += tool.name
                // A vendor tool gets the vendor's body; OpenAI's own shape is the fallback, which is
                // right for a vendor that only renames the type.
                val body = if (vendorToolBody != null && vendorToolNames.containsKey(tool.id)) {
                    vendorToolBody(tool.id, wireName, tool.args)
                } else {
                    providerToolBody(wireName, tool)
                }
                // A custom tool's `async` rides in its args and is a 400 on a model without async
                // calling, so it comes off the body there rather than going out to be refused.
                val customAsync = (tool.args["async"] as? JsonPrimitive)?.booleanOrNull.takeIf { wireName == "custom" }
                val asyncDropped = customAsync == true &&
                    resolveAsyncToolOption(customAsync, supportsAsyncToolCalling, tool.name, warnings) == null
                out += if (asyncDropped) JsonObject(body - "async") else body
            }
        }
    }

    val builtInChoiceTypes = knownToolNames.values.toSet() - "custom"
    val choice = toolChoice?.let { requested ->
        when (requested) {
            ToolChoice.Auto -> JsonPrimitive("auto")
            ToolChoice.None ->
                if (functionToolsOnly) {
                    // The Hugging Face router has no wire form for "none"; forwarding it reads as an
                    // unknown value, and dropping it silently would claim the ban was applied.
                    warnings += Warning.Unsupported(
                        feature = "toolChoice",
                        details = "This Responses endpoint cannot express 'none'; the setting was dropped.",
                    )
                    null
                } else {
                    JsonPrimitive("none")
                }
            ToolChoice.Required -> JsonPrimitive("required")
            is ToolChoice.Specific -> {
                val name = mapping.toProviderToolName(requested.toolName)
                val extensionTool = extensionTools[requested.toolName]
                when {
                    extensionTool != null ->
                        extensionTool.first.encodeToolChoiceEntry(extensionTool.second, warnings)
                    // The tool was refused above and is not in the request; a function entry naming
                    // it would be a 400 for a tool the caller was already warned about.
                    requested.toolName in refusedExtensionTools -> null
                    // The router nests the name one level down; OpenAI's flat shape reads there as an
                    // unknown field and the pin silently never engages.
                    functionToolsOnly -> buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject { put("name", name) })
                    }
                    name in builtInChoiceTypes -> buildJsonObject { put("type", name) }
                    requested.toolName in customNames -> buildJsonObject {
                        put("type", "custom")
                        put("name", requested.toolName)
                    }
                    else -> buildJsonObject {
                        put("type", "function")
                        put("name", name)
                    }
                }
            }
        }
    }

    // `allowedTools` narrows what the model may call without shrinking the declared set, and it
    // OVERRIDES tool_choice — sending both is the contradiction the reference resolves the same way.
    val allowed = allowedTools?.let {
        buildAllowedToolsChoice(it, tools, mapping, builtInChoiceTypes, customNames, warnings)
    }

    return PreparedTools(
        tools = out.takeIf { it.isNotEmpty() }?.let { JsonArray(it) },
        // A tool_choice with no tools is a 400 on every vendor that has the field.
        toolChoice = (allowed ?: choice).takeIf { out.isNotEmpty() },
        warnings = warnings,
        mapping = mapping,
        providerToolsPresent = present,
        customToolNames = customNames,
        providerToolsByName = providerToolsByName,
    )
}

/**
 * `tool_choice: {type: "allowed_tools"}` from the caller's name list.
 *
 * Every name resolves to the entry shape its tool kind requires: a function or custom tool carries a
 * `name`, an MCP tool carries its `server_label` (read off the declared tool's own args), and every
 * other built-in is its bare `type`. A name that matches nothing still goes out as a function entry
 * with a warning — the server's unknown-tool error names the tool, ours would not — and a list that
 * resolves to NOTHING is refused here, because `allowed_tools: []` means "call nothing" and that is
 * `tool_choice: none`, which the caller did not say.
 */
private fun buildAllowedToolsChoice(
    allowedTools: JsonObject,
    tools: List<Tool>,
    mapping: ToolNameMapping,
    builtInChoiceTypes: Set<String>,
    customNames: Set<String>,
    warnings: MutableList<Warning>,
): JsonObject? {
    val names = (allowedTools["toolNames"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .orEmpty()
    if (names.isEmpty()) return null

    val declaredFunctionNames = tools.filterIsInstance<Tool.Function>().mapTo(mutableSetOf()) { it.name }
    val entries = names.mapNotNull { requested ->
        val wire = mapping.toProviderToolName(requested)
        when {
            requested in customNames -> buildJsonObject { put("type", "custom"); put("name", requested) }
            wire == "mcp" -> {
                val label = tools.filterIsInstance<Tool.ProviderDefined>()
                    .firstOrNull { it.name == requested }
                    ?.args?.get("serverLabel")?.let { (it as? JsonPrimitive)?.content }
                if (label == null) {
                    warnings += Warning.Other("allowedTools: MCP tool '$requested' has no serverLabel; dropped.")
                    null
                } else {
                    buildJsonObject { put("type", "mcp"); put("server_label", label) }
                }
            }
            wire in builtInChoiceTypes -> buildJsonObject { put("type", wire) }
            else -> {
                if (requested !in declaredFunctionNames) {
                    warnings += Warning.Other(
                        "allowedTools: '$requested' matches no declared tool; sent as a function entry.",
                    )
                }
                buildJsonObject { put("type", "function"); put("name", wire) }
            }
        }
    }
    if (entries.isEmpty()) {
        throw UnsupportedFunctionalityError(
            "allowedTools resolved to no usable entries; an empty allowed_tools list would forbid every tool.",
        )
    }
    return buildJsonObject {
        put("type", "allowed_tools")
        put("mode", (allowedTools["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "auto")
        put("tools", JsonArray(entries))
    }
}

/**
 * `async: true` on a model without async tool calling is refused by the endpoint, so it is dropped
 * here with a warning; anything else goes out as asked.
 */
private fun resolveAsyncToolOption(
    requested: Boolean?,
    supportsAsyncToolCalling: Boolean,
    toolName: String,
    warnings: MutableList<Warning>,
): Boolean? {
    if (requested != true || supportsAsyncToolCalling) return requested
    warnings += Warning.Unsupported(
        feature = "async tool calling for \"$toolName\"",
        details = "Async tool calling is only supported by GPT-6 and later models.",
    )
    return null
}

@Suppress("CyclomaticComplexMethod")
private fun providerToolBody(wireName: String, tool: Tool.ProviderDefined): JsonObject {
    val args = tool.args
    val body = when (wireName) {
        "web_search" -> args.rename(
            "externalWebAccess" to "external_web_access",
            "searchContextSize" to "search_context_size",
            "userLocation" to "user_location",
        ).replacing("filters") { it.rename("allowedDomains" to "allowed_domains", "blockedDomains" to "blocked_domains") }

        "web_search_preview" -> args.rename(
            "searchContextSize" to "search_context_size",
            "userLocation" to "user_location",
        )

        "file_search" -> args.rename(
            "vectorStoreIds" to "vector_store_ids",
            "maxNumResults" to "max_num_results",
            "ranking" to "ranking_options",
        ).replacing("ranking_options") { it.rename("scoreThreshold" to "score_threshold") }

        // `container` is a string (an existing container id) or an object naming files to seed a new
        // one, and OpenAI REQUIRES it: absent, it is sent as a fresh auto container, which is what the
        // reference sends and what the endpoint's own documentation demands. Only the object form
        // needs the `type` discriminator.
        "code_interpreter" -> buildJsonObject {
            args.forEach { (key, value) -> if (key != "container") put(key, value) }
            put("container", args["container"].codeInterpreterContainer())
        }

        "image_generation" -> args.rename(
            "inputFidelity" to "input_fidelity",
            "inputImageMask" to "input_image_mask",
            "partialImages" to "partial_images",
            "outputCompression" to "output_compression",
            "outputFormat" to "output_format",
        ).replacing("input_image_mask") { it.rename("fileId" to "file_id", "imageUrl" to "image_url") }

        // `headers` is deliberately absent from the rename list: its keys are HTTP header names the
        // caller chose, and renaming them would send `contentType` where the server expects
        // `Content-Type`.
        "mcp" -> args.rename(
            "serverLabel" to "server_label",
            "allowedTools" to "allowed_tools",
            "connectorId" to "connector_id",
            "requireApproval" to "require_approval",
            "serverDescription" to "server_description",
            "serverUrl" to "server_url",
        ).replacing("allowed_tools") { it.rename("readOnly" to "read_only", "toolNames" to "tool_names") }
            .replacing("require_approval") { it.replacing("never") { n -> n.rename("toolNames" to "tool_names") } }

        // `apply_patch` and `programmatic_tool_calling` are configured entirely by being present:
        // the reference emits a bare `{type}` for both and ignores whatever args ride along.
        "apply_patch", "programmatic_tool_calling" -> JsonObject(emptyMap())

        "tool_search" -> args.rename()

        // The one with real structure. Its `environment` is a discriminated union whose arm decides
        // both the wire `type` and which fields travel — and the local arm's `skills` are already
        // snake-free, where the container arms' are not.
        "shell" -> args.rename().replacing("environment") { it.shellEnvironment() }

        else -> args
    }

    return buildJsonObject {
        put("type", wireName)
        // A custom tool is the one provider tool whose NAME reaches the wire: the model calls it by name
        // and the reply names it back.
        if (wireName == "custom") put("name", tool.name)
        body.forEach { (key, value) -> if (key != "type" && key != "name") put(key, value) }
    }
}

/** An existing container's id passes through; absent or an object, it is OpenAI's auto container. */
private fun JsonElement?.codeInterpreterContainer(): JsonElement {
    if (this != null && this !is JsonObject) return this
    return buildJsonObject {
        put("type", "auto")
        (this@codeInterpreterContainer as? JsonObject)?.get("fileIds")?.let { put("file_ids", it) }
    }
}

/**
 * The `shell` tool's environment union, mapped arm by arm.
 *
 * A generic camelCase pass cannot do this: the arm's `type` is itself renamed
 * (`containerReference` becomes `container_reference`), and each arm carries a different field set —
 * sending a container arm's `file_ids` on a local environment is a field the endpoint rejects.
 */
private fun JsonObject.shellEnvironment(): JsonObject = when (this["type"]?.stringOrNullValue()) {
    "containerReference" -> buildJsonObject {
        put("type", "container_reference")
        this@shellEnvironment["containerId"]?.let { put("container_id", it) }
    }

    "containerAuto" -> buildJsonObject {
        put("type", "container_auto")
        this@shellEnvironment["fileIds"]?.let { put("file_ids", it) }
        this@shellEnvironment["memoryLimit"]?.let { put("memory_limit", it) }
        (this@shellEnvironment["networkPolicy"] as? JsonObject)?.let { policy ->
            put(
                "network_policy",
                if (policy["type"]?.stringOrNullValue() == "disabled") {
                    buildJsonObject { put("type", "disabled") }
                } else {
                    buildJsonObject {
                        put("type", "allowlist")
                        policy["allowedDomains"]?.let { put("allowed_domains", it) }
                        policy["domainSecrets"]?.let { put("domain_secrets", it) }
                    }
                },
            )
        }
        (this@shellEnvironment["skills"] as? JsonArray)?.let { put("skills", it.shellSkills()) }
    }

    // `local` is the default arm, and its skills are already in the vendor's spelling.
    else -> buildJsonObject {
        put("type", "local")
        this@shellEnvironment["skills"]?.let { put("skills", it) }
    }
}

/** A referenced skill resolves to its provider id; an inline one carries its own base64 payload. */
private fun JsonArray.shellSkills(): JsonArray = buildJsonArray {
    forEach { entry ->
        val skill = entry as? JsonObject ?: return@forEach
        add(
            if (skill["type"]?.stringOrNullValue() == "skillReference") {
                buildJsonObject {
                    put("type", "skill_reference")
                    (skill["providerReference"] as? JsonObject)?.get(OPENAI_PROVIDER_ID)
                        ?.let { put("skill_id", it) }
                    put("version", skill["version"] ?: JsonPrimitive("latest"))
                }
            } else {
                buildJsonObject {
                    put("type", "inline")
                    skill["name"]?.let { put("name", it) }
                    skill["description"]?.let { put("description", it) }
                    (skill["source"] as? JsonObject)?.let { source ->
                        put(
                            "source",
                            buildJsonObject {
                                put("type", "base64")
                                source["mediaType"]?.let { put("media_type", it) }
                                source["data"]?.let { put("data", it) }
                            },
                        )
                    }
                }
            },
        )
    }
}

private fun JsonElement.stringOrNullValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Copies every entry, renaming the ones named. An unknown key survives unchanged. */
private fun JsonObject.rename(vararg renames: Pair<String, String>): JsonObject {
    val table = renames.toMap()
    return buildJsonObject { forEach { (key, value) -> put(table[key] ?: key, value) } }
}

/** Rewrites one nested object in place, leaving a non-object value (or an absent key) alone. */
private fun JsonObject.replacing(key: String, transform: (JsonObject) -> JsonObject): JsonObject {
    val nested = this[key] as? JsonObject ?: return this
    return buildJsonObject {
        this@replacing.forEach { (k, v) -> put(k, if (k == key) transform(nested) else v) }
    }
}
