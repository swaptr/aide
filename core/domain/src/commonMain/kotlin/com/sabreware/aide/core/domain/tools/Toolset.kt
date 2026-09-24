package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.permission.CategoryRequirement

/**
 * One category's worth of tools, describing itself.
 *
 * Everything the rest of the app used to know about a toolset by name — which prompt heading its tools sit
 * under, what Settings calls it, whether it needs a runtime permission, whether its schemas are advertised
 * eagerly — is declared here, as data. That is what turns "add a tool" from a seven-place checklist (an enum
 * entry, two prompt tables, a lazy list, a permissions table, a registry constructor parameter, a
 * `buildList` line) into new files plus one binding.
 *
 * Implementations live wherever their dependencies do: a device toolset in an Android-only module, a
 * portable one in `:core:domain`. A platform gets the toolsets whose modules its application depends on.
 */
interface Toolset {

    /** The category these tools belong to. Stamped onto every tool this toolset produces. */
    val category: ToolCategory

    /** Settings row title. */
    val displayName: String

    /** Settings row subtitle: what enabling this lets the assistant do. */
    val blurb: String

    /**
     * True when the schemas stay behind `RequestToolset` until the model asks for them. Worth it for a broad
     * category the average turn does not need; not worth it for a small, latency-sensitive one (a flashlight
     * toggle would pay a whole extra round trip).
     */
    val onDemand: Boolean get() = false

    /** What the user must grant before the category can be enabled. */
    val requirement: CategoryRequirement get() = CategoryRequirement.None

    /**
     * The tools, for one bundle. Called per session because a toolset may need the [ToolsetScope] — the
     * surface it is dispatching from, or whether its gate is open — and may legitimately return nothing
     * (a filesystem toolset with no granted roots would otherwise hand the model a tool it cannot call).
     */
    fun tools(scope: ToolsetScope): List<AideTool>

    /**
     * Guidance for the system prompt that the tool schemas cannot express — which filesystem roots the user
     * granted, say. Null for the toolsets that need none, which is most of them.
     */
    fun promptNotes(scope: ToolsetScope): String? = null
}

/** What a [Toolset] may need to know when it builds its tools for one session. */
data class ToolsetScope(
    val surface: Surface,
    /** Gates the user opened for this turn (web search today). */
    val enabledGated: Set<ToolGate>,
)

/**
 * The contributed toolsets — the tool stack's peer of the provider and asset-source registries. Collected
 * with `getAll`, in binding order, which is also the order the prompt advertises categories in.
 */
class ToolsetRegistry(val toolsets: List<Toolset>) {

    init {
        val clash = toolsets.groupBy { it.category }.filterValues { it.size > 1 }.keys
        require(clash.isEmpty()) { "Two toolsets claim the same category: ${clash.joinToString()}" }
    }

    /** Every category on offer, in prompt/Settings order. */
    val categories: List<ToolCategory> get() = toolsets.map { it.category }

    /** Categories whose schemas load only after `RequestToolset`. */
    val onDemandCategories: List<ToolCategory> get() = toolsets.filter { it.onDemand }.map { it.category }

    operator fun get(category: ToolCategory): Toolset? = toolsets.firstOrNull { it.category == category }
}
