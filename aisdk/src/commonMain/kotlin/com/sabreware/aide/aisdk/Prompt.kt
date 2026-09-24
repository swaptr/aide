package com.sabreware.aide.aisdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The standardized prompt handed to a [LanguageModel].
 *
 * This is NOT a user-facing chat history. A runtime maps whatever it stores — AIDE's `AideMessage`, a
 * UI message list, a database row — into this shape, and that indirection is what lets the storage
 * format evolve without touching a single provider.
 */
public typealias Prompt = List<ModelMessage>

/**
 * One message in a [Prompt].
 *
 * Modelled as four distinct types rather than one class with a `role` string, because the roles do not
 * carry the same content: only an assistant turn can hold reasoning or a tool call, only a tool turn can
 * hold a result, and a system turn is plain text. Encoding that in the type removes every "can this
 * actually happen" branch from every provider.
 *
 * The wire discriminator is `role`, not `type`, matching the reference — a stored prompt should be
 * readable by any implementation of this specification, and the discriminator key is half of that.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("role")
public sealed interface ModelMessage {

    /** Provider-specific input attached to this message (e.g. Anthropic cache control). */
    public val providerOptions: ProviderOptions?

    /** System instructions. Providers that have no system role hoist this into their own equivalent. */
    @Serializable
    @SerialName("system")
    public data class System(
        val content: String,
        override val providerOptions: ProviderOptions? = null,
    ) : ModelMessage

    /** A user turn: text and/or attachments. */
    @Serializable
    @SerialName("user")
    public data class User(
        val content: List<UserPart>,
        override val providerOptions: ProviderOptions? = null,
    ) : ModelMessage

    /**
     * An assistant turn, replayed back to the model on the next round.
     *
     * The ORDER of [content] is load-bearing and must be preserved exactly as the model produced it.
     * Anthropic requires that a replayed assistant turn begin with its thinking block, and rejects a
     * reordered or re-serialized signature outright. This is why parts are a list and not a set of
     * optional fields.
     */
    @Serializable
    @SerialName("assistant")
    public data class Assistant(
        val content: List<AssistantPart>,
        override val providerOptions: ProviderOptions? = null,
    ) : ModelMessage

    /** Results for tool calls the assistant requested in the previous turn, and approval decisions. */
    @Serializable
    @SerialName("tool")
    public data class Tool(
        val content: List<ToolPart>,
        override val providerOptions: ProviderOptions? = null,
    ) : ModelMessage
}

/** A part of a [ModelMessage.User] turn. */
@Serializable
public sealed interface UserPart {

    /** Provider-specific input attached to this part — see [ProviderOptions]. */
    public val providerOptions: ProviderOptions?

    /** Text the user wrote. */
    @Serializable
    @SerialName("text")
    public data class Text(
        val text: String,
        override val providerOptions: ProviderOptions? = null,
    ) : UserPart

    /** An attachment: image, audio, video or document. */
    @Serializable
    @SerialName("file")
    public data class File(
        /** The payload — see [FileData] for the forms it can take. */
        val data: FileData,
        /** The attachment's media type, e.g. `image/png`. */
        val mediaType: String,
        /** The original file name, for vendors that surface it to the model. */
        val filename: String? = null,
        override val providerOptions: ProviderOptions? = null,
    ) : UserPart
}

/**
 * A part of a [ModelMessage.Assistant] turn.
 *
 * Every kind the model can produce has a replay arm here. That is not symmetry for its own sake: a part
 * with no arm is a part the runtime has to null out when it rebuilds the turn, and a vendor that issued
 * it — Gemini for reasoning artifacts, OpenAI for custom content — then sees a turn it did not generate.
 */
@Serializable
public sealed interface AssistantPart {

    /** Provider-specific input attached to this part — see [ProviderOptions]. */
    public val providerOptions: ProviderOptions?

    /** Text the model generated, replayed as it was produced. */
    @Serializable
    @SerialName("text")
    public data class Text(
        val text: String,
        override val providerOptions: ProviderOptions? = null,
    ) : AssistantPart

