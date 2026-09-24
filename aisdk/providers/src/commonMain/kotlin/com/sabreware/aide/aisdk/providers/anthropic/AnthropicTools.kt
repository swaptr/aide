package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.util.ProviderToolFactory
import com.sabreware.aide.aisdk.util.providerDefinedTool
import com.sabreware.aide.aisdk.util.providerExecutedTool
import com.sabreware.aide.aisdk.util.wireToolNames

/** `toolset_name`, as carried on a member's call and echoed on its result. */
public const val ANTHROPIC_TOOLSET_KEY: String = "toolsetName"

/**
 * Anthropic's own tools: web search and fetch, code execution, the advisor, tool search and the MCP
 * connector on the server side; bash, the text editor, memory, computer use and the browser on the
 * client side.
 *
 * Each is a [ProviderToolFactory], so a caller builds one the way the reference's
 * `anthropic.tools.webSearch_20250305({ maxUses: 3 })` allows:
 *
 * ```kotlin
 * AnthropicTools.webSearch_20250305(buildJsonObject { put("maxUses", 3) })
 * ```
 *
 * **The factories ARE the wire table.** Before this object existed the request builder read a private
 * map of wire facts keyed by id, and a caller could name a tool only by spelling its id string — two
 * lists of the same tools, kept in agreement by hand. Now each tool is declared ONCE, with the facts a
 * [ProviderToolFactory] carries (id, wire name, schemas, who executes it) and the ones only Anthropic's
 * request needs (the dated `type`, the beta header, which of the caller's arguments the vendor reads,
 * the toolset it forms). [wire] is derived from the declarations and [prepareAnthropicTools] reads
 * nothing else, so a tool that exists here exists on the wire, and one that does not is refused with a
 * warning rather than sent under a guessed type.
 *
 * **Who executes what is not a guess.** A server tool ([providerExecutedTool]) runs on Anthropic's side
 * and its result arrives inside the assistant turn; the runtime never dispatches one. A client tool
 * ([providerDefinedTool]) is a schema Anthropic wrote and trained the model on, and the application
 * still has to do the work. Getting this wrong is silent in both directions: a client tool marked
 * executed is never run, and a server tool marked client-side makes the loop wait for a result it is
 * supposed to be receiving.
 *
 * **Every server tool may answer late.** Anthropic's server-tools guide is explicit that a server tool
 * called in the same group of parallel calls as a client tool is NOT run until the client's results come
 * back — the response ends `tool_use` with a `server_tool_use` block and no result, and "an
 * `mcp_tool_use` block ... behaves the same way". So every server tool declares
 * `supportsDeferredResults`, matching the reference for the tools it has, except the Python-only
 * `code_execution_20250522`, which the reference leaves undeclared and this port follows.
 *
 * **The beta column is validated against Anthropic's own tool reference (checked 2026-09-01), not
 * against the vendored port.** That matters more than it looks: an unrecognized `anthropic-beta` value
 * is a 400 naming the header, so a beta attached to a tool that no longer needs one does not degrade —
 * it fails every request carrying that tool. The reference we ported from paired betas with
 * `bash_20250124`, `text_editor_*`, `memory_20250818`, `code_execution_20250825`, and the 2026-02-09 web
 * tools; the vendor documents all of those as requiring **none**, and says of bash in as many words that
 * it "requires no beta header". Only the genuinely legacy `*_20241022` versions, the two dated
 * `computer_*` versions, `advisor_20260301` and `mcp_toolset` still carry one.
 *
 * The wire name is fixed and shared — two web-search versions are both `web_search` — which is exactly
 * why [com.sabreware.aide.aisdk.util.ToolNameMapping] has to translate back: the response names the
 * tool `web_search`, and the caller knows it by whatever it called it.
 *
 * Five tools here have no counterpart in the reference and were read off the vendor's documentation:
 * `web_fetch_20260309`, `code_execution_20260521`, the two client toolsets and the MCP connector. The
 * later versions restate the schemas of the version they extend, because the vendor documents each as
 * the same tool with an option added. (The 2026-03-18 web tools began that way and are now in the
 * reference too, which is where their `useCache` and `responseInclusion` arguments come from.)
 */
public object AnthropicTools {

    /**
     * Declaration order IS registration order. Every factory below registers itself here as it is
     * built, and [all] and [wire] are read from the list LAST — an object initializes top to bottom, so
     * anything reading the list earlier would see a partial table.
     */
    private val registered = mutableListOf<AnthropicProviderTool>()

