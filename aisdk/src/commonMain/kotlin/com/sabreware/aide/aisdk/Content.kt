package com.sabreware.aide.aisdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * One piece of what a model generated, in the order it generated it.
 *
 * Order is part of the contract, not a rendering detail: a reasoning block that preceded a tool call has
 * to be replayed before that tool call or Anthropic rejects the turn. Every variant carries
 * [providerMetadata], which is how vendor-specific payloads survive a layer that does not understand
 * them — see [ProviderMetadata].
 */
@Serializable
public sealed interface Content {

    /** Provider-namespaced output riding on this part, carried verbatim — see [ProviderMetadata]. */
    public val providerMetadata: ProviderMetadata?

    /** Text the model generated. */
    @Serializable
    @SerialName("text")
    public data class Text(
        val text: String,
        override val providerMetadata: ProviderMetadata? = null,
    ) : Content

    /**
     * A reasoning block.
     *
     * [text] is what a UI may show. Whatever makes the block replayable — Anthropic's `signature`, its
     * `redacted_thinking` payload, Gemini's `thoughtSignature` — rides in [providerMetadata] under the
     * provider's own key, untouched.
     */
    @Serializable
    @SerialName("reasoning")
    public data class Reasoning(
        val text: String,
        override val providerMetadata: ProviderMetadata? = null,
    ) : Content

    /** A file the model generated — an image, an audio clip. */
    @Serializable
    @SerialName("file")
    public data class File(
        /** The file's media type, e.g. `image/png`. */
        val mediaType: String,
        /** The payload — see [FileData] for the forms it can take. */
        val data: FileData,
        override val providerMetadata: ProviderMetadata? = null,
    ) : Content

    /** A file generated as part of reasoning, e.g. a chart the model drew while thinking. */
    @Serializable
    @SerialName("reasoning-file")
    public data class ReasoningFile(
        /** The file's media type, e.g. `image/png`. */
        val mediaType: String,
        /** The payload — see [FileData] for the forms it can take. */
        val data: FileData,
        override val providerMetadata: ProviderMetadata? = null,
    ) : Content

    /** A tool call the model wants executed. [input] is a JSON string, never a parsed object. */
    @Serializable
    @SerialName("tool-call")
    public data class ToolCall(
        /** The vendor's id for this call; the matching result must echo it exactly. */
        val toolCallId: String,
        /** The tool being called, by its offered [Tool.name]. */
        val toolName: String,
        val input: String,
        /** True where the vendor runs the tool itself; the runtime must not run it too. */
        val providerExecuted: Boolean = false,
        /** True for a tool the call did not statically offer — discovered at run time, MCP typically. */
        val dynamic: Boolean = false,
        /**
         * The call did not survive validation — truncated arguments, a tool name nothing matches, input
         * that does not fit the schema.
         *
         * A runtime must not execute one. The flag exists rather than dropping the call because the
         * model still produced it: the turn has to be replayed with it present, and a caller debugging a
         * loop needs to see what the model tried to do.
         */
        val invalid: Boolean = false,
        override val providerMetadata: ProviderMetadata? = null,
    ) : Content

    /**
     * The result of a tool the PROVIDER executed on its own servers.
     *
     * [providerExecuted] defaults to true because that is what this variant means. It is a field rather
     * than an assumption so a runtime that ever surfaces a client-executed result here can say so, and
     * whoever rebuilds the assistant turn can filter on it — replaying a client result inside the
     * assistant turn AND in the tool turn sends it to the model twice.
     */
    @Serializable
    @SerialName("tool-result")
    public data class ToolResult(
        /** The call this answers — matches [ToolCall.toolCallId] exactly. */
        val toolCallId: String,
        /** The tool that ran. */
        val toolName: String,
        /** What it returned — see [ToolOutput]. */
        val output: ToolOutput,
        val providerExecuted: Boolean = true,
        /** The vendor's failure flag. Read [failed], which honours both channels, not this alone. */
        val isError: Boolean = false,
        /**
         * A placeholder that a later result replaces — an image preview mid-render, for instance. There
         * is always a final non-preliminary result; a consumer that treats a preliminary one as final
         * shows the user a half-finished answer.
         */
        val preliminary: Boolean = false,
        /** True for a tool the call did not statically offer — discovered at run time, MCP typically. */
        val dynamic: Boolean = false,
        override val providerMetadata: ProviderMetadata? = null,
    ) : Content {

        /**
         * Whether this result reports a failure, by either of the two channels that can say so.
         *
         * [isError] is the vendor's flag and [output] can be an error variant; a provider may set
         * either. One reader means a consumer cannot honour one and miss the other — which is what
         * turns a failed tool into a result the UI renders as a success.
         */
        public val failed: Boolean
            get() = isError || output is ToolOutput.ErrorText || output is ToolOutput.ErrorJson
    }

    /**
     * The provider wants to run a tool but needs explicit approval first (MCP tools, typically).
     *
     * [approvalId] is what a `tool-approval-response` refers back to.
     */
    @Serializable
    @SerialName("tool-approval-request")
    public data class ToolApprovalRequest(
        val approvalId: String,
        /** The call awaiting the decision. */
        val toolCallId: String,
        override val providerMetadata: ProviderMetadata? = null,
        /** Why approval is being asked — shown to whoever decides, when the requester says. */
        val reason: String? = null,
        /**
         * True on a request no human saw: policy answered it, and the request/response pair exists so
         * the transcript still shows that the gate was consulted. A UI filters these out of its
         * confirm queue; an auditor is exactly who they are for.
         */
        val isAutomatic: Boolean? = null,
        /**
         * HMAC binding this approval to its call (id, name, and input digest).
         *
         * Exists because the approval and the call it authorizes travel through storage the runtime
         * does not control: a persisted conversation edited between the ask and the answer could
         * attach a yes to a different call. Verified on resume when the caller supplies the secret;
         * opaque otherwise.
         */
        val signature: String? = null,
    ) : Content

    /** A source the model used — a web page it searched, a document it was given. */
    @Serializable
    public sealed interface Source : Content {

        /** Provider-scoped identifier for this source. */
        public val id: String

        /** Human-readable name, when the provider gives one. */
        public val title: String?

        @Serializable
        @SerialName("source-url")
        public data class Url(
            override val id: String,
            /** The address of the page the model consulted. */
            val url: String,
            override val title: String? = null,
            override val providerMetadata: ProviderMetadata? = null,
        ) : Source

        @Serializable
        @SerialName("source-document")
        public data class Document(
            override val id: String,
            /** The document's media type, e.g. `application/pdf`. */
            val mediaType: String,
            override val title: String,
            /** The underlying file's name, when known. */
            val filename: String? = null,
            override val providerMetadata: ProviderMetadata? = null,
        ) : Source
    }

    /**
     * A provider block that maps to no standardized part.
     *
     * The escape hatch that keeps this specification from being the bottleneck: a vendor can ship a new
     * content kind and its provider can surface it on day one, without a spec change. [kind] is
     * `{provider}.{type}`.
     */
    @Serializable
    @SerialName("custom")
    public data class Custom(
        val kind: String,
        override val providerMetadata: ProviderMetadata? = null,
    ) : Content
}