    /**
     * A reasoning block the model produced.
     *
     * [providerOptions] is the whole game: the displayable text is in [text], and the signed or
     * encrypted payload that makes the block replayable rides in the provider's own namespace. A
     * provider that receives this part back must reproduce that payload byte-for-byte.
     */
    @Serializable
    @SerialName("reasoning")
    public data class Reasoning(
        val text: String,
        override val providerOptions: ProviderOptions? = null,
    ) : AssistantPart

    /** A file the model generated — an image, an audio clip — replayed back on the next round. */
    @Serializable
    @SerialName("file")
    public data class File(
        /** The payload — see [FileData] for the forms it can take. */
        val data: FileData,
        /** The file's media type, e.g. `image/png`. */
        val mediaType: String,
        /** The file's name, when the model gave it one. */
        val filename: String? = null,
        override val providerOptions: ProviderOptions? = null,
    ) : AssistantPart

    /** A file the model generated while reasoning, replayed with the reasoning it belongs to. */
    @Serializable
    @SerialName("reasoning-file")
    public data class ReasoningFile(
        /** The payload — see [FileData] for the forms it can take. */
        val data: FileData,
        /** The file's media type, e.g. `image/png`. */
        val mediaType: String,
        override val providerOptions: ProviderOptions? = null,
    ) : AssistantPart

    /**
     * A vendor block with no standardized shape, replayed verbatim.
     *
     * [kind] is `{provider}.{type}`. The payload itself lives in [providerOptions] — this part carries
     * no content of its own, which is exactly what makes it safe for a neutral layer to move around.
     */
    @Serializable
    @SerialName("custom")
    public data class Custom(
        val kind: String,
        override val providerOptions: ProviderOptions? = null,
    ) : AssistantPart

    /** A tool call the model requested. [input] is a JSON string, never a parsed object. */
    @Serializable
    @SerialName("tool-call")
    public data class ToolCall(
        /** The vendor's id for this call; the answering result must echo it exactly. */
        val toolCallId: String,
        /** The tool that was called. */
        val toolName: String,
        /**
         * The arguments as the model produced them, as a JSON string — a decision, not an oversight.
         *
         * The porter's trap: the reference holds the PARSED value on the prompt side (`ToolCallPart.input:
         * unknown`) and a string on the output side (`LanguageModelV4ToolCall.input: string`), so a
         * provider ported verbatim serializes a string that was already JSON and the model gets its own
         * arguments back double-encoded. Ours is a string on both sides, and the same string
         * ([com.sabreware.aide.aisdk.Content.ToolCall.input] comes back here untouched), because two of
         * the three wire shapes carry it as one: Chat Completions `function.arguments` and Responses
         * `function_call.arguments` are strings, and replaying the model's own bytes verbatim is the only
         * way to be certain the model sees what it said. The object wires parse at their boundary —
         * Anthropic's `input` with a `{}` fallback, Google's `args` omitted when it will not parse — and
         * the runtime replays an `invalid` call's arguments as `{}` before either sees them. A parsed type
         * could not do that: the raw text of an unparseable call is exactly what has to travel back to the
         * model, and a value that failed to parse has no parsed form to carry it in.
         */
        val input: String,
        /** True where the vendor ran it; its result replays inside the assistant turn. */
        val providerExecuted: Boolean = false,
        override val providerOptions: ProviderOptions? = null,
    ) : AssistantPart

    /**
     * A question about whether one of this turn's calls may run — the assistant-side half of an
     * approval exchange, answered by a [ToolPart.ApprovalResponse] in the following tool turn.
     *
     * It exists so an approval can OUTLIVE the process that raised it. A confirm-before-writing flow
     * asks a human, and a human takes minutes: the conversation is persisted, the app is closed, and
     * the answer arrives against a rebuilt runtime. Without this part the request lives only in the
     * response types, so a stored conversation comes back holding a call, no result, and no record
     * that anything was ever asked — which reads as a truncated turn and is rejected as one.
     *
     * Most vendors have no wire form for it and skip it; it is bookkeeping between the runtime and
     * whoever decides, not content for the model.
     */
    @Serializable
    @SerialName("tool-approval-request")
    public data class ApprovalRequest(
        /** The id the answering [ToolPart.ApprovalResponse] must echo. */
        val approvalId: String,
        /** The call this decision gates. */
        val toolCallId: String,
        /** Why approval is being asked — shown to whoever decides. */
        val reason: String? = null,
        /** True where policy answered it without a human — see [Content.ToolApprovalRequest.isAutomatic]. */
        val isAutomatic: Boolean? = null,
        /** Binds this request to its call across storage — see [Content.ToolApprovalRequest.signature]. */
        val signature: String? = null,
        override val providerOptions: ProviderOptions? = null,
    ) : AssistantPart

