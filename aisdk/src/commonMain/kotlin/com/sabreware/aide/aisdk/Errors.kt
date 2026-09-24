package com.sabreware.aide.aisdk

import kotlinx.serialization.json.JsonElement

/**
 * Base for every error this specification defines.
 *
 * The reference implementation brands each error class with a `Symbol.for(...)` marker and a static
 * `isInstance`, because two copies of the package at different versions produce two unrelated classes and
 * `instanceof` stops working across them. Kotlin has no such problem — there is one class, and `is`
 * answers correctly — so the marker machinery is deliberately not ported. [errorName] is kept because it
 * is what appears in logs and telemetry, and matching the reference's strings keeps those comparable.
 */
public open class AiSdkError(
    /** The reference's stable error name, e.g. `AI_APICallError`. */
    public val errorName: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * An HTTP call to a provider failed.
 *
 * [isRetryable] defaults to the reference's rule — 408, 409, 429 or any 5xx — so a retry policy does not
 * have to re-derive per-vendor knowledge. A provider that knows better overrides it: some vendors return
 * 400 for a transient overload, and some return 429 for a quota that will not reset today.
 *
 * [requestBodyValues] and [responseBody] are kept because the first question about any wire failure is
 * "what did we actually send", and an error that cannot answer it turns a five-minute fix into a
 * reproduction session.
 */
public class APICallError(
    message: String,
    /** The URL the request went to. */
    public val url: String,
    /** The request body as sent — "what did we actually send", answered without a reproduction. */
    public val requestBodyValues: String? = null,
    /** HTTP status; null when the failure happened before any response arrived. */
    public val statusCode: Int? = null,
    /** Response headers — where rate-limit state and retry-after hints live. */
    public val responseHeaders: Map<String, String>? = null,
    /** The raw response body, usually the vendor's own explanation of the failure. */
    public val responseBody: String? = null,
    /**
     * Whether a retry can plausibly succeed — the one bit a retry policy branches on.
     *
     * Getting it wrong costs in both directions: false on a transient overload abandons a call that
     * would have worked, true on an exhausted quota hammers an endpoint that will refuse all day.
     */
    public val isRetryable: Boolean = defaultIsRetryable(statusCode),
    /** The vendor's structured error payload, when the body parsed as JSON. */
    public val data: JsonElement? = null,
    cause: Throwable? = null,
) : AiSdkError("AI_APICallError", message, cause) {

    public companion object {
        /** 408 request timeout, 409 conflict, 429 too many requests, or any server error. */
        public fun defaultIsRetryable(statusCode: Int?): Boolean = statusCode != null &&
            (statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500)
    }
}

/** The provider returned success with no body at all. */
public class EmptyResponseBodyError(
    message: String = "Empty response body",
    cause: Throwable? = null,
) : AiSdkError("AI_EmptyResponseBodyError", message, cause)

/** A caller passed an argument this provider cannot accept. */
public class InvalidArgumentError(
    message: String,
    /** The name of the offending argument. */
    public val argument: String,
    cause: Throwable? = null,
) : AiSdkError("AI_InvalidArgumentError", message, cause)

/** The prompt is structurally invalid — an empty message list, a tool result with no matching call. */
public class InvalidPromptError(
    message: String,
    /** The rejected prompt, when the thrower had it in hand. */
    public val prompt: Prompt? = null,
    cause: Throwable? = null,
) : AiSdkError("AI_InvalidPromptError", message, cause)

/** The provider responded with data that does not match its own documented shape. */
public class InvalidResponseDataError(
    message: String,
    /** The offending payload, verbatim. */
    public val data: JsonElement? = null,
    cause: Throwable? = null,
) : AiSdkError("AI_InvalidResponseDataError", message, cause)

/**
 * A response body did not parse as JSON.
 *
 * [text] is the raw body. Providers stream, and a truncated body is the usual cause; without the text
 * there is no way to tell truncation from a genuine format change.
 */
public class JsonParseError(
    public val text: String,
    cause: Throwable? = null,
) : AiSdkError(
    "AI_JSONParseError",
    "JSON parsing failed: Text: ${text.take(JSON_ERROR_EXCERPT)}.\nError message: ${getErrorMessage(cause)}",
    cause,
) {

    private companion object {
        // Long enough to see where a truncated stream stopped; short enough not to fill a log line.
        const val JSON_ERROR_EXCERPT = 500
    }
}

/** No API key was found, in the argument or in the environment. */
public class LoadApiKeyError(
    message: String,
    cause: Throwable? = null,
) : AiSdkError("AI_LoadAPIKeyError", message, cause)

/** A required setting could not be resolved. */
public class LoadSettingError(
    message: String,
    cause: Throwable? = null,
) : AiSdkError("AI_LoadSettingError", message, cause)

/**
 * The call succeeded but produced nothing usable.
 *
 * Distinct from an empty string, which is a legitimate answer. This is the case where a provider
 * returned a well-formed response containing no content parts at all.
 *
 * **Open, and the base of every modality-specific twin below.** The reference makes them siblings under
 * its own error base; here they extend this one, because this is the class our modality wrappers have
 * always thrown and a caller already catching it must keep catching it. Sibling classes would have made
 * "the model produced nothing" a case every existing `catch` silently stopped matching — a refinement
 * that breaks callers is not a refinement.
 */
public open class NoContentGeneratedError(
    message: String = "No content generated.",
    cause: Throwable? = null,
    errorName: String = "AI_NoContentGeneratedError",
    /**
     * Response identity for each call that produced nothing.
     *
     * A list because a wrapper may fan one request out over several calls — nine images against a
     * five-per-call ceiling is two responses — and the one that came back empty is the one worth
     * quoting in the bug report.
     */
    public val responses: List<ModalityResponse> = emptyList(),
) : AiSdkError(errorName, message, cause)

/**
 * An image call returned no images.
 *
 * Named separately from its base because the recovery differs per modality: an empty image response is
 * usually a moderated prompt worth rewording, where an empty speech response is usually a bad voice id.
 * A caller that catches only [NoContentGeneratedError] still catches this.
 */
public class NoImageGeneratedError(
    message: String = "No image generated.",
    cause: Throwable? = null,
    responses: List<ModalityResponse> = emptyList(),
    /**
     * Every call's full result, not only its response envelope: the warnings and the retryability
     * classification of an empty result are what a caller reads to decide whether asking again is
     * worth anything, and they live on the result, not on the response.
     */
    public val calls: List<ImageResult> = emptyList(),
) : NoContentGeneratedError(message, cause, "AI_NoImageGeneratedError", responses)

/** A speech call returned no audio. @see NoImageGeneratedError for why the modality is named. */
public class NoSpeechGeneratedError(
    message: String = "No speech audio generated.",
    cause: Throwable? = null,
    responses: List<ModalityResponse> = emptyList(),
) : NoContentGeneratedError(message, cause, "AI_NoSpeechGeneratedError", responses)

/** A video call returned no video. @see NoImageGeneratedError for why the modality is named. */
public class NoVideoGeneratedError(
    message: String = "No video generated.",
    cause: Throwable? = null,
    responses: List<ModalityResponse> = emptyList(),
) : NoContentGeneratedError(message, cause, "AI_NoVideoGeneratedError", responses)

/**
 * A run produced no output of the shape that was asked for.
 *
 * The general case behind [NoObjectGeneratedError]: the run finished, and nothing in it could be read
 * as the requested output. Kept distinct from "the provider returned no content at all", which is
 * [NoContentGeneratedError] itself — a run CAN produce plenty of content and still yield no output.
 */
public class NoOutputGeneratedError(
    message: String = "No output generated.",
    cause: Throwable? = null,
) : NoContentGeneratedError(message, cause, "AI_NoOutputGeneratedError")

/** The requested model id is unknown to this provider. */
public open class NoSuchModelError(
    /** The id that resolved nothing. */
    public val modelId: String,
    /** Which modality's namespace the lookup searched. */
    public val modelType: ModelType,
    message: String = "No such ${modelType.wireName}: $modelId",
    cause: Throwable? = null,
    errorName: String = "AI_NoSuchModelError",
) : AiSdkError(errorName, message, cause) {

    /**
     * The modality namespaces a lookup can miss in; [wireName] is the reference's string for the
     * modality, and is what appears in error messages.
     */
    public enum class ModelType(public val wireName: String) {
        /** A language-model lookup. */
        LanguageModel("languageModel"),

        /** An embedding-model lookup. */
        EmbeddingModel("embeddingModel"),

        /** An image-model lookup. */
        ImageModel("imageModel"),

        /** A speech-model lookup. */
        SpeechModel("speechModel"),

        /** A transcription-model lookup. */
        TranscriptionModel("transcriptionModel"),

        /** A video-model lookup. */
        VideoModel("videoModel"),

        /** A reranking-model lookup. */
        RerankingModel("rerankingModel"),
    }
}

/**
 * A registry lookup named a provider that was never registered.
 *
 * Subclasses [NoSuchModelError] because that is what it is: the id `openai:gpt-5` resolved no model, and
 * the reason happens to be the half before the colon. A caller catching "model not found" should catch
 * this too, which a peer class would not give them.
 */
public class NoSuchProviderError(
    /** The provider id that resolved nothing. */
    public val providerId: String,
    /** What IS registered, so the message can put the likely typo next to its neighbours. */
    public val availableProviders: List<String> = emptyList(),
    modelId: String,
    modelType: ModelType,
    message: String = "No such provider: $providerId (modelId: $modelId, modelType: ${modelType.wireName})" +
        if (availableProviders.isEmpty()) "" else " - available providers: ${availableProviders.joinToString()}",
    cause: Throwable? = null,
) : NoSuchModelError(modelId, modelType, message, cause, errorName = "AI_NoSuchProviderError")

/**
 * A [FileData.Reference] lookup found no id under the provider that needs one.
 *
 * The reference map is keyed by provider because the same logical file has a different id on every
 * vendor that stores it — so a file uploaded to OpenAI and replayed against Anthropic is not a warning
 * to shrug at but a part the request cannot express. Typed, so a caller can catch it and upload the
 * file to the missing provider rather than pattern-matching a message.
 */
public class NoSuchProviderReferenceError(
    /** The provider whose id the lookup needed and did not find. */
    public val provider: String,
    /** The reference map as given — its keys are the providers the file IS known to. */
    public val reference: Map<String, String>,
    message: String = "No provider reference found for provider '$provider'. " +
        "Available providers: ${reference.keys.joinToString(", ")}",
    cause: Throwable? = null,
) : AiSdkError("AI_NoSuchProviderReferenceError", message, cause)

/** More values were passed to an embedding call than the model accepts in one request. */
public class TooManyEmbeddingValuesForCallError(
    /** The provider whose limit was exceeded. */
    public val provider: String,
    /** The model whose limit was exceeded. */
    public val modelId: String,
    /** The ceiling, per [EmbeddingModel.maxEmbeddingsPerCall]. */
    public val maxEmbeddingsPerCall: Int,
    /** Everything that was submitted — kept so the caller can split and resend without re-deriving. */
    public val values: List<String>,
) : AiSdkError(
    "AI_TooManyEmbeddingValuesForCallError",
    "Too many values for a single embedding call. " +
        "The '$provider' model '$modelId' supports at most $maxEmbeddingsPerCall values per call, " +
        "but ${values.size} values were provided.",
)

/**
 * Where a value that failed validation came from — the half of a [TypeValidationError] a reader can
 * act on.
 *
 * "Type validation failed" on its own names nothing: in a conversation of forty tool calls it does not
 * say which call, and in a message of a dozen parts it does not say which part. [field] is a dot path
 * to the offending member (`message.metadata`, `message.parts[3].data`), [entityName] the tool or type
 * whose schema rejected it, [entityId] the message or tool-call id. All three are optional and every
 * combination renders — see [messagePrefix].
 */
public data class TypeValidationContext(
    val field: String? = null,
    val entityName: String? = null,
    val entityId: String? = null,
) {

    /**
     * The reference's prefix rule, byte for byte: `Type validation failed`, then ` for <field>`, then
     * ` (<entityName>, id: "<entityId>")` with whichever of the two is present. An empty string counts
     * as absent, as it does in the reference's truthiness checks.
     */
    public fun messagePrefix(): String = buildString {
        append("Type validation failed")
        if (!field.isNullOrEmpty()) append(" for ").append(field)
        val parts = listOfNotNull(
            entityName?.takeIf { it.isNotEmpty() },
            entityId?.takeIf { it.isNotEmpty() }?.let { "id: \"$it\"" },
        )
        if (parts.isNotEmpty()) append(" (").append(parts.joinToString(", ")).append(")")
    }
}

/**
 * A value failed validation against the type it was expected to have.
 *
 * The message follows the reference's format exactly — the [context] prefix, the value as JSON, then
 * the reason on its own line — so a failure read out of a Kotlin host and one read out of a Node host
 * say the same thing:
 *
 * ```
 * Type validation failed for messages[0].parts[0].input (weather, id: "call-1"): Value: {"foo":123}.
 * Error message: Expected a string.
 * ```
 */
public class TypeValidationError(
    /** The value that failed; null when it never parsed in the first place. */
    public val value: JsonElement?,
    /**
     * Why the value did not fit — the `Error message:` line. The reference derives it from the cause;
     * here it is stated directly, because most of our failures are a described mismatch with no
     * exception behind it.
     */
    message: String,
    cause: Throwable? = null,
    /** Which field, tool or schema the value was checked against — see [TypeValidationContext]. */
    public val context: TypeValidationContext? = null,
) : AiSdkError(
    "AI_TypeValidationError",
    "${(context ?: TypeValidationContext()).messagePrefix()}: Value: ${value ?: "undefined"}.\n" +
        "Error message: $message",
    cause,
) {

    public companion object {

        /**
         * The reference's `wrap`: a cause that is already a [TypeValidationError] for this value and this
         * context is returned as it is, rather than nested inside a second error that would say the same
         * thing twice. Anything else becomes the cause of a new error whose reason is its message.
         *
         * The context is compared field by field, as the reference compares it, so an absent context and
         * an empty one are the same context.
         */
        public fun wrap(
            value: JsonElement?,
            cause: Throwable?,
            context: TypeValidationContext? = null,
        ): TypeValidationError =
            if (cause is TypeValidationError && cause.value == value && cause.context sameAs context) {
                cause
            } else {
                TypeValidationError(value, getErrorMessage(cause), cause, context)
            }

        private infix fun TypeValidationContext?.sameAs(other: TypeValidationContext?): Boolean =
            this?.field == other?.field &&
                this?.entityName == other?.entityName &&
                this?.entityId == other?.entityId
    }
}

/**
 * The provider does not support something the call asked for.
 *
 * This is the honest alternative to silently ignoring a setting. A provider that cannot express a
 * request either warns (see [Warning.Unsupported], for anything it can proceed without) or throws
 * this (for anything it cannot).
 */
public class UnsupportedFunctionalityError(
    /** The capability that was asked for, named for the message. */
    public val functionality: String,
    message: String = "'$functionality' functionality not supported.",
    cause: Throwable? = null,
) : AiSdkError("AI_UnsupportedFunctionalityError", message, cause)

/**
 * A human-readable message for any throwable, including the ones that carry none.
 *
 * Mirrors the reference's `getErrorMessage`: a null becomes `"unknown error"` rather than the string
 * `"null"`, which is what a UI would otherwise show a user.
 */
public fun getErrorMessage(error: Throwable?): String = when {
    error == null -> "unknown error"
    !error.message.isNullOrEmpty() -> error.message!!
    else -> error.toString()
}

// ---------------------------------------------------------------------------------------------------
// Tool-loop errors
//
// A runtime that cannot name WHY a tool call was rejected has to report every rejection the same way,
// and "tool not found" for a call whose arguments were truncated sends whoever is debugging it to the
// wrong file. Each of these is a distinct thing a caller can act on.
// ---------------------------------------------------------------------------------------------------

/** The model called a tool that was not offered to it. */
public class NoSuchToolError(
    /** The name the model invented or misremembered. */
    public val toolName: String,
    /** What was actually offered; null means the call carried no tools at all. */
    public val availableTools: List<String>? = null,
    message: String = "Model tried to call unavailable tool '$toolName'. " +
        when {
            availableTools == null -> "No tools are available."
            availableTools.isEmpty() -> "No tools are available."
            else -> "Available tools: ${availableTools.joinToString()}."
        },
    cause: Throwable? = null,
) : AiSdkError("AI_NoSuchToolError", message, cause)

/** The model called a known tool with input that does not satisfy its schema. */
public class InvalidToolInputError(
    /** The tool whose schema rejected the input. */
    public val toolName: String,
    /** The raw JSON string the model produced — kept because a repair hook needs it to work from. */
    public val toolInput: String,
    cause: Throwable? = null,
    message: String = "Invalid input for tool $toolName: ${getErrorMessage(cause)}",
) : AiSdkError("AI_InvalidToolInputError", message, cause)

/**
 * A repair hook threw while trying to fix a malformed tool call.
 *
 * Wrapping matters: an un-contained hook takes down the whole run, so a caller's optional recovery
 * mechanism becomes a new way for a working conversation to die.
 */
public class ToolCallRepairError(
    /** What was wrong with the tool call BEFORE the hook made things worse. */
    public val originalError: Throwable,
    cause: Throwable? = null,
    message: String = "Error repairing tool call: ${getErrorMessage(cause)}",
) : AiSdkError("AI_ToolCallRepairError", message, cause)

/**
 * An assistant turn holds tool calls that no following tool turn answers.
 *
 * Anthropic and OpenAI both reject this with a 400. Catching it before the request means the caller
 * learns which call is unanswered instead of reading a vendor error that names none of them.
 */
public class MissingToolResultsError(
    /** The unanswered call ids — the exact list the vendor's 400 would not have named. */
    public val toolCallIds: List<String>,
    message: String = "Missing tool result(s) for tool call(s): ${toolCallIds.joinToString()}",
    cause: Throwable? = null,
) : AiSdkError("AI_MissingToolResultsError", message, cause)

/**
 * A structured-output call produced nothing that parses or validates.
 *
 * Carries the text, usage, finish reason and response so a failed call does not also lose the record of
 * what it cost and what the model actually said. Debugging one without those means running it again.
 */
public class NoObjectGeneratedError(
    message: String = "No object generated.",
    /** Whatever the model did produce — the text that failed to parse or validate. */
    public val text: String? = null,
    /** Response identity, for correlating with vendor-side logs. */
    public val response: ResponseMetadata? = null,
    /** What the failed call cost anyway. */
    public val usage: Usage? = null,
    /** Why generation stopped — `length` here usually means the object was truncated mid-brace. */
    public val finishReason: FinishReason? = null,
    cause: Throwable? = null,
) : AiSdkError("AI_NoObjectGeneratedError", message, cause)

/**
 * Every retry attempt failed.
 *
 * [errors] holds all of them, in order. A policy that surfaces only the last one hides the case where
 * the first failure was the real cause and the rest are its consequences.
 */
public class RetryError(
    /** Why retrying stopped — attempts exhausted, an error retrying cannot fix, or a cancellation. */
    public val reason: Reason,
    /** Every attempt's failure, in order — see the class doc for why all of them. */
    public val errors: List<Throwable>,
    message: String = "Failed after ${errors.size} attempt(s): ${reason.name}. " +
        "Last error: ${getErrorMessage(errors.lastOrNull())}",
) : AiSdkError("AI_RetryError", message, errors.lastOrNull()) {

    public enum class Reason { MaxRetriesExceeded, ErrorNotRetryable, AbortedError }
}

/**
 * A file the provider was asked to fetch could not be downloaded, or was refused.
 *
 * A refusal is not a network failure: [statusCode] null with a message naming the origin means the URL
 * was rejected before any request went out, which is the SSRF guard doing its job.
 */
public class DownloadError(
    /** The URL that could not be fetched. */
    public val url: String,
    /** HTTP status of the failed fetch; null means no request was ever made — see the class doc. */
    public val statusCode: Int? = null,
    message: String = "Failed to download $url" + (statusCode?.let { ": status $it" } ?: ""),
    cause: Throwable? = null,
) : AiSdkError("AI_DownloadError", message, cause)
