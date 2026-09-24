package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Warning
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The one config field a toolset member takes, plus the one that has to agree across all of them.
 *
 * Anything else in a member's value is rejected by the API, so it is refused here with a warning naming
 * the key rather than sent and turned into a 400 that names only the request.
 */
private val TOOLSET_MEMBER_FIELDS = setOf("enabled", "defer_loading", "deferLoading")

/** The `tools` array, the betas those tools need, and anything that had to be dropped. */
internal data class PreparedTools(
    val tools: List<JsonObject>?,
    val betas: Set<String>,
    val warnings: List<Warning>,
)

/**
 * Builds Anthropic's `tools` array.
 *
 * A function tool is its schema; a provider-defined tool is a `type` plus whichever of its arguments the
 * vendor reads, looked up in [AnthropicTools.wire] — the table the factories declare. An id with no entry
 * is warned about rather than dropped silently: that warning is the only signal a caller gets that the
 * capability it wired up is not there, and sending it under a guessed `type` would be a 400 naming only
 * the request.
 */
internal fun prepareAnthropicTools(
    tools: List<Tool>?,
    cacheControls: AnthropicCacheControlBudget,
    /** The model-level `toolStreaming` default; a tool's own `eagerInputStreaming` overrides it. */
    defaultEagerInputStreaming: Boolean = true,
): PreparedTools {
    if (tools.isNullOrEmpty()) return PreparedTools(null, emptySet(), emptyList())

    val betas = mutableSetOf<String>()
    val warnings = mutableListOf<Warning>()
    val declaredToolsets = mutableSetOf<String>()
    val out = tools.mapNotNull { tool ->
        when (tool) {
            is Tool.Function -> buildJsonObject {
                put("name", tool.name)
                tool.description?.let { put("description", it) }
                put("input_schema", tool.inputSchema)
                val perTool = tool.providerOptions?.get(ANTHROPIC_PROVIDER_ID)
                // Emitted only as `true`, never as `false` — the reference omits the falsy key, and
                // function tools are the only kind the field is defined on.
                if (perTool?.boolOrNull("eagerInputStreaming") ?: defaultEagerInputStreaming) {
                    put("eager_input_streaming", true)
                }
                // Both values are meaningful on the wire, so non-null is the emission test here.
                perTool?.boolOrNull("deferLoading")?.let { put("defer_loading", it) }
                cacheControls.take(tool.providerOptions, "tool definition", warnings)
                    ?.let { put("cache_control", it) }
            }

            is Tool.ProviderDefined -> {
                val spec = AnthropicTools.wire[tool.id]
                when {
                    spec == null -> {
                        warnings += Warning.Unsupported(feature = "provider-defined tool ${tool.id}")
                        null
                    }

                    spec.toolset != null ->
                        if (!declaredToolsets.add(spec.toolset)) {
                            // Two entries of the same toolset is an explicit rejection. Dropping the
                            // second keeps the first working, where sending both loses the whole request.
                            warnings += Warning.Unsupported(
                                feature = "provider-defined tool ${tool.id}",
                                details = "the ${spec.toolset} toolset is already declared; " +
                                    "the API rejects two entries of the same toolset.",
                            )
                            null
                        } else {
                            spec.beta?.let(betas::add)
                            toolsetEntry(spec, tool, warnings)
                        }

                    else -> {
                        spec.beta?.let(betas::add)
                        buildJsonObject {
                            put("type", spec.wireType)
                            put("name", spec.wireName)
                            spec.args.forEach { arg -> tool.args[arg]?.let { put(arg.snakeCased(), it) } }
                        }
                    }
                }
            }
        }
    }
    return PreparedTools(out.takeIf { it.isNotEmpty() }, betas, warnings)
}

/**
 * One client toolset entry: a dated `type`, optional per-member `configs`, and nothing else by default.
 *
 * The shape differs from a tool in the one way that matters — **no `name`** — because the dated `type`
 * fixes the member names. It also rejects the display arguments the dated `computer_*` tools take, so an
 * `args` list is deliberately absent from these specs rather than mapped and ignored.
 *
 * Four things the API refuses are refused here instead, each with a warning naming the offending key,
 * because a rejection at the vendor names only the request:
 *
 * - `defer_loading` on the ENTRY. It belongs per member, inside `configs`, and every enabled member has
 *   to carry the same value — the toolset loads and expands as one definition.
 * - a code-execution caller in `allowed_callers`. Only `["direct"]` is accepted.
 * - `configs` that disables every member. The way to send nothing is to send no entry.
 * - any member field other than `enabled` and `defer_loading`, or an unknown member name.
 *
 * `cache_control` is accepted on a toolset entry by the API but has no channel here:
 * [Tool.ProviderDefined] carries no `providerOptions`, and reading a breakpoint out of `args` would
 * bypass the four-breakpoint budget that every other cache marker is counted against. Closing that gap
 * means growing the spec type, not smuggling the field through this one.
 */
