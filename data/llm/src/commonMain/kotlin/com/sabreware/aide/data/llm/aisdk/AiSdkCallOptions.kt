package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice as AisdkToolChoice
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.ToolChoice
import com.sabreware.aide.core.domain.model.ChatCapabilities

/**
 * AIDE's per-turn request, in the spec's vocabulary.
 *
 * One builder for every `:aisdk` vendor: the sampler knobs, the tool list and the reasoning request are
 * translated here and nowhere else, so a model the catalog knows rejects `temperature` never has it sent,
 * whichever provider serves it.
 *
 * [disabledParams] holds the spec's parameter names ([ChatCapabilities.SamplerParam.wireName]) rather
 * than AIDE's enum, because it is the spec's field that is being omitted. Null means OMIT THE FIELD —
 * several hosted models reject an explicitly-sent value that equals their own default.
 *
 * **A knob still at [ChatGenerationConfig]'s own default is UNSET, not a value.** Those defaults are
 * the on-device engine's baseline (LiteRT must be handed a number), not a choice anyone made for a
 * remote model: sending them meant every Claude, Gemini and GPT reply was capped at 1,024 tokens and
 * sampled at an explicit `topP`/`topK` the vendor then warned about on every call. A remote model's own
 * ceiling and sampling stay in charge until the model's allowlist default or the user's sampler sheet
 * overlays something else ([com.sabreware.aide.core.domain.llm.applyingSampler]).
 */
internal fun buildCallOptions(
    prompt: Prompt,
    config: ChatGenerationConfig,
    tools: List<AideTool>,
    activationState: ToolActivationState?,
    disabledParams: Set<String>,
): CallOptions = CallOptions(
    prompt = prompt,
    maxOutputTokens = config.maxTokens.takeIf { it != BASE.maxTokens },
    temperature = config.temperature.takeIf { it != BASE.temperature && "temperature" !in disabledParams }?.toWireDouble(),
    topP = config.topP.takeIf { it != BASE.topP && "topP" !in disabledParams }?.toWireDouble(),
    topK = config.topK.takeIf { it != BASE.topK && "topK" !in disabledParams },
    tools = activeWireTools(tools, activationState),
    toolChoice = config.toolChoice.toAisdk(),
    reasoning = config.thinking.toReasoningEffort(),
)

/** The baseline every remote knob is compared against — see [buildCallOptions]. */
private val BASE = ChatGenerationConfig()

/**
 * A float sampler value as the decimal the user typed, not its binary expansion: `0.7f.toDouble()` is
 * `0.699999988079071`, which is what would otherwise go on the wire and into every vendor's logs.
 */
private fun Float.toWireDouble(): Double = toString().toDouble()

/**
 * The function tools offered on the wire this turn — every one the user enabled, minus those gated
 * behind an activation the model has not asked for yet.
 *
 * The runtime validates a call against exactly this list, so a model naming a tool it was never offered
 * gets an `INVALID_CALL` result rather than a dispatch. Null (not an empty list) when nothing is offered,
 * because forcing a tool choice with an empty `tools[]` is a 400 on every vendor.
 */
internal fun activeWireTools(tools: List<AideTool>, activationState: ToolActivationState?): List<Tool>? =
    activeFunctionTools(tools, activationState)
        .map { Tool.Function(name = it.name, inputSchema = it.parametersSchema, description = it.description) }
        .takeIf { it.isNotEmpty() }

/** The [AideTool.Function]s behind [activeWireTools], for the executor that dispatches them. */
internal fun activeFunctionTools(tools: List<AideTool>, activationState: ToolActivationState?): List<AideTool.Function> {
    val activated = activationState?.activated.orEmpty()
    return tools.filterIsInstance<AideTool.Function>()
        .filter { tool -> !tool.requiresActivation || (tool.category != null && tool.category in activated) }
}

internal fun ToolChoice.toAisdk(): AisdkToolChoice = when (this) {
    ToolChoice.Auto -> AisdkToolChoice.Auto
    ToolChoice.None -> AisdkToolChoice.None
    ToolChoice.Required -> AisdkToolChoice.Required
    is ToolChoice.Named -> AisdkToolChoice.Specific(name)
}

/**
 * AIDE's thinking request, in the spec's vocabulary.
 *
 * The level strings come from models.dev and are therefore already model-validated, so an unrecognized
 * one falls back to the provider default rather than being guessed at.
 */
internal fun ChatGenerationConfig.ThinkingRequest.toReasoningEffort(): ReasoningEffort = when (this) {
    ChatGenerationConfig.ThinkingRequest.Off -> ReasoningEffort.None
    ChatGenerationConfig.ThinkingRequest.On -> ReasoningEffort.Medium
    is ChatGenerationConfig.ThinkingRequest.Level -> when (level.lowercase()) {
        "none" -> ReasoningEffort.None
        "minimal" -> ReasoningEffort.Minimal
        "low" -> ReasoningEffort.Low
        "medium" -> ReasoningEffort.Medium
        "high" -> ReasoningEffort.High
        "xhigh", "max" -> ReasoningEffort.XHigh
        else -> ReasoningEffort.ProviderDefault
    }
}

/** The spec's parameter name for a sampler knob AIDE's catalog says a model rejects. */
internal fun ChatCapabilities.SamplerParam.wireName(): String = when (this) {
    ChatCapabilities.SamplerParam.Temperature -> "temperature"
    ChatCapabilities.SamplerParam.TopP -> "topP"
    ChatCapabilities.SamplerParam.TopK -> "topK"
    ChatCapabilities.SamplerParam.PresencePenalty -> "presencePenalty"
    ChatCapabilities.SamplerParam.FrequencyPenalty -> "frequencyPenalty"
}
