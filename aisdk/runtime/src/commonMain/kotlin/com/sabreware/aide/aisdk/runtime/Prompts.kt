package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidPromptError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.MissingToolResultsError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart

/**
 * Turns instructions plus a message list into a prompt the providers will accept, or explains why it
 * cannot.
 *
 * Every rule here answers a specific vendor rejection rather than a stylistic preference, which is why
 * validation belongs in the runtime and not at each seam that builds a prompt. A seam maps its own
 * storage into [ModelMessage]s; it should not also have to know that Anthropic 400s on an assistant
 * `tool_use` with no answering `tool_result`, or that a system turn appearing halfway through a
 * conversation is rejected by most of the vendors that accept one at the top.
 *
 * The four transformations, each with its failure:
 *
 * - **Instructions become the leading system message.** A caller that has to construct that itself is a
 *   caller that will eventually put it second.
 * - **Consecutive tool turns merge into one.** A round that answered three calls produces three results,
 *   and vendors that accept exactly one tool message per assistant turn — Anthropic among them — reject
 *   the run of them.
 * - **A tool turn left empty is dropped.** An approval-only turn whose responses a provider does not
 *   take is an empty `content` array, and an empty message is a 400 everywhere.
 * - **An unanswered tool call is [MissingToolResultsError].** A stored conversation truncated mid-round,
 *   or a UI that dropped a failed tool result, produces an assistant turn whose calls nothing answers;
 *   both Anthropic and OpenAI answer that with a 400 whose text names neither the message nor the id.
 *
 * Two exceptions, both legitimate. [deferredToolNames]: a vendor tool that hands off to a client tool
 * mid-flight ([Tool.ProviderDefined.supportsDeferredResults]) may be answered a turn later. And a call
 * gated by an UNSETTLED [AssistantPart.ApprovalRequest] — one whose decision is still out with a human,
 * or has just arrived and the run is about to act on it. Either way the runtime stopped that round on
 * purpose and the missing piece is not a lost result. Everything else is an error: the loop writes a
 * [ToolOutput.ExecutionDenied] result for every denied call and an error result for every invalid one,
 * so an ordinary non-provider-executed call is always answered in the round it was made.
 *
 * @param allowSystemInMessages whether [messages] may carry its own leading system turns. True by
 *   default, where the reference defaults to false: a stored conversation arrives here WITH the system
 *   turn it was run under, and a seam that maps storage into messages should not have to peel it off
 *   and re-thread it through [instructions]. False is for the caller that funnels every instruction
 *   through [instructions] and wants a stray system turn in the history to be the error it is.
 */
public fun standardizePrompt(
    messages: Prompt,
    instructions: String? = null,
    deferredToolNames: Set<String> = emptySet(),
    allowSystemInMessages: Boolean = true,
): Prompt {
    // Instructions alone are not a prompt: a system-only turn is a 400 on most vendors, because there
    // is nothing for the model to answer.
    if (messages.none { it !is ModelMessage.System }) {
        throw InvalidPromptError("A prompt must contain at least one user or assistant turn.", messages)
    }

    if (!allowSystemInMessages && messages.any { it is ModelMessage.System }) {
        throw InvalidPromptError(
            "System messages are not allowed in the prompt or messages fields. Use the instructions option instead.",
            messages,
        )
    }

    val withInstructions =
        if (instructions == null) messages else listOf(ModelMessage.System(instructions)) + messages

    // Leading only: a system turn is context for the whole conversation, and one that arrives after a
    // user turn is a mid-conversation instruction change the vendors have no representation for.
    val firstNonSystem = withInstructions.indexOfFirst { it !is ModelMessage.System }
    if (firstNonSystem >= 0 && withInstructions.drop(firstNonSystem).any { it is ModelMessage.System }) {
        throw InvalidPromptError(
            "A system message may only appear before the first user or assistant turn.",
            withInstructions,
        )
    }

    val merged = withInstructions.mergeConsecutiveToolMessages()
    merged.requireToolResults(deferredToolNames)
    return merged.filterNot { it is ModelMessage.Tool && it.content.isEmpty() }
}

/**
 * The names whose unanswered calls [standardizePrompt] must tolerate.
 *
 * Keyed by NAME rather than id because [AssistantPart.ToolCall] carries only the name — which also
 * means a caller that renames a deferred tool between turns defeats the exemption, the same
 * limitation the reference has.
 */
internal fun List<Tool>?.deferredToolNames(): Set<String> =
    orEmpty().filterIsInstance<Tool.ProviderDefined>()
        .filter { it.supportsDeferredResults }
        .mapTo(mutableSetOf()) { it.name }

