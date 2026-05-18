package com.swaptr.aide.domain.tools

import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.data.prefs.ToolCategory
import com.swaptr.aide.data.search.DuckDuckGoSearchClient
import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.Surface
import com.swaptr.aide.domain.llm.ToolActivationState
import com.swaptr.aide.domain.llm.ToolEnvelope
import com.swaptr.aide.domain.llm.gates.WriteConfirmGate
import com.swaptr.aide.domain.search.ProviderChain
import com.swaptr.aide.domain.search.WebSearchProvider
import com.swaptr.aide.domain.tools.calendar.CalendarToolset
import com.swaptr.aide.domain.tools.clipboard.ClipboardToolset
import com.swaptr.aide.domain.tools.contacts.ContactsToolset
import com.swaptr.aide.domain.tools.fs.FileSystemRoots
import com.swaptr.aide.domain.tools.fs.FileSystemToolset
import com.swaptr.aide.intent.IntentDispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object AideToolRegistry {

    enum class Gated { WEB_SEARCH, FILESYSTEM }

    data class Bundle(
        val tools: List<AideTool>,
        val systemPrompt: String?,
        val webSearchToolset: WebSearchToolset?,
        val webFetchToolset: WebFetchToolset?,
        val fileSystemToolset: FileSystemToolset?,
        val activationState: ToolActivationState,
    )

    // Order determines the order the prompt advertises these as available-on-demand.
    private val LAZY_CATEGORIES = listOf(
        "Clock", "Phone", "Calendar", "Contacts", "Clipboard",
    )

    fun build(
        providerId: ProviderId,
        supportsTools: Boolean,
        enabledGated: Set<Gated>,
        enabledCategories: Set<ToolCategory>,
        webSearch: WebSearchProvider,
        webSearchChain: ProviderChain,
        webSearchDisplayName: String,
        fetchClient: DuckDuckGoSearchClient,
        roots: FileSystemRoots,
        confirmGate: WriteConfirmGate,
        clockToolset: ClockToolset,
        phoneToolset: PhoneToolset,
        calendarToolset: CalendarToolset,
        contactsToolset: ContactsToolset,
        clipboardToolset: ClipboardToolset,
        intentDispatchers: IntentDispatchers,
        surface: Surface = Surface.CHAT,
    ): Bundle {
        val activationState = ToolActivationState()
        if (!supportsTools) {
            return Bundle(emptyList(), null, null, null, null, activationState)
        }

        val timeset = TimeToolset()
        val calcset = CalculatorToolset()
        val fetchset = WebFetchToolset(fetchClient)
        val webset = if (Gated.WEB_SEARCH in enabledGated) {
            WebSearchToolset(webSearchChain, webSearchDisplayName)
        } else null

        // Filesystem tools need at least one granted root — registering them with
        // no roots leaves the model with a tool it can't call.
        val rootSnapshot = roots.snapshot()
        val filesystemReady = Gated.FILESYSTEM in enabledGated && rootSnapshot.isNotEmpty()
        val fsset = if (filesystemReady) FileSystemToolset(roots, confirmGate) else null

        val intentDispatcher = intentDispatchers.forSurface(surface)
        val enabledLabels = enabledCategories.map { it.promptLabel }.toSet()
        val tools = buildList<AideTool> {
            add(timeset.asAideTool())
            add(calcset.asAideTool())
            add(fetchset.asAideTool())
            addAll(clockToolset.asAideTools(intentDispatcher))
            addAll(phoneToolset.asAideTools(intentDispatcher))
            addAll(calendarToolset.asAideTools(intentDispatcher))
            addAll(contactsToolset.asAideTools())
            addAll(clipboardToolset.asAideTools())
            if (webset != null) add(webset.asAideTool())
            if (fsset != null) addAll(fsset.asAideTools())
        }.filter { tool ->
            when (tool) {
                is AideTool.Function -> true
                is AideTool.ProviderNative -> providerId in tool.scope
            }
        }.filter { tool ->
            surface in tool.surfaces
        }.filter { tool ->
            // "Other" bypass: cross-cutting/builtin tools without a mapped category are never hidden.
            val cat = SystemPromptBuilder.categoryOf(tool.name)
            cat == "Other" || cat in enabledLabels
        }.map { tool ->
            if (tool is AideTool.Function) tool.withCrossCuttingProps() else tool
        }.map { tool ->
            if (tool is AideTool.Function) tool.withActivationCategory() else tool
        } + requestToolsetMetaTool(activationState, categories = LAZY_CATEGORIES)

        return Bundle(
            tools = tools,
            systemPrompt = SystemPromptBuilder.build(
                surface = surface,
                tools = tools,
                webSearchEnabled = webset != null,
                filesystemReady = filesystemReady,
                filesystemRequestedButEmpty = Gated.FILESYSTEM in enabledGated && rootSnapshot.isEmpty(),
                rootsSummary = roots.availableKeysSummary(),
                lazyCategories = LAZY_CATEGORIES,
            ),
            webSearchToolset = webset,
            webFetchToolset = fetchset,
            fileSystemToolset = fsset,
            activationState = activationState,
        )
    }

    private fun requestToolsetMetaTool(
        state: ToolActivationState,
        categories: List<String>,
    ): AideTool.Function {
        val knownCategories = categories.toSet()
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
                    category.isBlank() -> ToolEnvelope.failure(
                        "INVALID_ARGS",
                        "category is required",
                    )
                    category !in knownCategories -> ToolEnvelope.failure(
                        "INVALID_ARGS",
                        "unknown category '$category'; valid: ${knownCategories.joinToString()}",
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

    private fun AideTool.Function.withActivationCategory(): AideTool.Function {
        if (category != null) return this
        val cat = SystemPromptBuilder.categoryOf(name)
        val lazy = cat in LAZY_CATEGORIES
        return copy(category = cat, requiresActivation = lazy)
    }
}

// Cross-cutting props (idempotency_key, __trace_id) per R3 / R7 — picked up by the
// dispatcher so each tool doesn't re-declare them.
private fun AideTool.Function.withCrossCuttingProps(): AideTool.Function {
    val current = parametersSchema
    val type = current["type"] ?: kotlinx.serialization.json.JsonPrimitive("object")
    val required = current["required"]
    val props = current["properties"] as? kotlinx.serialization.json.JsonObject
        ?: kotlinx.serialization.json.JsonObject(emptyMap())

    val idempotencyKeyProp = kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("string"))
        put(
            "description",
            kotlinx.serialization.json.JsonPrimitive(
                "Optional client-supplied dedup key. If two calls with the same key " +
                    "arrive inside the 5-minute idempotency window, the second returns " +
                    "the first's cached envelope with `idempotent_hit: true`.",
            ),
        )
    }
    val traceIdProp = kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("string"))
        put(
            "description",
            kotlinx.serialization.json.JsonPrimitive(
                "Optional client-side span name echoed in traces. No semantic meaning.",
            ),
        )
    }

    val mergedProps = kotlinx.serialization.json.buildJsonObject {
        props.forEach { (k, v) -> put(k, v) }
        if ("idempotency_key" !in props) put("idempotency_key", idempotencyKeyProp)
        if ("__trace_id" !in props) put("__trace_id", traceIdProp)
    }
    val newSchema = kotlinx.serialization.json.buildJsonObject {
        put("type", type)
        put("properties", mergedProps)
        if (required != null) put("required", required)
    }
    return copy(parametersSchema = newSchema)
}
