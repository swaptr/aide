package com.sabreware.aide.aisdk

/**
 * Everything one call to a [LanguageModel] needs.
 *
 * Every sampler knob is nullable and defaults to null, and null means OMIT THE FIELD — not "send the
 * default". The distinction is not cosmetic: several hosted models reject an explicitly-sent
 * `temperature` that happens to equal their own default, and a client that cannot express "unset" ends
 * up sending values the user never chose.
 *
 * There is no abort parameter. Cancellation is coroutine cancellation, which every provider gets for
 * free by virtue of `doStream` returning a [kotlinx.coroutines.flow.Flow].
 */
public data class CallOptions(
    /** The conversation to continue — see [Prompt]. */
    val prompt: Prompt,
    /** Cap on generated tokens. Null lets the vendor apply its own ceiling. */
    val maxOutputTokens: Int? = null,
    /** Sampling temperature, in the vendor's own scale — the ranges differ, so no clamping here. */
    val temperature: Double? = null,
    /** Sequences that end generation the moment the model emits one. */
    val stopSequences: List<String>? = null,
    /** Nucleus sampling. Vendors advise setting this or [temperature], not both. */
    val topP: Double? = null,
    /** Sample only from the K most likely tokens. */
    val topK: Int? = null,
    /** Penalty on tokens that have appeared at all — nudges the model toward new topics. */
    val presencePenalty: Double? = null,
    /** Penalty on tokens proportional to their repetition — damps verbatim loops. */
    val frequencyPenalty: Double? = null,
    /** Deterministic sampling, where the vendor honours one — same seed, same output. */
    val seed: Int? = null,
    /** Text or JSON output — see [ResponseFormat]. */
    val responseFormat: ResponseFormat? = null,
    /** The tools on offer for this call. Null offers none. */
    val tools: List<Tool>? = null,
    /** How the model may use [tools] — see [ToolChoice]. Null leaves it to the vendor. */
    val toolChoice: ToolChoice? = null,
    /** How hard the model should think. See [ReasoningEffort]. */
    val reasoning: ReasoningEffort = ReasoningEffort.ProviderDefault,
    /** Extra HTTP headers, for providers that speak HTTP. */
    val headers: Map<String, String>? = null,
    /** Emit the untouched provider payload alongside the mapped parts. Debugging aid; off by default. */
    val includeRawChunks: Boolean = false,
    /**
     * Provider-namespaced options, passed through verbatim — see [ProviderOptions].
     *
     * The escape hatch the whole design rests on: any vendor knob this type does not model — cache
     * control, a thinking budget, safety settings — rides here under the provider's own id, opaque to
     * every layer between the caller and the one provider that reads it. Without it, every new vendor
     * feature would be a change to this class.
     */
    val providerOptions: ProviderOptions? = null,
)

/**
 * Requested reasoning depth, provider-agnostic.
 *
 * [ProviderDefault] is distinct from [None]: the first sends nothing and lets the model do whatever it
 * does, the second actively asks for no reasoning. Providers differ on whether the second is even
 * expressible — a model with always-on thinking has no "off" — and a provider that cannot honour a level
 * says so with a [Warning.Unsupported] rather than silently substituting one.
 */
public enum class ReasoningEffort {
    ProviderDefault,
    None,
    Minimal,
    Low,
    Medium,
    High,
    XHigh,
}

/** Text or JSON output. */
public sealed interface ResponseFormat {

    public data object Text : ResponseFormat

    /**
     * Structured output. [schema] is passed to the vendor verbatim; [name] and [description] are extra
     * guidance some vendors accept and the rest ignore.
     */
    public data class Json(
        val schema: JsonSchema? = null,
        val name: String? = null,
        val description: String? = null,
    ) : ResponseFormat
}

/** How the model may use tools. */
public sealed interface ToolChoice {

    /** The model decides, and may call nothing. */
    public data object Auto : ToolChoice

    /** The model may not call a tool. */
    public data object None : ToolChoice

    /** The model must call some tool. */
    public data object Required : ToolChoice

    /** The model must call this specific tool. */
    public data class Specific(val toolName: String) : ToolChoice
}

/** A tool offered to the model. */
public sealed interface Tool {

    public val name: String

    /**
     * A tool the CLIENT executes: the model emits a call, the runtime runs it, the result goes back as a
     * [ToolResultPart].
     */
    public data class Function(
        override val name: String,
        val inputSchema: JsonSchema,
        val description: String? = null,
        /**
         * Ask the vendor to constrain generation to [inputSchema]. Guarantees a parseable input where
         * supported, at the cost of rejecting schema features the vendor's constrained decoder lacks.
         */
        val strict: Boolean? = null,
        val providerOptions: ProviderOptions? = null,
        /**
         * Example inputs, for vendors that accept few-shot tool guidance. Each is one complete input
         * object. Providers without a place for them ignore them — a warning per call would nag about
         * a hint, and a hint is all this is.
         */
        val inputExamples: List<JsonSchema>? = null,
        /**
         * True where the runtime must ask before running this tool.
         *
         * A flag rather than the reference's boolean-or-function: the per-call decision lives in the
         * runtime's approval policy, which sees the call and the conversation — this declares the
         * tool's own default. False stays the default because approval is friction, and a tool that
         * needs it says so.
         */
        val needsApproval: Boolean = false,
    ) : Tool

    /**
     * A tool the VENDOR defines — web search, code execution, computer use, a text editor.
     *
     * Who runs it is [providerExecuted]: a vendor-run tool streams its result back inside the
     * assistant turn, while a vendor-defined-but-client-run tool (Anthropic's text editor, computer
     * use) is dispatched by the runtime like any [Function]. [args] is the vendor's own configuration
     * blob, passed through untouched, which is what lets a new provider-side tool ship without
     * changing this specification.
     */
    public data class ProviderDefined(
        override val name: String,
        /** Vendor-namespaced identifier, e.g. `anthropic.web_search_20250305`. */
        val id: String,
        val args: JsonSchema,
        /** True where the VENDOR runs the tool on its own servers; false where the runtime must. */
        val providerExecuted: Boolean = false,
        /**
         * True where the vendor may answer this call in a turn LATER than the one that made it.
         *
         * A runtime that requires every call answered within its turn would otherwise report a
         * missing result for a call that is merely still running.
         */
        val supportsDeferredResults: Boolean = false,
    ) : Tool
}