    private fun server(
        id: String,
        wireName: String,
        inputSchema: JsonSchema,
        outputSchema: JsonSchema,
        deferred: Boolean = true,
        beta: String? = null,
        args: List<String> = emptyList(),
        wireType: String = id.substringAfter('.'),
    ): ProviderToolFactory = register(
        factory = providerExecutedTool(
            id = id,
            wireName = wireName,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            supportsDeferredResults = deferred,
        ),
        wireType = wireType,
        beta = beta,
        args = args,
        toolset = null,
    )

    private fun client(
        id: String,
        wireName: String,
        inputSchema: JsonSchema,
        beta: String? = null,
        args: List<String> = emptyList(),
    ): ProviderToolFactory = register(
        factory = providerDefinedTool(id = id, wireName = wireName, inputSchema = inputSchema),
        wireType = id.substringAfter('.'),
        beta = beta,
        args = args,
        toolset = null,
    )

    /**
     * A client toolset. `name` is the toolset name rather than a tool name, because that is what a
     * member's call and its result both report; the entry itself sends no `name`.
     */
    private fun toolset(id: String, name: String): ProviderToolFactory = register(
        factory = providerDefinedTool(id = id, wireName = name, inputSchema = AnthropicToolSchemas.memberInput),
        wireType = id.substringAfter('.'),
        beta = null,
        args = emptyList(),
        toolset = name,
    )

    private fun register(
        factory: ProviderToolFactory,
        wireType: String,
        beta: String?,
        args: List<String>,
        toolset: String?,
    ): ProviderToolFactory {
        registered += AnthropicProviderTool(factory, wireType, beta, args, toolset)
        return factory
    }

    // --- advisor --------------------------------------------------------------------------------

    /**
     * A faster executor model consults a higher-intelligence advisor mid-generation, inside one
     * `/v1/messages` request: the advisor reads the executor's transcript and hands back a plan or a
     * course correction.
     *
     * `args`: `model` (REQUIRED by the API — the advisor's id, e.g. `claude-opus-4-8`; the factory
     * cannot insist on it, so a call without one is a 400 rather than a compile error), `maxUses` (a
     * per-request cap), `maxTokens` (per advisor call, thinking included; minimum 1024) and `caching`
     * (`{type: "ephemeral", ttl: "5m" | "1h"}`, worthwhile from about three advisor calls).
     *
     * On a follow-up turn keep the advisor in `tools` while the history holds `advisor_tool_result`
     * blocks; dropping it is a 400.
     */
    public val advisor_20260301: ProviderToolFactory = server(
        id = "anthropic.advisor_20260301",
        wireName = "advisor",
        inputSchema = AnthropicToolSchemas.advisorInput,
        outputSchema = AnthropicToolSchemas.advisorOutput,
        beta = "advisor-tool-2026-03-01",
        args = listOf("model", "maxUses", "maxTokens", "caching"),
    )

    // --- bash -----------------------------------------------------------------------------------

    /**
     * Shell commands in a persistent session the CLIENT runs. Image results are supported.
     *
     * The 2024-10-22 version is legacy and still needs `computer-use-2024-10-22`.
     */
    public val bash_20241022: ProviderToolFactory = client(
        id = "anthropic.bash_20241022",
        wireName = "bash",
        inputSchema = AnthropicToolSchemas.bashInput,
        beta = "computer-use-2024-10-22",
    )

    /** Shell commands in a persistent session the CLIENT runs. No beta: the vendor says so in as many words. */
    public val bash_20250124: ProviderToolFactory = client(
        id = "anthropic.bash_20250124",
        wireName = "bash",
        inputSchema = AnthropicToolSchemas.bashInput,
    )

    // --- code execution -------------------------------------------------------------------------

    /**
     * The legacy Python-only sandbox. The one server tool the reference does not mark deferrable, so
     * neither does this.
     */
    public val codeExecution_20250522: ProviderToolFactory = server(
        id = "anthropic.code_execution_20250522",
        wireName = "code_execution",
        inputSchema = AnthropicToolSchemas.pythonCodeExecutionInput,
        outputSchema = AnthropicToolSchemas.pythonCodeExecutionOutput,
        deferred = false,
        beta = "code-execution-2025-05-22",
    )

    /** Python, bash and file operations in Anthropic's sandbox. */
    public val codeExecution_20250825: ProviderToolFactory = server(
        id = "anthropic.code_execution_20250825",
        wireName = "code_execution",
        inputSchema = AnthropicToolSchemas.codeExecutionInput,
        outputSchema = AnthropicToolSchemas.codeExecutionOutput(encryptedResults = false),
    )

