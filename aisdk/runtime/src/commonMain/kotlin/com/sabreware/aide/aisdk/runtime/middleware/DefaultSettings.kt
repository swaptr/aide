package com.sabreware.aide.aisdk.runtime.middleware

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingModelMiddleware
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.LanguageModelMiddleware
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.runtime.mergeProviderOptions

/**
 * Supplies settings a call did not set.
 *
 * The call always wins. That is the direction that makes this composable: a middleware stack is
 * configuration, and configuration that overrode what the caller explicitly asked for would make a
 * per-call `temperature` silently do nothing — a bug that shows up as "the model ignores my settings"
 * and points at the provider.
 *
 * [headers] and [providerOptions] merge instead of being replaced, because they are namespaced bags
 * rather than single values: a default that sets Anthropic's `betas` and a call that sets its
 * `thinking` budget both mean what they say, and replacing would lose one of them. Within a namespace
 * the merge is deep and the call's value still wins — see `mergeJsonObjects` for why a list is replaced
 * rather than appended.
 *
 * @param maxOutputTokens generation cap to use when the call sets none.
 * @param temperature sampling temperature to use when the call sets none.
 * @param stopSequences stop sequences to use when the call sets none.
 * @param topP nucleus-sampling cutoff to use when the call sets none.
 * @param topK top-k cutoff to use when the call sets none.
 * @param presencePenalty presence penalty to use when the call sets none.
 * @param frequencyPenalty frequency penalty to use when the call sets none.
 * @param seed sampling seed to use when the call sets none.
 * @param responseFormat response format to use when the call sets none.
 * @param tools the tool list to offer when the call offers none.
 * @param toolChoice tool-choice mode to use when the call sets none.
 * @param headers merged under the call's headers — the call's spelling of a header wins.
 * @param providerOptions merged under the call's options, deeply, per provider namespace.
 */
@Suppress("LongParameterList")
public fun defaultSettings(
    maxOutputTokens: Int? = null,
    temperature: Double? = null,
    stopSequences: List<String>? = null,
    topP: Double? = null,
    topK: Int? = null,
    presencePenalty: Double? = null,
    frequencyPenalty: Double? = null,
    seed: Int? = null,
    responseFormat: ResponseFormat? = null,
    tools: List<Tool>? = null,
    toolChoice: ToolChoice? = null,
    headers: Map<String, String>? = null,
    providerOptions: ProviderOptions? = null,
): LanguageModelMiddleware = object : LanguageModelMiddleware {

    override suspend fun transformParams(
        type: LanguageModelMiddleware.CallType,
        params: CallOptions,
        model: LanguageModel,
    ): CallOptions = params.copy(
        maxOutputTokens = params.maxOutputTokens ?: maxOutputTokens,
        temperature = params.temperature ?: temperature,
        stopSequences = params.stopSequences ?: stopSequences,
        topP = params.topP ?: topP,
        topK = params.topK ?: topK,
        presencePenalty = params.presencePenalty ?: presencePenalty,
        frequencyPenalty = params.frequencyPenalty ?: frequencyPenalty,
        seed = params.seed ?: seed,
        responseFormat = params.responseFormat ?: responseFormat,
        tools = params.tools ?: tools,
        toolChoice = params.toolChoice ?: toolChoice,
        headers = if (headers == null) params.headers else headers + (params.headers ?: emptyMap()),
        providerOptions = mergeProviderOptions(providerOptions, params.providerOptions),
    )
}

/**
 * Supplies embedding-call settings the call did not set — [defaultSettings], for [EmbeddingModel]s.
 *
 * The same precedence rule, because it is the only composable one: the call always wins. [headers] and
 * [providerOptions] are the two knobs an embedding call has beyond its values, and both are namespaced
 * bags, so they merge rather than replace — a default that pins Voyage's `input_type` and a call that
 * sets its `truncation` both survive, and within a namespace the call's value wins deeply.
 *
 * @param headers merged under the call's headers — the call's spelling of a header wins.
 * @param providerOptions merged under the call's options, deeply, per provider namespace.
 */
public fun defaultEmbeddingSettings(
    headers: Map<String, String>? = null,
    providerOptions: ProviderOptions? = null,
): EmbeddingModelMiddleware = object : EmbeddingModelMiddleware {

    override suspend fun transformParams(
        params: EmbeddingCallOptions,
        model: EmbeddingModel,
    ): EmbeddingCallOptions = params.copy(
        headers = if (headers == null) params.headers else headers + (params.headers ?: emptyMap()),
        providerOptions = mergeProviderOptions(providerOptions, params.providerOptions),
    )
}

/**
 * Prepends instructions to a call that has none of its own.
 *
 * "None of its own" means no system turn anywhere in the prompt, not an empty one: a caller that sent
 * instructions has decided what this model is for, and a second set arriving underneath would give the
 * model two answers to that question. This is a default, not an addition.
 *
 * @param instructions the system text to prepend when the prompt carries no system turn of its own.
 */
public fun defaultInstructions(instructions: String): LanguageModelMiddleware =
    object : LanguageModelMiddleware {

        override suspend fun transformParams(
            type: LanguageModelMiddleware.CallType,
            params: CallOptions,
            model: LanguageModel,
        ): CallOptions =
            if (instructions.isEmpty() || params.prompt.any { it is ModelMessage.System }) {
                params
            } else {
                params.copy(prompt = listOf(ModelMessage.System(instructions)) + params.prompt)
            }
    }