/**
 * Collapses each run of tool turns into one.
 *
 * The absorbed turn's own [ModelMessage.providerOptions] is pushed down onto its last part rather than
 * discarded, because the option that rides there in practice is Anthropic's `cache_control` marking the
 * end of a cacheable prefix — dropping it moves the cache breakpoint and silently doubles the cost of
 * every subsequent turn.
 */
private fun Prompt.mergeConsecutiveToolMessages(): Prompt {
    val out = mutableListOf<ModelMessage>()
    for (message in this) {
        val previous = out.lastOrNull()
        if (message !is ModelMessage.Tool || previous !is ModelMessage.Tool) {
            out += message
            continue
        }
        // An empty turn has no last part to carry its options onto, and the drop that removes it runs
        // after this — so the guard is here, not there.
        val carried = previous.content.dropLast(1) +
            listOfNotNull(previous.content.lastOrNull()?.withOptionsFrom(previous))
        out[out.lastIndex] = ModelMessage.Tool(
            content = carried + message.content,
            providerOptions = message.providerOptions,
        )
    }
    return out
}

private fun ToolPart.withOptionsFrom(message: ModelMessage.Tool): ToolPart {
    val merged = mergeProviderOptions(message.providerOptions, providerOptions) ?: return this
    return when (this) {
        is ToolPart.Result -> copy(providerOptions = merged)
        is ToolPart.ApprovalResponse -> copy(providerOptions = merged)
    }
}

/** Throws if any client-side tool call goes unanswered before the conversation moves on. */
private fun Prompt.requireToolResults(deferredToolNames: Set<String>) {
    val pending = linkedSetOf<String>()
    // A call gated by an approval is WAITING, not unanswered — whether the decision is still out with
    // a human or has just arrived and the runtime is about to act on it. Counting either as a
    // truncated turn is what made a confirm-before-writing flow unrepresentable: the very
    // conversation the feature produces could not be handed back to the runtime that produced it.
    val awaitingApproval = unsettledApprovalCallIds()
    for (message in this) {
        when (message) {
            is ModelMessage.Assistant ->
                message.content.filterIsInstance<AssistantPart.ToolCall>()
                    // A provider-executed call ran on the vendor's servers and is answered — if at all —
                    // inside the assistant turn itself; a deferred vendor tool may legitimately answer
                    // a turn later. Neither is ours to account for.
                    .filterNot {
                        it.providerExecuted ||
                            it.toolName in deferredToolNames ||
                            it.toolCallId in awaitingApproval
                    }
                    .forEach { pending += it.toolCallId }

            is ModelMessage.Tool ->
                message.content.filterIsInstance<ToolPart.Result>()
                    .forEach { pending -= it.toolCallId }

            // The turn moved on. Anything still open will never be answered.
            is ModelMessage.User, is ModelMessage.System ->
                if (pending.isNotEmpty()) throw MissingToolResultsError(pending.toList())
        }
    }
    if (pending.isNotEmpty()) throw MissingToolResultsError(pending.toList())
}

/**
 * Bytes for a remote asset the model cannot fetch itself.
 *
 * [mediaType] is what the server said the bytes are, or null when it did not say.
 */
public data class DownloadedAsset(val bytes: ByteArray, val mediaType: String?) {

    override fun equals(other: Any?): Boolean =
        this === other || (other is DownloadedAsset && bytes.contentEquals(other.bytes) && mediaType == other.mediaType)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mediaType.hashCode()
}

/**
 * Fetches a URL the model declined to fetch for itself.
 *
 * A port rather than a concrete downloader so the runtime keeps no transport of its own: a consumer
 * already holds a configured HTTP client with its proxy, its timeouts and its auth, and a second one
 * built here would be the one that is wrong on a corporate network.
 */
public fun interface AssetDownloader {

    /** Fetches [url]; [mediaType] is what the prompt claims the bytes are. Throwing keeps the URL. */
    public suspend fun download(url: String, mediaType: String): DownloadedAsset
}

/**
 * Replaces every [FileData.Url] the model cannot fetch with the bytes behind it.
 *
 * This is the only caller of [LanguageModel.supportedUrls], and the reason that member exists: a model
 * that fetches `https` PDFs itself should be handed the URL, because downloading a 40MB document here
 * and base64-ing it into the request body costs two round trips and the whole file in memory to arrive
 * at what the vendor would have done anyway.
 *
 * A URL that fails to download is left as a URL rather than dropped or thrown on. The provider may still
 * accept it — [LanguageModel.supportedUrls] is a declaration, not an exhaustive one — and losing a
 * user's attachment because a HEAD request was refused is worse than letting the vendor decide.
 */