    /**
     * Adds REPL state persistence and programmatic tool calling from inside the sandbox — which is why
     * its results can be encrypted, and why a result may wait on a client tool the code called.
     */
    public val codeExecution_20260120: ProviderToolFactory = server(
        id = "anthropic.code_execution_20260120",
        wireName = "code_execution",
        inputSchema = AnthropicToolSchemas.codeExecutionInput,
        outputSchema = AnthropicToolSchemas.codeExecutionOutput(encryptedResults = true),
    )

    /**
     * The same runtime as [codeExecution_20260120]; the tool description additionally tells the model
     * about the 90-second limit on each Python cell so it can budget long-running work. Vendor-documented,
     * not in the reference.
     */
    public val codeExecution_20260521: ProviderToolFactory = server(
        id = "anthropic.code_execution_20260521",
        wireName = "code_execution",
        inputSchema = AnthropicToolSchemas.codeExecutionInput,
        outputSchema = AnthropicToolSchemas.codeExecutionOutput(encryptedResults = true),
    )

    // --- computer use ---------------------------------------------------------------------------

    /**
     * Screenshots plus mouse and keyboard control, executed by the CLIENT. Image results are supported.
     *
     * `args`: `displayWidthPx` and `displayHeightPx` (required), `displayNumber` (X11 only).
     */
    public val computer_20241022: ProviderToolFactory = client(
        id = "anthropic.computer_20241022",
        wireName = "computer",
        inputSchema = AnthropicToolSchemas.computerInput20241022,
        beta = "computer-use-2024-10-22",
        args = listOf("displayWidthPx", "displayHeightPx", "displayNumber"),
    )

    /** As [computer_20241022], with more actions (hold, scroll, wait, triple-click) and typed coordinates. */
    public val computer_20250124: ProviderToolFactory = client(
        id = "anthropic.computer_20250124",
        wireName = "computer",
        inputSchema = AnthropicToolSchemas.computerInput20250124,
        beta = "computer-use-2025-01-24",
        args = listOf("displayWidthPx", "displayHeightPx", "displayNumber"),
    )

    /**
     * Adds the `zoom` action for inspecting a screen region at full resolution.
     *
     * `args` additionally accepts `enableZoom`, off by default.
     */
    public val computer_20251124: ProviderToolFactory = client(
        id = "anthropic.computer_20251124",
        wireName = "computer",
        inputSchema = AnthropicToolSchemas.computerInput20251124,
        beta = "computer-use-2025-11-24",
        args = listOf("displayWidthPx", "displayHeightPx", "displayNumber", "enableZoom"),
    )

    // --- memory ---------------------------------------------------------------------------------

    /**
     * A memory directory the CLIENT stores, so the model can keep knowledge across conversations without
     * carrying it in the context window.
     */
    public val memory_20250818: ProviderToolFactory = client(
        id = "anthropic.memory_20250818",
        wireName = "memory",
        inputSchema = AnthropicToolSchemas.memoryInput,
    )

    // --- text editor ----------------------------------------------------------------------------

    /** View and modify text files, executed by the CLIENT. Legacy: Claude Sonnet 3.5, `undo_edit` included. */
    public val textEditor_20241022: ProviderToolFactory = client(
        id = "anthropic.text_editor_20241022",
        wireName = "str_replace_editor",
        inputSchema = AnthropicToolSchemas.textEditorInputWithUndo,
        beta = "computer-use-2024-10-22",
    )

    /** The text editor for Claude Sonnet 3.7. */
    public val textEditor_20250124: ProviderToolFactory = client(
        id = "anthropic.text_editor_20250124",
        wireName = "str_replace_editor",
        inputSchema = AnthropicToolSchemas.textEditorInputWithUndo,
    )

    /**
     * The first Claude 4 editor, without `undo_edit`. The reference marks it deprecated in favour of
     * [textEditor_20250728]; it is kept because the vendor still serves it.
     */
    public val textEditor_20250429: ProviderToolFactory = client(
        id = "anthropic.text_editor_20250429",
        wireName = "str_replace_based_edit_tool",
        inputSchema = AnthropicToolSchemas.textEditorInput,
    )

    /**
     * The current Claude 4 editor. `args`: `maxCharacters`, a cap on how much of a file `view` returns.
     */
    public val textEditor_20250728: ProviderToolFactory = client(
        id = "anthropic.text_editor_20250728",
        wireName = "str_replace_based_edit_tool",
        inputSchema = AnthropicToolSchemas.textEditorInput,
        args = listOf("maxCharacters"),
    )