    /**
     * The result of a tool the PROVIDER executed (server-side search, code execution), replayed as part
     * of the assistant turn it belongs to rather than as a separate tool message.
     */
    @Serializable
    @SerialName("tool-result")
    public data class ToolResult(
        /** The [ToolCall.toolCallId] this answers. */
        val toolCallId: String,
        /** The tool that ran. */
        val toolName: String,
        /** What it returned — see [ToolOutput]. */
        val output: ToolOutput,
        override val providerOptions: ProviderOptions? = null,
    ) : AssistantPart
}

/**
 * A part of a [ModelMessage.Tool] turn: a result for one tool call, or a decision on one approval
 * request.
 *
 * The approval arm exists because [Content.ToolApprovalRequest] is only half a conversation. A provider
 * that asks whether it may run a tool needs the answer back on the wire; without this part the runtime
 * can compute a decision and has no way to say it.
 */
@Serializable
public sealed interface ToolPart {

    /** Provider-specific input attached to this part — see [ProviderOptions]. */
    public val providerOptions: ProviderOptions?

    /** A result for one tool call. */
    @Serializable
    @SerialName("tool-result")
    public data class Result(
        /** The assistant-turn call this answers. */
        val toolCallId: String,
        /** The tool that ran. */
        val toolName: String,
        /** What it returned — see [ToolOutput]. */
        val output: ToolOutput,
        override val providerOptions: ProviderOptions? = null,
    ) : ToolPart

    /** The decision on a [Content.ToolApprovalRequest] the provider raised. */
    @Serializable
    @SerialName("tool-approval-response")
    public data class ApprovalResponse(
        /** The [Content.ToolApprovalRequest.approvalId] this answers. */
        val approvalId: String,
        /** The decision: may the provider run the tool. */
        val approved: Boolean,
        /** Why, when the decider chooses to say — some vendors show it to the model. */
        val reason: String? = null,
        override val providerOptions: ProviderOptions? = null,
    ) : ToolPart
}

/**
 * The namespace a RUNTIME-minted approval exchange is filed under.
 *
 * An approval can come from two places, and only one of them belongs on the wire. A VENDOR raises one
 * through its own protocol — OpenAI's MCP approvals — and the answer has to go back, because the vendor
 * is holding a pending item keyed by an id it issued. A RUNTIME gate raises one because the host asked
 * to confirm before writing; its ids are minted here, and sending them to a vendor names an item that
 * vendor never created.
 *
 * So a runtime-minted request and its response carry this key, and a provider skips what it sees tagged
 * with it. The model still learns the outcome — from the tool result, or from its absence — which is
 * the only part it needs.
 */
public const val RUNTIME_APPROVAL_NAMESPACE: String = "aisdk.runtime"

/** Whether this decision was made by the runtime's own gate — see [RUNTIME_APPROVAL_NAMESPACE]. */
public val ToolPart.ApprovalResponse.isRuntimeMinted: Boolean
    get() = providerOptions?.containsKey(RUNTIME_APPROVAL_NAMESPACE) == true

/** Whether this request came from the runtime's own gate — see [RUNTIME_APPROVAL_NAMESPACE]. */
public val AssistantPart.ApprovalRequest.isRuntimeMinted: Boolean
    get() = providerOptions?.containsKey(RUNTIME_APPROVAL_NAMESPACE) == true

/**
 * What a tool returned.
 *
 * A union rather than a string because providers treat these differently on the wire: an error result
 * is flagged rather than described, and structured JSON is sent as JSON where the vendor accepts it.
 * Collapsing all of it to text is a lossy convenience that shows up later as a model that cannot tell
 * a failure from a string containing the word "error".
 *
 * Every arm carries [providerOptions] because the most valuable per-result option in practice —
 * Anthropic's `cache_control` on a large tool result — attaches to the result, not to the message.
 */
