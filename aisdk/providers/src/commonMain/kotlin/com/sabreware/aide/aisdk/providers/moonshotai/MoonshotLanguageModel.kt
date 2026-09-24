package com.sabreware.aide.aisdk.providers.moonshotai

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Which Moonshot family a model id belongs to, because the thinking rules differ per family and a
 * setting one family requires is a 400 on another.
 */
internal enum class MoonshotFamily {
    KimiK3,
    KimiK27,
    KimiK26,
    KimiK25,
    MoonshotV1,
    Unknown,
}

/** The reference's own id table; an unrecognized id is [MoonshotFamily.Unknown], never guessed at. */
internal fun moonshotFamily(modelId: String): MoonshotFamily = when {
    modelId == "kimi-k3" -> MoonshotFamily.KimiK3
    modelId == "kimi-k2.7-code" || modelId == "kimi-k2.7-code-highspeed" -> MoonshotFamily.KimiK27
    modelId == "kimi-k2.6" -> MoonshotFamily.KimiK26
    modelId == "kimi-k2.5" -> MoonshotFamily.KimiK25
    modelId.startsWith("moonshot-v1-") -> MoonshotFamily.MoonshotV1
    else -> MoonshotFamily.Unknown
}

/**
 * The models that reject `tool_choice: "required"`.
 *
 * Not in the published API reference, which documents `required` without qualification
 * (<https://platform.kimi.ai/docs/api/chat>). Carried from the vendored reference, whose own comment
 * says Moonshot rejects it for exactly these three — a rejection is a live-wire fact a doc page is
 * likelier to omit than to invent, and dropping the setting with a warning is recoverable where a 400
 * is not.
 */
private val REQUIRED_TOOL_CHOICE_REJECTED =
    setOf("kimi-k2.6", "kimi-k2.7-code", "kimi-k2.7-code-highspeed")

/** Effort levels Kimi K3 accepts. The unified scale is mapped onto these three, never sent raw. */
private val K3_EFFORTS = setOf("low", "high", "max")

/**
 * Moonshot's quirks, applied around the OpenAI-compatible model rather than inside it.
 *
 * The load-bearing one is schema rewriting: **every** tool `inputSchema` and every structured-output
 * schema is rewritten for MFJS before the delegate ever sees it — see [normalizeJsonSchemaForMfjs].
 * That has to happen here, while the schemas are still typed values on [CallOptions]. By the time the
 * body exists they are nested inside `tools`, which the body hook may not rewrite, and digging them
 * back out of encoded JSON to patch them would be strictly worse.
 *
 * The rest is family gating. Thinking is spelled differently, or refused, by every Moonshot family:
 * K3 always reasons and rejects the `thinking` field outright, K2.7 cannot have it turned off, K2.6
 * alone can preserve reasoning across turns, and `moonshot-v1` has no reasoning at all. Sending the
 * wrong one is a 400 for a setting the caller had every reason to think was portable, so each is
 * dropped with a warning naming the model.
 */