private fun toolsetEntry(
    spec: AnthropicProviderTool,
    tool: Tool.ProviderDefined,
    warnings: MutableList<Warning>,
): JsonObject {
    val feature = "provider-defined tool ${tool.id}"
    if (tool.args["deferLoading"] != null || tool.args["defer_loading"] != null) {
        warnings += Warning.Unsupported(
            feature = feature,
            details = "defer_loading belongs on each member inside configs, not on the toolset entry.",
        )
    }

    val callers = (tool.args["allowedCallers"] ?: tool.args["allowed_callers"]) as? JsonArray
    val directOnly = callers?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter { caller ->
            (caller == "direct").also {
                if (!it) {
                    warnings += Warning.Unsupported(
                        feature = feature,
                        details = "allowed_callers accepts only \"direct\" on a toolset; " +
                            "\"$caller\" was dropped.",
                    )
                }
            }
        }

    val configs = (tool.args["configs"] as? JsonObject)?.let { sanitizeToolsetConfigs(it, feature, warnings) }

    return buildJsonObject {
        put("type", spec.wireType)
        configs?.takeIf { it.isNotEmpty() }?.let { put("configs", it) }
        directOnly?.takeIf { it.isNotEmpty() }
            ?.let { put("allowed_callers", JsonArray(it.map(::JsonPrimitive))) }
    }
}

/**
 * Keeps the member settings the API defines and drops the rest, naming each one dropped.
 *
 * A `configs` that ends up disabling every member is dropped whole: the API rejects it, and the entry
 * without it is the same request minus a refusal.
 */
private fun sanitizeToolsetConfigs(
    configs: JsonObject,
    feature: String,
    warnings: MutableList<Warning>,
): JsonObject {
    val cleaned = configs.mapNotNull { (member, value) ->
        val settings = value as? JsonObject
        if (settings == null) {
            warnings += Warning.Unsupported(
                feature = feature,
                details = "the config for member \"$member\" must be an object; it was dropped.",
            )
            return@mapNotNull null
        }
        val kept = settings.filterKeys { key ->
            (key in TOOLSET_MEMBER_FIELDS).also {
                if (!it) {
                    warnings += Warning.Unsupported(
                        feature = feature,
                        details = "member \"$member\" accepts only enabled and defer_loading; " +
                            "\"$key\" was dropped.",
                    )
                }
            }
        }
        member to JsonObject(kept.mapKeys { (key, _) -> key.snakeCased() })
    }.toMap()

    val everyMemberDisabled = cleaned.isNotEmpty() &&
        cleaned.values.all { (it["enabled"] as? JsonPrimitive)?.contentOrNull == "false" }
    if (everyMemberDisabled) {
        warnings += Warning.Unsupported(
            feature = feature,
            details = "a configs that disables every member is rejected; omit the toolset entry instead.",
        )
        return JsonObject(emptyMap())
    }
    return JsonObject(cleaned)
}

/**
 * Anthropic's `tool_choice`.
 *
 * Anthropic spells "must call some tool" as `any`, where OpenAI says `required`. [ToolChoice.Auto]
 * still produces an object because `disable_parallel_tool_use` rides on it and has nowhere else to go;
 * with neither, the field is omitted and the server default is auto anyway.
 */
internal fun ToolChoice.toAnthropicToolChoice(disableParallelToolUse: Boolean?): JsonObject? = when (this) {
    // `none` is expressed by sending no tools at all — Anthropic has no `{"type":"none"}`.
    ToolChoice.None -> null
    ToolChoice.Auto -> if (disableParallelToolUse == null) {
        null
    } else {
        buildJsonObject {
            put("type", "auto")
            put("disable_parallel_tool_use", disableParallelToolUse)
        }
    }
    ToolChoice.Required -> buildJsonObject {
        put("type", "any")
        disableParallelToolUse?.let { put("disable_parallel_tool_use", it) }
    }
    is ToolChoice.Specific -> buildJsonObject {
        put("type", "tool")
        put("name", toolName)
        disableParallelToolUse?.let { put("disable_parallel_tool_use", it) }
    }
}