@Serializable
public sealed interface ToolOutput {

    public val providerOptions: ProviderOptions?

    /** Plain text output. */
    @Serializable
    @SerialName("text")
    public data class Text(
        val value: String,
        override val providerOptions: ProviderOptions? = null,
    ) : ToolOutput

    /** Structured output. */
    @Serializable
    @SerialName("json")
    public data class Json(
        val value: JsonElement,
        override val providerOptions: ProviderOptions? = null,
    ) : ToolOutput

    /** The tool failed, described in text. */
    @Serializable
    @SerialName("error-text")
    public data class ErrorText(
        val value: String,
        override val providerOptions: ProviderOptions? = null,
    ) : ToolOutput

    /** The tool failed, described structurally. */
    @Serializable
    @SerialName("error-json")
    public data class ErrorJson(
        val value: JsonElement,
        override val providerOptions: ProviderOptions? = null,
    ) : ToolOutput

    /** The user or a policy refused to run the tool. */
    @Serializable
    @SerialName("execution-denied")
    public data class ExecutionDenied(
        val reason: String? = null,
        override val providerOptions: ProviderOptions? = null,
    ) : ToolOutput

    /**
     * Multi-modal output — a tool that returns images alongside text.
     *
     * Named `Multipart` rather than the reference's `content` so it does not shadow the top-level
     * [Content] type inside this file.
     */
    @Serializable
    @SerialName("content")
    public data class Multipart(
        val value: List<Item>,
        override val providerOptions: ProviderOptions? = null,
    ) : ToolOutput {

        @Serializable
        public sealed interface Item {

            /** Provider-specific input attached to this item — see [ProviderOptions]. */
            public val providerOptions: ProviderOptions?

            /** A text piece of the result. */
            @Serializable
            @SerialName("text")
            public data class Text(
                val text: String,
                override val providerOptions: ProviderOptions? = null,
            ) : Item

            /** A file piece of the result — an image a tool rendered, typically. */
            @Serializable
            @SerialName("file")
            public data class File(
                /** The payload — see [FileData] for the forms it can take. */
                val data: FileData,
                /** The file's media type, e.g. `image/png`. */
                val mediaType: String,
                /** The file's name, when the tool gave it one. */
                val filename: String? = null,
                override val providerOptions: ProviderOptions? = null,
            ) : Item

            /** A vendor item with no standardized shape; the payload rides in [providerOptions]. */
            @Serializable
            @SerialName("custom")
            public data class Custom(
                override val providerOptions: ProviderOptions? = null,
            ) : Item
        }
    }
}

/**
 * File payload: bytes we hold, a URL the provider fetches itself, a handle the provider already has, or
 * inline text.
 *
 * Kept as a union so a provider that supports remote URLs natively (see [LanguageModel.supportedUrls])
 * can pass one straight through instead of forcing a download the vendor would have done anyway, and so
 * a file already uploaded to a vendor is referenced rather than re-sent on every single turn.
 */
@Serializable
public sealed interface FileData {

    /** Raw bytes. Base64 encoding, where a vendor needs it, is the provider's job — not the caller's. */
    @Serializable
    @SerialName("data")
    public data class Bytes(val bytes: ByteArray) : FileData {

        // Data classes compare ByteArray by reference; content equality is what every caller means.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** A URL the provider fetches. */
    @Serializable
    @SerialName("url")
    public data class Url(val url: String) : FileData

    /**
     * A file the vendor already holds, named by the id it gave us — keyed by provider, because the same
     * logical file has a different id on every vendor that stores it.
     *
     * This is what stops a 20 MB PDF being re-uploaded on every turn of a long conversation.
     */
    @Serializable
    @SerialName("reference")
    public data class Reference(val reference: Map<String, String>) : FileData

    /** Inline text content — an attached document whose bytes are already characters. */
    @Serializable
    @SerialName("text")
    public data class Text(val text: String) : FileData
}

/** A JSON Schema, carried verbatim. The specification never parses or validates one — see DESIGN.md. */
public typealias JsonSchema = JsonObject