internal class MoonshotLanguageModel(
    private val delegate: LanguageModel,
    private val family: MoonshotFamily,
) : LanguageModel {

    override val provider: String = MOONSHOT_PROVIDER_ID

    override val modelId: String get() = delegate.modelId

    /** Kimi's vision models fetch image and video URLs themselves, so a URL part is passed through. */
    override suspend fun supportedUrls(): Map<String, List<Regex>> = mapOf(
        "image/*" to listOf(HTTP_URL),
        "video/*" to listOf(HTTP_URL),
    )

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val prepared = prepare(options)
        val result = delegate.doGenerate(prepared.options)
        return result.copy(warnings = result.warnings + prepared.warnings)
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val prepared = prepare(options)
        val result = delegate.doStream(prepared.options)
        return result.copy(
            stream = result.stream.map { part ->
                if (part is StreamPart.StreamStart) {
                    part.copy(warnings = part.warnings + prepared.warnings)
                } else {
                    part
                }
            },
        )
    }

    private class Prepared(val options: CallOptions, val warnings: List<Warning>)

    @Suppress("CyclomaticComplexMethod")
    private fun prepare(options: CallOptions): Prepared {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.get(MOONSHOT_PROVIDER_ID)

        val body = buildJsonObject {
            vendor?.let { carryPassthroughOptions(it, warnings) }
            val thinking = resolveThinking(vendor, options.reasoning, warnings)
            thinking?.let { put("thinking", it) }
            resolveReasoningEffort(vendor, options.reasoning, warnings)?.let { put("reasoning_effort", it) }
        }

        // The caller's own block is REPLACED, never merged over: its keys are the reference's camelCase
        // caller vocabulary, and every one that reaches the wire does so through the translation above.
        // Falling back to the original when nothing was translated is how a `thinking` this family
        // rejects got spread into the body after being warned about and "dropped".
        val merged = if (vendor == null && body.isEmpty()) {
            options.providerOptions
        } else {
            options.providerOptions.orEmpty() + (MOONSHOT_PROVIDER_ID to body)
        }

        return Prepared(
            options = options.copy(
                tools = options.tools?.map { it.normalized() },
                toolChoice = resolveToolChoice(options.toolChoice, warnings),
                responseFormat = options.responseFormat?.normalized(),
                // The effort reaches the wire through the vendor block above, in Moonshot's own
                // vocabulary. Leaving it here as well would send OpenAI's spelling beside it.
                reasoning = ReasoningEffort.ProviderDefault,
                providerOptions = merged,
            ),
            warnings = warnings,
        )
    }

    /** The caller's own documented options, passed through in the vendor's spellings. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.carryPassthroughOptions(
        vendor: JsonObject,
        warnings: MutableList<Warning>,
    ) {
        vendor.optString("promptCacheKey")?.let { put("prompt_cache_key", it) }
        vendor.optString("safetyIdentifier")?.let { put("safety_identifier", it) }
        vendor.optObject("prediction")?.let { put("prediction", it) }
        vendor.optInt("topLogprobs")?.let {
            put("top_logprobs", it)
            // The reference's own note: asking for the top-N implies asking for log-probs at all.
            put("logprobs", true)
        }
        if (vendor.optObject("thinking")?.optInt("budgetTokens") != null) {
            warnings += Warning.Unsupported(
                feature = "thinking.budgetTokens",
                details = "Moonshot Chat Completions does not support budget_tokens; it was omitted.",
            )
        }
    }

    /**
     * `thinking`, per family. Null omits the field, which is the only correct value for K3.
     *
     * `reasoningHistory: "preserved"` is K2.6's alone — it becomes `thinking.keep: "all"` there and
     * warns everywhere else, because a caller who asked for reasoning to survive the turn needs to know
     * it will not.
     */
    private fun resolveThinking(
        vendor: JsonObject?,
        reasoning: ReasoningEffort,
        warnings: MutableList<Warning>,
    ): JsonObject? {
        val requested = vendor?.optObject("thinking")
        val requestedType = requested?.optString("type")
        val preserve = vendor?.optString("reasoningHistory") == "preserved"
        val disablesReasoning = reasoning == ReasoningEffort.None

        return when (family) {
            MoonshotFamily.KimiK3 -> {
                if (requested != null) {
                    warnings += Warning.Unsupported(
                        feature = "thinking",
                        details = "Kimi K3 always reasons and does not accept the thinking field. " +
                            "The option has been omitted.",
                    )
                }
                if (disablesReasoning) {
                    warnings += Warning.Unsupported("reasoning \"none\"", "Kimi K3 reasoning cannot be disabled.")
                }
                null
            }

            MoonshotFamily.KimiK27 -> {
                if (requestedType == "disabled" || disablesReasoning) {
                    warnings += Warning.Unsupported(
                        feature = if (requestedType == "disabled") "thinking.type \"disabled\"" else "reasoning \"none\"",
                        details = "Kimi K2.7 thinking cannot be disabled.",
                    )
                    null
                } else if (requestedType == "enabled") {
                    buildJsonObject { put("type", "enabled") }
                } else {
                    null
                }
            }

            MoonshotFamily.KimiK26 -> {
                val type = requestedType ?: reasoning.toThinkingType()
                if (type == null && !preserve) {
                    null
                } else {
                    buildJsonObject {
                        put("type", type ?: "enabled")
                        if (preserve) put("keep", "all")
                    }
                }
            }

            MoonshotFamily.KimiK25 -> {
                warnPreserveUnsupported(preserve, warnings)
                (requestedType ?: reasoning.toThinkingType())?.let { buildJsonObject { put("type", it) } }
            }

            MoonshotFamily.MoonshotV1 -> {
                if (requested != null) {
                    warnings += Warning.Unsupported(
                        feature = "thinking",
                        details = "thinking is not supported by model \"$modelId\" and has been omitted.",
                    )
                }
                if (reasoning != ReasoningEffort.ProviderDefault && !disablesReasoning) {
                    warnings += Warning.Unsupported(
                        feature = "reasoning",
                        details = "reasoning is not supported by model \"$modelId\".",
                    )
                }
                warnPreserveUnsupported(preserve, warnings)
                null
            }

            MoonshotFamily.Unknown -> {
                if (disablesReasoning) {
                    warnings += Warning.Unsupported(
                        feature = "reasoning \"none\"",
                        details = "Use providerOptions.moonshotai.thinking to control thinking on custom models.",
                    )
                }
                warnPreserveUnsupported(preserve, warnings)
                requestedType?.let { buildJsonObject { put("type", it) } }
            }
        }
    }

    private fun warnPreserveUnsupported(preserve: Boolean, warnings: MutableList<Warning>) {
        if (preserve) {
            warnings += Warning.Unsupported(
                feature = "reasoningHistory \"preserved\"",
                details = "reasoningHistory \"preserved\" is not supported by model \"$modelId\".",
            )
        }
    }

    /** `reasoning_effort` is K3's alone; anywhere else it warns rather than reaching the wire. */
    private fun resolveReasoningEffort(
        vendor: JsonObject?,
        reasoning: ReasoningEffort,
        warnings: MutableList<Warning>,
    ): String? {
        val requested = vendor?.optString("reasoningEffort")
        val supported = family == MoonshotFamily.KimiK3 || family == MoonshotFamily.Unknown
        if (!supported) {
            if (requested != null) {
                warnings += Warning.Unsupported(
                    feature = "reasoningEffort",
                    details = "reasoningEffort is only supported by Kimi K3 and has been omitted " +
                        "for model \"$modelId\".",
                )
            }
            return null
        }
        requested?.let { return it.takeIf { effort -> effort in K3_EFFORTS } }
        return reasoning.toK3Effort()
    }

    private fun resolveToolChoice(choice: ToolChoice?, warnings: MutableList<Warning>): ToolChoice? {
        if (choice != ToolChoice.Required || modelId !in REQUIRED_TOOL_CHOICE_REJECTED) return choice
        warnings += Warning.Unsupported(
            feature = "tool choice \"required\" for model \"$modelId\"",
            details = "Moonshot AI rejects required tool choice for this model. The setting has been " +
                "omitted; use \"auto\" or select a specific tool instead.",
        )
        return null
    }

    /** Every function tool's parameters, rewritten for MFJS. A provider-defined tool has no schema. */
    private fun Tool.normalized(): Tool = when (this) {
        is Tool.Function -> copy(inputSchema = normalizeJsonSchemaForMfjs(inputSchema) as JsonObject)
        is Tool.ProviderDefined -> this
    }

    /**
     * The structured-output schema, rewritten for MFJS with `$schema` removed first.
     *
     * Kimi K2.5 produces nonsensical output when the top-level `$schema` keyword is present even though
     * it otherwise supports structured outputs — so it is stripped for every family rather than made a
     * per-model branch. The caller's original schema still validates the result; only the copy Moonshot
     * sees is narrowed.
     */
    private fun ResponseFormat.normalized(): ResponseFormat = when (this) {
        is ResponseFormat.Json -> schema?.let { original ->
            copy(schema = normalizeJsonSchemaForMfjs(JsonObject(original - "\$schema")) as JsonObject)
        } ?: this
        ResponseFormat.Text -> this
    }
}

private val HTTP_URL = Regex("^https?://")

/** The unified scale, onto the only three efforts Kimi K3 names. */
private fun ReasoningEffort.toK3Effort(): String? = when (this) {
    ReasoningEffort.ProviderDefault, ReasoningEffort.None -> null
    ReasoningEffort.Minimal, ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium, ReasoningEffort.High -> "high"
    ReasoningEffort.XHigh -> "max"
}

/** On the K2.x families the unified scale can only say whether to think at all. */
private fun ReasoningEffort.toThinkingType(): String? = when (this) {
    ReasoningEffort.ProviderDefault -> null
    ReasoningEffort.None -> "disabled"
    else -> "enabled"
}
