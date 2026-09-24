package com.sabreware.aide.aisdk.providers.deepseek

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidPromptError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The DeepSeek departures, applied around the OpenAI-compatible model rather than inside it.
 *
 * Four of them are wire-visible and none is expressible as a table row:
 *
 * - **Thinking silently voids the samplers.** DeepSeek documents that `temperature`, `top_p`,
 *   `presence_penalty` and `frequency_penalty` "will not trigger an error but will also have no
 *   effect" under thinking mode (https://api-docs.deepseek.com/guides/thinking_mode). Forwarding them
 *   is the worst outcome available: the call succeeds, the knob does nothing, and nothing says so. They
 *   are dropped here WITH a warning.
 * - **Its effort vocabulary is three values, not five.** `low`, `high`, `max` are all DeepSeek accepts
 *   (https://api-docs.deepseek.com/api/create-chat-completion). The shared model would send `medium`
 *   verbatim, so the neutral levels are remapped before they reach the wire.
 * - **Prefix completion is beta-gated and position-gated.** `prefix: true` belongs on the FINAL
 *   assistant message and only against a `/beta` base URL
 *   (https://api-docs.deepseek.com/guides/chat_prefix_completion). Each violation fails here, before a
 *   request is spent discovering it.
 * - **`insufficient_system_resource` is a finish reason.** It is in DeepSeek's documented vocabulary
 *   and in no shared table, so unmapped it arrives as `Other` — a capacity failure that reads as an
 *   ordinary end of turn.
 * - **Image parts are four formats and 8192 characters of URL.** Checked before the request
 *   (`DeepSeekFileParts.kt`), because the vendor's answer to either is a 400 that names neither.
 *
 * Everything else — the wire, streaming, the `reasoning_content` channel — IS the OpenAI-compatible
 * model, so this wraps it rather than re-implementing it.
 */
internal class DeepSeekLanguageModel(
    private val delegate: LanguageModel,
    /** True for a `/beta` base URL, which is what gates prefix completion and strict tools. */
    private val isBeta: Boolean,
) : LanguageModel {

    override val provider: String = DEEPSEEK_PROVIDER_ID

    override val modelId: String get() = delegate.modelId

    /** DeepSeek's vision models fetch image URLs themselves, so a URL part is passed through. */
    override suspend fun supportedUrls(): Map<String, List<Regex>> =
        mapOf("image/*" to listOf(HTTP_URL))

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val prepared = prepare(options)
        val result = delegate.doGenerate(prepared.options)
        return result.copy(
            finishReason = deepSeekFinishReason(result.finishReason),
            warnings = result.warnings + prepared.warnings,
        )
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val prepared = prepare(options)
        val result = delegate.doStream(prepared.options)
        return result.copy(
            stream = result.stream.map { part ->
                when (part) {
                    is StreamPart.StreamStart -> part.copy(warnings = part.warnings + prepared.warnings)
                    is StreamPart.Finish -> part.copy(finishReason = deepSeekFinishReason(part.finishReason))
                    else -> part
                }
            },
        )
    }

    private class Prepared(val options: CallOptions, val warnings: List<Warning>)

    @Suppress("CyclomaticComplexMethod")
    private fun prepare(options: CallOptions): Prepared {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.get(DEEPSEEK_PROVIDER_ID)

        validatePrefix(options.prompt)
        validateStrictTools(options.tools)
        validateDeepSeekImageParts(options.prompt)

        val thinkingType = resolveThinkingType(vendor, options.reasoning, warnings)
        val thinkingEnabled = thinkingType != DISABLED &&
            (thinkingType != null || modelId == REASONER_MODEL || isDeepSeekV4Model(modelId))

        var temperature = options.temperature
        var topP = options.topP
        if (thinkingEnabled) {
            // Accepted and ignored by the vendor, which is why this warns rather than throwing: the
            // call would otherwise succeed with a sampler the caller believes is in effect.
            if (temperature != null) {
                warnings += Warning.Unsupported(
                    feature = "temperature",
                    details = "DeepSeek ignores temperature while thinking is enabled. " +
                        "Set providerOptions.deepseek.thinking.type to \"disabled\" to use it.",
                )
                temperature = null
            }
            if (topP != null) {
                warnings += Warning.Unsupported(
                    feature = "topP",
                    details = "DeepSeek ignores topP while thinking is enabled. " +
                        "Set providerOptions.deepseek.thinking.type to \"disabled\" to use it.",
                )
                topP = null
            }
        }

        // Deprecated by the vendor outright, thinking or not.
        var frequencyPenalty = options.frequencyPenalty
        var presencePenalty = options.presencePenalty
        if (frequencyPenalty != null) {
            warnings += Warning.Deprecated(
                setting = "frequencyPenalty",
                message = "DeepSeek has deprecated frequencyPenalty and ignores it; it was omitted.",
            )
            frequencyPenalty = null
        }
        if (presencePenalty != null) {
            warnings += Warning.Deprecated(
                setting = "presencePenalty",
                message = "DeepSeek has deprecated presencePenalty and ignores it; it was omitted.",
            )
            presencePenalty = null
        }

        val effort = resolveEffort(vendor, options.reasoning, thinkingType, warnings)

        return Prepared(
            options = options.copy(
                temperature = temperature,
                topP = topP,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
                // Taken over entirely: the shared model would otherwise write `reasoning_effort:
                // "medium"`, a level DeepSeek does not accept. Everything DeepSeek-shaped is emitted
                // through the vendor block below instead.
                reasoning = ReasoningEffort.ProviderDefault,
                providerOptions = options.providerOptions.orEmpty() +
                    (DEEPSEEK_PROVIDER_ID to bodyOptions(vendor, thinkingType, effort)),
            ),
            warnings = warnings,
        )
    }

    /**
     * `prefix: true` is a message-level option, so it reaches the wire on its own — what it needs from
     * here is the two rules the vendor documents and the wire cannot enforce.
     */
    private fun validatePrefix(prompt: List<ModelMessage>) {
        prompt.forEachIndexed { index, message ->
            val prefix = message.providerOptions?.get(DEEPSEEK_PROVIDER_ID)?.optBoolean("prefix") ?: return@forEachIndexed
            if (!prefix) return@forEachIndexed
            if (message !is ModelMessage.Assistant) {
                throw InvalidPromptError(
                    "DeepSeek prefix completion requires `prefix` on an assistant message.",
                    prompt,
                )
            }
            if (index != prompt.lastIndex) {
                throw InvalidPromptError(
                    "DeepSeek prefix completion requires the prefixed assistant message to be last.",
                    prompt,
                )
            }
            if (!isBeta) {
                throw UnsupportedFunctionalityError(
                    functionality = "DeepSeek prefix completion",
                    message = "DeepSeek prefix completion requires a base URL ending in `/beta`.",
                )
            }
        }
    }

    /** Strict tools are beta-only, and DeepSeek rejects a request that mixes strict with non-strict. */
    private fun validateStrictTools(tools: List<Tool>?) {
        val functions = tools.orEmpty().filterIsInstance<Tool.Function>()
        if (functions.none { it.strict == true }) return
        if (!isBeta) {
            throw UnsupportedFunctionalityError(
                functionality = "DeepSeek strict tool calls",
                message = "DeepSeek strict tool calls require a base URL ending in `/beta`.",
            )
        }
        if (functions.any { it.strict != true }) {
            throw UnsupportedFunctionalityError(
                functionality = "mixed DeepSeek strict and non-strict tool calls",
                message = "DeepSeek strict mode requires every function tool in the request to set strict.",
            )
        }
    }

    /**
     * Which thinking mode this call asks for, in DeepSeek's own spelling.
     *
     * An explicit vendor option wins; otherwise the neutral [ReasoningEffort] decides, so a caller who
     * never learned DeepSeek's option still turns thinking off with `ReasoningEffort.None`.
     */
    private fun resolveThinkingType(
        vendor: JsonObject?,
        reasoning: ReasoningEffort,
        warnings: MutableList<Warning>,
    ): String? {
        val requested = vendor?.optObject("thinking")?.optString("type")
        return when {
            requested == ADAPTIVE -> {
                // Not in DeepSeek's documented enum; the reference accepts it as a legacy alias.
                warnings += Warning.Compatibility(
                    feature = "thinking.type",
                    details = "\"adaptive\" is not a DeepSeek value; sent as \"$ENABLED\".",
                )
                ENABLED
            }
            requested != null -> {
                if (requested !in THINKING_TYPES) {
                    throw InvalidArgumentError(
                        "DeepSeek thinking.type must be one of $THINKING_TYPES, got \"$requested\".",
                        "thinking.type",
                    )
                }
                requested
            }
            reasoning == ReasoningEffort.None -> DISABLED
            reasoning != ReasoningEffort.ProviderDefault -> ENABLED
            else -> null
        }
    }

    /** The effort level, remapped onto the three values DeepSeek accepts. */
    private fun resolveEffort(
        vendor: JsonObject?,
        reasoning: ReasoningEffort,
        thinkingType: String?,
        warnings: MutableList<Warning>,
    ): String? {
        if (thinkingType == DISABLED) return null
        vendor?.optString("reasoningEffort")?.let { requested ->
            val mapped = LEGACY_EFFORTS[requested] ?: requested
            if (mapped !in EFFORTS) {
                throw InvalidArgumentError(
                    "DeepSeek reasoningEffort must be one of $EFFORTS, got \"$requested\".",
                    "reasoningEffort",
                )
            }
            if (mapped != requested) {
                warnings += Warning.Compatibility(
                    feature = "reasoningEffort",
                    details = "\"$requested\" is not a DeepSeek value; sent as \"$mapped\".",
                )
            }
            return mapped
        }
        return when (reasoning) {
            ReasoningEffort.Minimal, ReasoningEffort.Low -> "low"
            ReasoningEffort.Medium, ReasoningEffort.High -> "high"
            ReasoningEffort.XHigh -> "max"
            ReasoningEffort.None, ReasoningEffort.ProviderDefault -> null
        }
    }

    /**
     * The vendor block the compat model spreads into the request body.
     *
     * Only documented options survive. `logprobs` is forced on whenever `topLogprobs` is set, because
     * DeepSeek requires the pair and rejects the second without the first.
     */
    private fun bodyOptions(vendor: JsonObject?, thinkingType: String?, effort: String?): JsonObject =
        buildJsonObject {
            val logprobs = vendor?.optBoolean("logprobs")
            val topLogprobs = vendor?.optInt("topLogprobs")
            if (logprobs == true || topLogprobs != null) put("logprobs", true)
            topLogprobs?.let {
                if (it !in TOP_LOGPROBS_RANGE) {
                    throw InvalidArgumentError(
                        "DeepSeek topLogprobs must be in $TOP_LOGPROBS_RANGE, got $it.",
                        "topLogprobs",
                    )
                }
                put("top_logprobs", it)
            }
            vendor?.optString("userId")?.let { userId ->
                if (userId.length > USER_ID_MAX || !USER_ID.matches(userId)) {
                    throw InvalidArgumentError(
                        "DeepSeek userId must match $USER_ID and be at most $USER_ID_MAX characters.",
                        "userId",
                    )
                }
                put("user_id", userId)
            }
            thinkingType?.let { put("thinking", buildJsonObject { put("type", it) }) }
            effort?.let { put("reasoning_effort", it) }
        }
}

/**
 * Whether this assistant turn carries a reasoning part.
 *
 * DeepSeek requires `reasoning_content` echoed back on EVERY assistant turn of a tool-calling
 * conversation — "even for turns where the model did not perform a tool call", or the API answers 400
 * (https://api-docs.deepseek.com/guides/thinking_mode). See [DeepSeekProvider] for why the empty
 * back-fill that satisfies it cannot be expressed from here.
 */
internal fun ModelMessage.Assistant.hasReasoning(): Boolean =
    content.any { it is AssistantPart.Reasoning }

/**
 * DeepSeek's one extra finish reason, mapped — with the raw string kept, as everywhere.
 *
 * `insufficient_system_resource` means the platform ran out of capacity mid-request. Left unmapped it
 * arrives as `Other`, which a loop keyed on the finish reason reads as an ordinary completion, so a
 * truncated answer is indistinguishable from a finished one.
 */
internal fun deepSeekFinishReason(finish: FinishReason): FinishReason =
    if (finish.raw == "insufficient_system_resource") {
        finish.copy(unified = FinishReason.Unified.Error)
    } else {
        finish
    }

private val HTTP_URL = Regex("^https?://")

private const val ENABLED = "enabled"
private const val DISABLED = "disabled"
private const val ADAPTIVE = "adaptive"
private const val REASONER_MODEL = "deepseek-reasoner"
private const val V4_MARKER = "deepseek-v4"
private const val FLASH_ALIAS = "deepseek-flash"
private const val PRO_ALIAS = "deepseek-pro"

/**
 * Whether [modelId] is a V4-generation model: thinking on by default, `reasoning_content` required on
 * every assistant turn.
 *
 * DeepSeek publishes V4 under versioned ids (`deepseek-v4-pro`, `deepseek-v4-flash-*`) AND unversioned
 * aliases (`deepseek-flash`, currently serving V4.1 Flash). Only the legacy `deepseek-chat` /
 * `deepseek-reasoner` ids predate V4, so the aliases have to be recognised or a `deepseek-flash`
 * caller gets their samplers silently ignored — the exact failure the versioned check warns about.
 */
internal fun isDeepSeekV4Model(modelId: String): Boolean =
    modelId.contains(V4_MARKER) || modelId.startsWith(FLASH_ALIAS) || modelId.startsWith(PRO_ALIAS)

private val THINKING_TYPES = setOf(ENABLED, DISABLED)

/** DeepSeek accepts exactly these three; the neutral levels are folded onto them. */
private val EFFORTS = setOf("low", "high", "max")

/** Levels the reference accepted before DeepSeek narrowed the enum, kept as aliases with a warning. */
private val LEGACY_EFFORTS = mapOf("medium" to "high", "xhigh" to "max")

private val TOP_LOGPROBS_RANGE = 0..20

private val USER_ID = Regex("^[a-zA-Z0-9_-]+$")

private const val USER_ID_MAX = 512