    // --- tool search ----------------------------------------------------------------------------

    /**
     * Discover tools on demand by regex, so a catalogue of thousands need not sit in the prompt. Other
     * tools opt in with `providerOptions: {anthropic: {deferLoading: true}}`; this one must never defer.
     *
     * The one family whose wire `type` is not the id's tail: `tool_search_tool_regex_20251119`.
     */
    public val toolSearchRegex_20251119: ProviderToolFactory = server(
        id = "anthropic.tool_search_regex_20251119",
        wireName = "tool_search_tool_regex",
        inputSchema = AnthropicToolSchemas.toolSearchRegexInput,
        outputSchema = AnthropicToolSchemas.toolSearchOutput,
        wireType = "tool_search_tool_regex_20251119",
    )

    /** As [toolSearchRegex_20251119], ranking by BM25 over a natural-language query instead. */
    public val toolSearchBm25_20251119: ProviderToolFactory = server(
        id = "anthropic.tool_search_bm25_20251119",
        wireName = "tool_search_tool_bm25",
        inputSchema = AnthropicToolSchemas.toolSearchBm25Input,
        outputSchema = AnthropicToolSchemas.toolSearchOutput,
        wireType = "tool_search_tool_bm25_20251119",
    )

    // --- web fetch ------------------------------------------------------------------------------

    /**
     * Fetch a page or a PDF the conversation already mentions.
     *
     * `args`: `maxUses`, `allowedDomains`, `blockedDomains` (one or the other, never both), `citations`
     * (`{enabled: true}`; off by default, unlike web search) and `maxContentTokens`.
     */
    public val webFetch_20250910: ProviderToolFactory = server(
        id = "anthropic.web_fetch_20250910",
        wireName = "web_fetch",
        inputSchema = AnthropicToolSchemas.webFetchInput,
        outputSchema = AnthropicToolSchemas.webFetchOutput,
        args = WEB_FETCH_ARGS,
    )

    /** Adds dynamic filtering: the model runs code over the page before it enters the context window. */
    public val webFetch_20260209: ProviderToolFactory = server(
        id = "anthropic.web_fetch_20260209",
        wireName = "web_fetch",
        inputSchema = AnthropicToolSchemas.webFetchInput,
        outputSchema = AnthropicToolSchemas.webFetchOutput,
        args = WEB_FETCH_ARGS,
    )

    /** Adds cache bypass. Vendor-documented, not in the reference. */
    public val webFetch_20260309: ProviderToolFactory = server(
        id = "anthropic.web_fetch_20260309",
        wireName = "web_fetch",
        inputSchema = AnthropicToolSchemas.webFetchInput,
        outputSchema = AnthropicToolSchemas.webFetchOutput,
        args = WEB_FETCH_ARGS,
    )

    /**
     * Adds two knobs for agentic loops: `useCache` (`false` fetches fresh content rather than a cached
     * copy) and `responseInclusion` (`excluded` drops result blocks a completed code-execution call
     * already consumed, so a long loop does not carry every page it filtered). Defaults are `true`
     * and `full`.
     */
    public val webFetch_20260318: ProviderToolFactory = server(
        id = "anthropic.web_fetch_20260318",
        wireName = "web_fetch",
        inputSchema = AnthropicToolSchemas.webFetchInput,
        outputSchema = AnthropicToolSchemas.webFetchOutput,
        args = WEB_FETCH_ARGS_20260318,
    )

    // --- web search -----------------------------------------------------------------------------

    /**
     * Real-time web search with citations.
     *
     * `args`: `maxUses`, `allowedDomains`, `blockedDomains` (one or the other, never both) and
     * `userLocation` (`{type: "approximate", city, region, country, timezone}`).
     */
    public val webSearch_20250305: ProviderToolFactory = server(
        id = "anthropic.web_search_20250305",
        wireName = "web_search",
        inputSchema = AnthropicToolSchemas.webSearchInput,
        outputSchema = AnthropicToolSchemas.webSearchOutput,
        args = WEB_SEARCH_ARGS,
    )

    /** Adds dynamic filtering: the model runs code over the results before they enter the context window. */
    public val webSearch_20260209: ProviderToolFactory = server(
        id = "anthropic.web_search_20260209",
        wireName = "web_search",
        inputSchema = AnthropicToolSchemas.webSearchInput,
        outputSchema = AnthropicToolSchemas.webSearchOutput,
        args = WEB_SEARCH_ARGS,
    )