public suspend fun Prompt.withDownloadedAssets(
    supportedUrls: Map<String, List<Regex>>,
    download: AssetDownloader,
): Prompt = map { message ->
    when (message) {
        is ModelMessage.User -> message.copy(
            content = message.content.map { part ->
                if (part !is UserPart.File) part else part.copy(data = part.data.resolve(part.mediaType, supportedUrls, download))
            },
        )
        is ModelMessage.Tool -> message.copy(content = message.content.map { it.downloadFiles(supportedUrls, download) })
        else -> message
    }
}

private suspend fun ToolPart.downloadFiles(
    supportedUrls: Map<String, List<Regex>>,
    download: AssetDownloader,
): ToolPart {
    val result = this as? ToolPart.Result ?: return this
    val multipart = result.output as? ToolOutput.Multipart ?: return this
    return result.copy(
        output = multipart.copy(
            value = multipart.value.map { item ->
                if (item !is ToolOutput.Multipart.Item.File) {
                    item
                } else {
                    item.copy(data = item.data.resolve(item.mediaType, supportedUrls, download))
                }
            },
        ),
    )
}

private suspend fun FileData.resolve(
    mediaType: String,
    supportedUrls: Map<String, List<Regex>>,
    download: AssetDownloader,
): FileData {
    val url = (this as? FileData.Url)?.url ?: return this
    if (supportedUrls.supports(mediaType, url)) return this
    return runCatching { download.download(url, mediaType) }
        .fold({ FileData.Bytes(it.bytes) }, { this })
}

/**
 * Whether the model declared it fetches [url] for [mediaType] itself.
 *
 * Keys are matched case-insensitively as an exact media type, as `type/` + star, or as a bare star
 * (a lone star and star-slash-star both mean everything); patterns are matched against the
 * lower-cased URL, as [LanguageModel.supportedUrls] specifies. A bare top-level key request
 * (`image`) matches only the `image/`-prefixed declarations — the reference's rule, kept so a model
 * declaring `image/png` is never consulted for audio.
 */
private fun Map<String, List<Regex>>.supports(mediaType: String, url: String): Boolean {
    val lowered = url.lowercase()
    val wanted = mediaType.lowercase()
    val prefix = wanted.substringBefore('/') + "/*"
    val candidates = entries
        .filter { (key, _) ->
            val k = key.lowercase()
            k == wanted || k == prefix || k == "*" || k == "*/*"
        }
        .map { it.value }
    return candidates.any { patterns -> patterns.any { it.containsMatchIn(lowered) } }
}

/**
 * Rejects call settings no provider can honour, before a request is spent finding out.
 *
 * Deliberately much shorter than the reference's, because most of what it checks — *"temperature must be
 * a number"* — Kotlin's type system has already decided. What is left is the set a well-typed call can
 * still get wrong: a count that is not a count, and a floating-point sampler that is NaN, which
 * serializes to a literal `NaN` no vendor's JSON parser accepts.
 */
public fun CallOptions.validated(): CallOptions {
    requirePositive(maxOutputTokens, "maxOutputTokens")
    requirePositive(topK, "topK")
    requireFinite(temperature, "temperature")
    requireFinite(topP, "topP")
    requireFinite(presencePenalty, "presencePenalty")
    requireFinite(frequencyPenalty, "frequencyPenalty")
    return this
}

private fun requirePositive(value: Int?, argument: String) {
    if (value != null && value < 1) {
        throw InvalidArgumentError("$argument must be at least 1, was $value.", argument)
    }
}

private fun requireFinite(value: Double?, argument: String) {
    if (value != null && !value.isFinite()) {
        throw InvalidArgumentError("$argument must be a finite number, was $value.", argument)
    }
}

/**
 * The tool calls that have an approval request and no result yet.
 *
 * Both halves of "waiting" in one set, because both are legitimate: a decision still out with a human,
 * and a decision that has arrived but whose call the resume has not run yet. Resolved across the whole
 * conversation rather than per message, because the request lives in an assistant turn and its answer
 * in a later tool turn — matching them within one message finds neither half.
 */
internal fun Prompt.unsettledApprovalCallIds(): Set<String> {
    val gated = mutableSetOf<String>()
    for (message in this) {
        if (message is ModelMessage.Assistant) {
            message.content.filterIsInstance<AssistantPart.ApprovalRequest>()
                .forEach { gated += it.toolCallId }
        }
    }
    if (gated.isEmpty()) return emptySet()
    // A result settles the call whatever the approval said — a denial the runtime already wrote is an
    // answer, and treating it as open would ask about it forever.
    for (message in this) {
        if (message is ModelMessage.Tool) {
            message.content.filterIsInstance<ToolPart.Result>().forEach { gated -= it.toolCallId }
        }
    }
    return gated
}
