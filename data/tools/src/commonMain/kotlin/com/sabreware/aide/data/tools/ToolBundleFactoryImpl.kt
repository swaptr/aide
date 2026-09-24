package com.sabreware.aide.data.tools

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.mcp.McpConnections
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.tools.SystemPromptBuilder
import com.sabreware.aide.core.domain.tools.ToolBundle
import com.sabreware.aide.core.domain.tools.ToolBundleFactory
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.ToolGate
import com.sabreware.aide.core.domain.tools.ToolsetRegistry
import com.sabreware.aide.core.domain.tools.ToolsetScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Assembles a session's tools from whatever toolsets the running application contributed.
 *
 * It names no toolset. Where the previous implementation took fourteen concrete toolsets as constructor
 * parameters and listed them by hand — so a new tool meant editing this class, an enum, two prompt tables
 * and a lazy list — it now walks the [ToolsetRegistry], stamps each tool with its owner's category, and
 * applies the four filters that are genuinely general: the provider must support the tool, the surface must
 * allow it, the user must have enabled its category, and cross-cutting tools are never hidden.
 *
 * That is also why this is common code: a target's tool set is the toolsets its application depends on, so
 * desktop no longer needs an empty-bundle stub.
 */
class ToolBundleFactoryImpl(
    private val toolsets: ToolsetRegistry,
    private val mcp: McpConnections,
) : ToolBundleFactory {

    override fun build(
        providerId: ProviderId,
        supportsTools: Boolean,
        enabledGated: Set<ToolGate>,
        enabledCategories: Set<ToolCategory>,
        surface: Surface,
    ): ToolBundle {
        val activationState = ToolActivationState()
        if (!supportsTools) return ToolBundle(emptyList(), null, activationState)

        val scope = ToolsetScope(surface = surface, enabledGated = enabledGated)

        // Every contributed tool, stamped with the category of the toolset that produced it. The stamp is
        // what replaced a hand-maintained tool-name → category table.
        val contributed = toolsets.toolsets.flatMap { toolset ->
            toolset.tools(scope).map { it.inCategory(toolset.category, onDemand = toolset.onDemand) }
        }
        // MCP tools arrive from connected servers at runtime, so they have no owning toolset; they are
        // cross-cutting and always visible, like the meta-tool below.
        val dynamic = mcp.currentTools().map { it.inCategory(ToolCategory.Other, onDemand = false) }

        val visible = (contributed + dynamic)
            .filter { tool ->
                when (tool) {
                    is AideTool.Function -> true
                    is AideTool.ProviderNative -> providerId in tool.scope
                }
            }
            .filter { surface in it.surfaces }
            .filter { tool ->
                // Cross-cutting tools are never hidden: the user has expressed no opinion about a category
                // that does not appear in Settings.
                val category = tool.categoryOrOther()
                category == ToolCategory.Other || category in enabledCategories
            }
            .map { if (it is AideTool.Function) it.withCrossCuttingProps() else it }

        val onDemandCategories = toolsets.toolsets
            .filter { it.onDemand && it.category in enabledCategories }
            .map { it.category.id }

        // Derived from the tools' own declared surfaces, so the "switch to chat" hint cannot drift from
        // what is actually reachable. Computed before the surface filter, which is the point.
        val chatOnlyToolNames = contributed
            .filter { Surface.CHAT in it.surfaces && Surface.IME !in it.surfaces }
            .map { it.name }

        val notes = toolsets.toolsets
            .filter { it.category in enabledCategories }
            .mapNotNull { it.promptNotes(scope) }

        val tools = visible + requestToolsetMetaTool(activationState, onDemandCategories)

        return ToolBundle(
            tools = tools,
            systemPrompt = SystemPromptBuilder.build(
                surface = surface,
                tools = tools,
                categoryOrder = toolsets.categories.map { it.id } + ToolCategory.Other.id,
                webSearchEnabled = ToolGate.WEB_SEARCH in enabledGated,
                onDemandCategories = onDemandCategories,
                toolsetNotes = notes,
                chatOnlyToolNames = chatOnlyToolNames,
            ),
            activationState = activationState,
        )
    }

    private fun AideTool.categoryOrOther(): ToolCategory =
        (this as? AideTool.Function)?.category?.let(::ToolCategory) ?: ToolCategory.Other

    private fun AideTool.inCategory(category: ToolCategory, onDemand: Boolean): AideTool =
        if (this !is AideTool.Function || this.category != null) this
        else copy(category = category.id, requiresActivation = onDemand)

    private fun requestToolsetMetaTool(
        state: ToolActivationState,
        categories: List<String>,
    ): AideTool.Function {
        val known = categories.toSet()
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("category", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", JsonArray(categories.map { JsonPrimitive(it) }))
                    put(
                        "description",
                        JsonPrimitive(
                            "Lazy tool category to load for the remainder of this " +
                                "conversation. Call once when the user's request " +
                                "clearly needs tools in that area (e.g. 'Clock' for " +
                                "alarms/timers, 'Calendar' for events).",
                        ),
                    )
                })
            })
            put("required", JsonArray(listOf(JsonPrimitive("category"))))
        }
        return AideTool.Function(
            name = "RequestToolset",
            description = "Activate a lazy-loaded tool category for this conversation.",
            parametersSchema = schema,
            handler = { args ->
                val category = (args["category"] as? JsonPrimitive)?.content.orEmpty()
                when {
                    category.isBlank() -> ToolEnvelope.failure("INVALID_ARGS", "category is required")
                    category !in known -> ToolEnvelope.failure(
                        "INVALID_ARGS",
                        "unknown category '$category'; valid: ${known.joinToString()}",
                    )
                    else -> {
                        val newlyAdded = state.activate(category)
                        ToolEnvelope.success {
                            put("category", JsonPrimitive(category))
                            put("already_active", JsonPrimitive(!newlyAdded))
                        }
                    }
                }
            },
            surfaces = setOf(Surface.CHAT, Surface.IME, Surface.VOICE),
            maxCallsPerTurn = 4,
            errorCodes = setOf("INVALID_ARGS"),
            promptDoc = "Loads schemas for one category at a time. After the call " +
                "succeeds, the tools in that category appear in the next request " +
                "you receive and you can call them normally.",
        )
    }
}

// Cross-cutting props (idempotency_key, __trace_id) per R3 / R7 — picked up by the dispatcher so each tool
// doesn't re-declare them.
private fun AideTool.Function.withCrossCuttingProps(): AideTool.Function {
    val current = parametersSchema
    val type = current["type"] ?: JsonPrimitive("object")
    val required = current["required"]
    val props = current["properties"] as? JsonObject ?: JsonObject(emptyMap())

    val idempotencyKeyProp = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put(
            "description",
            JsonPrimitive(
                "Optional client-supplied dedup key. If two calls with the same key " +
                    "arrive inside the 5-minute idempotency window, the second returns " +
                    "the first's cached envelope with `idempotent_hit: true`.",
            ),
        )
    }
    val traceIdProp = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put(
            "description",
            JsonPrimitive("Optional client-side span name echoed in traces. No semantic meaning."),
        )
    }

    val mergedProps = buildJsonObject {
        props.forEach { (k, v) -> put(k, v) }
        if ("idempotency_key" !in props) put("idempotency_key", idempotencyKeyProp)
        if ("__trace_id" !in props) put("__trace_id", traceIdProp)
    }
    val newSchema = buildJsonObject {
        put("type", type)
        put("properties", mergedProps)
        if (required != null) put("required", required)
    }
    return copy(parametersSchema = newSchema)
}