    /**
     * Adds `responseInclusion`: `excluded` drops result blocks a completed code-execution call already
     * consumed, so an agentic loop does not carry every result it filtered. Defaults to `full`.
     */
    public val webSearch_20260318: ProviderToolFactory = server(
        id = "anthropic.web_search_20260318",
        wireName = "web_search",
        inputSchema = AnthropicToolSchemas.webSearchInput,
        outputSchema = AnthropicToolSchemas.webSearchOutput,
        args = WEB_SEARCH_ARGS_20260318,
    )

    // --- toolsets -------------------------------------------------------------------------------

    /**
     * The client toolset that succeeds the dated `computer_*` tools: one entry declares seventeen
     * members (`screenshot`, `zoom`, the clicks, `type`, `key`, `scroll`, ...) the CLIENT executes, and
     * every call and result carries `toolset_name: "computer"`.
     *
     * `args`: `configs` (per-member `enabled` / `deferLoading`) and `allowedCallers` (`["direct"]` only).
     * It takes no display arguments; coordinates are in the pixel space of the screenshots returned.
     */
    public val computerToolset_20260801: ProviderToolFactory =
        toolset(id = "anthropic.computer_toolset_20260801", name = "computer")

    /**
     * The browser-use toolset: the CLIENT drives a browser and returns page text or screenshots, in a
     * coordinate frame independent of [computerToolset_20260801]. Same `args`; the two may be declared
     * together.
     */
    public val browserToolset_20260801: ProviderToolFactory =
        toolset(id = "anthropic.browser_toolset_20260801", name = "browser")

    /**
     * The MCP connector: Anthropic calls a remote MCP server's tools on the caller's behalf, answering
     * with `mcp_tool_use` / `mcp_tool_result` blocks. Not date-versioned — the version rides in the beta
     * header instead. A SERVER tool that happens to be called a toolset: its entry is an ordinary one,
     * and `args` pass through under their snake_case spelling, exactly as the table sent them before the
     * factories existed.
     */
    public val mcpToolset: ProviderToolFactory = server(
        id = "anthropic.mcp_toolset",
        wireName = "mcp_toolset",
        inputSchema = AnthropicToolSchemas.memberInput,
        outputSchema = AnthropicToolSchemas.mcpToolsetOutput,
        beta = "mcp-client-2025-11-20",
        args = listOf("mcpServers", "configs"),
    )

    /** Every Anthropic tool, for a caller offering the lot and for [anthropicProviderToolNames]. */
    public val all: List<ProviderToolFactory> = registered.map { it.factory }

    /** The wire facts by id, for [prepareAnthropicTools]. Derived from the factories, never edited. */
    internal val wire: Map<String, AnthropicProviderTool> = registered.associateBy { it.id }

    private val WEB_FETCH_ARGS: List<String>
        get() = listOf("maxUses", "allowedDomains", "blockedDomains", "citations", "maxContentTokens")

    private val WEB_SEARCH_ARGS: List<String>
        get() = listOf("maxUses", "allowedDomains", "blockedDomains", "userLocation")

    private val WEB_FETCH_ARGS_20260318: List<String>
        get() = WEB_FETCH_ARGS + listOf("useCache", "responseInclusion")

    private val WEB_SEARCH_ARGS_20260318: List<String>
        get() = WEB_SEARCH_ARGS + "responseInclusion"
}

/**
 * One tool's wire facts: the [factory] a caller builds it through, plus what only Anthropic's request
 * body needs to know about it.
 */
internal class AnthropicProviderTool(
    val factory: ProviderToolFactory,
    /** The dated `type` string. Usually the id's tail; the tool-search pair spell theirs differently. */
    val wireType: String,
    /** The beta this tool needs, or null where it is generally available. */
    val beta: String?,
    /** Argument names the vendor reads, in this tool's own `camelCase` spelling. */
    val args: List<String>,
    /**
     * The `toolset_name` this entry's members report, for a CLIENT TOOLSET rather than a tool.
     *
     * Null for an ordinary tool. Non-null switches both halves of the wire: the request entry carries no
     * `name` at all (the dated `type` fixes the member names) and declares members through `configs`,
     * and every result must echo this value back or the API rejects it.
     */
    val toolset: String?,
) {
    val id: String get() = factory.id
    val wireName: String get() = factory.wireName
}

/** Tool id to the name the vendor uses on the wire, for [com.sabreware.aide.aisdk.util.ToolNameMapping]. */
internal val anthropicProviderToolNames: Map<String, String> = AnthropicTools.all.wireToolNames()
