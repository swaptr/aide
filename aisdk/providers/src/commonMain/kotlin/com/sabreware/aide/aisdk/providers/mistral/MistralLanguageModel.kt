package com.sabreware.aide.aisdk.providers.mistral

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openaicompatible.REASONING_CONTENT_KEY
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optString
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Mistral quirks, applied around the OpenAI-compatible model.
 *
 * Mistral's chat endpoint is OpenAI-shaped in outline and differs in four details, every one of which
 * fails quietly rather than loudly:
 *
 * - **A trailing assistant message is a PREFILL.** Mistral requires `prefix: true` on it, and its
 *   documentation is explicit that the flag belongs on an assistant message at the end of the list. The
 *   marker rides through the compat model's message-level option passthrough.
 * - **`image_url` is a bare string**, not OpenAI's `{"url": …}` object. The object form is accepted and
 *   then ignored, which presents as a model that cannot see an image the caller definitely attached.
 * - **Assistant reasoning replays as a `thinking` content part**, not on a `reasoning_content` channel.
 *   Mistral's own reasoning guide says to "always replay the full assistant message (including
 *   ThinkChunk) back into the message history", so dropping it costs the model its own train of thought.
 * - **The seed is `random_seed`.** Sent as `seed`, it is an unrecognized field and sampling is not
 *   reproducible — with nothing in the response to say so.
 *
 * `document_url` for PDFs is already handled by the shared prompt encoder, and `ToolChoiceDialect.Mistral`
 * already spells `required` as `any`, so neither is repeated here.
 */
internal class MistralLanguageModel(private val delegate: LanguageModel) : LanguageModel {

    override val provider: String = MISTRAL_PROVIDER_ID

    override val modelId: String get() = delegate.modelId

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val prepared = prepare(options)
        val result = delegate.doGenerate(prepared.options)
        return result.copy(
            finishReason = mistralFinishReason(result.finishReason),
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
                    is StreamPart.Finish -> part.copy(finishReason = mistralFinishReason(part.finishReason))
                    else -> part
                }
            },
        )
    }

    private data class Prepared(val options: CallOptions, val warnings: List<Warning>)

    private fun prepare(options: CallOptions): Prepared {
        val warnings = mutableListOf<Warning>()
        val vendor = options.providerOptions?.get(MISTRAL_PROVIDER_ID)

        val effort = resolveReasoningEffort(vendor?.optString("reasoningEffort"), options.reasoning, warnings)

        val translated = buildJsonObject {
            vendor?.forEach { (key, value) -> if (key !in TRANSLATED_KEYS) put(key, value) }
            vendor?.optBoolean("safePrompt")?.let { put("safe_prompt", it) }
            vendor?.optInt("documentImageLimit")?.let { put("document_image_limit", it) }
            vendor?.optInt("documentPageLimit")?.let { put("document_page_limit", it) }
            vendor?.optString("promptCacheKey")?.let { put("prompt_cache_key", it) }
            vendor?.optBoolean("parallelToolCalls")?.let { put("parallel_tool_calls", it) }
            effort?.let { put("reasoning_effort", it) }
        }

        return Prepared(
            options = options.copy(
                prompt = options.prompt.withMistralReasoningChannel().withMistralPrefix(),
                // The delegate would spell the neutral level in OpenAI's vocabulary for EVERY model;
                // Mistral's own spelling is resolved above and rides in the vendor options instead.
                reasoning = ReasoningEffort.ProviderDefault,
                providerOptions = options.providerOptions.orEmpty() +
                    (MISTRAL_PROVIDER_ID to translated),
            ),
            warnings = warnings,
        )
    }

    /**
     * `reasoning_effort` goes only to the models Mistral lists as taking it — the vendor rejects it
     * elsewhere. A caller's explicit spelling wins over the neutral level, which folds onto the two
     * values Mistral offers: `None` is `none`, every other level is `high`. Off the list, either way
     * of asking warns rather than going out; the reference drops the explicit one silently.
     */
    private fun resolveReasoningEffort(
        explicit: String?,
        neutral: ReasoningEffort,
        warnings: MutableList<Warning>,
    ): String? {
        if (modelId !in REASONING_EFFORT_MODEL_IDS) {
            if (explicit != null) warnings += Warning.Unsupported("reasoningEffort", NO_REASONING_CONFIG)
            if (neutral != ReasoningEffort.ProviderDefault) warnings += Warning.Unsupported("reasoning", NO_REASONING_CONFIG)
            return null
        }
        if (explicit != null) {
            if (explicit in REASONING_EFFORTS) return explicit
            warnings += Warning.Unsupported(
                feature = "reasoningEffort",
                details = "Mistral accepts only ${REASONING_EFFORTS.joinToString(" or ")}.",
            )
        }
        return when (neutral) {
            ReasoningEffort.ProviderDefault -> null
            ReasoningEffort.None -> "none"
            else -> "high"
        }
    }

    private companion object {
        /** Read under this port's camelCase names, sent under Mistral's — never spread as both. */
        val TRANSLATED_KEYS = setOf(
            "safePrompt",
            "documentImageLimit",
            "documentPageLimit",
            "promptCacheKey",
            "parallelToolCalls",
            "reasoningEffort",
        )

        val REASONING_EFFORTS = listOf("high", "none")

        const val NO_REASONING_CONFIG = "This model does not support reasoning configuration."

        /** The models that take `reasoning_effort` — https://api.mistral.ai/v1/models (2026-09-10). */
        val REASONING_EFFORT_MODEL_IDS = setOf(
            "glm-5-2",
            "labs-leanstral-1-5",
            "labs-leanstral-1-5-1",
            "magistral-medium-latest",
            "magistral-small-latest",
            "mistral-medium",
            "mistral-medium-2604",
            "mistral-medium-3",
            "mistral-medium-3-5",
            "mistral-medium-3.5",
            "mistral-medium-latest",
            "mistral-small-2603",
            "mistral-small-latest",
            "mistral-vibe-cli-fast",
            "mistral-vibe-cli-latest",
            "mistral-vibe-cli-with-tools",
            "zai-glm-5-2",
        )
    }
}

/**
 * Puts each assistant reasoning part on the wire's reasoning channel, so the body transform can find it.
 *
 * The shared encoder emits `reasoning_content` only for a part that already carries one — it will not
 * fabricate a replay channel from display text, which is right, because on most vendors an invented
 * channel is a field the server never sent. Mistral is the exception: its documentation asks for the
 * reasoning back, so this fills the channel in for the parts that lack it, and
 * [mistralRequestBody] then reshapes it into the `thinking` part Mistral actually reads. A part that
 * already carries the key is left alone — that one came from the vendor and replays verbatim.
 */
private fun Prompt.withMistralReasoningChannel(): Prompt = map { message ->
    val assistant = message as? ModelMessage.Assistant ?: return@map message
    if (assistant.content.none { it is AssistantPart.Reasoning }) return@map message
    assistant.copy(
        content = assistant.content.map { part ->
            val reasoning = part as? AssistantPart.Reasoning ?: return@map part
            val options = reasoning.providerOptions.orEmpty()
            val vendor = options[MISTRAL_PROVIDER_ID] ?: JsonObject(emptyMap())
            if (vendor[REASONING_CONTENT_KEY] != null || reasoning.text.isEmpty()) return@map part
            reasoning.copy(
                providerOptions = options + (
                    MISTRAL_PROVIDER_ID to JsonObject(
                        vendor + (REASONING_CONTENT_KEY to JsonPrimitive(reasoning.text)),
                    )
                    ),
            )
        },
    )
}

/**
 * Marks a trailing assistant message as a prefill.
 *
 * Unconditional, matching the reference and Mistral's own description of the field: an assistant turn
 * in the final position IS the start of the answer the model is being asked to continue. A caller that
 * ends a prompt with an assistant message and does not want it continued has ended the prompt wrongly,
 * and Mistral has no other way to read it.
 */
private fun Prompt.withMistralPrefix(): Prompt {
    val last = lastOrNull() as? ModelMessage.Assistant ?: return this
    val options = last.providerOptions.orEmpty()
    val vendor = options[MISTRAL_PROVIDER_ID] ?: JsonObject(emptyMap())
    val marked = JsonObject(vendor + ("prefix" to JsonPrimitive(true)))
    return dropLast(1) + last.copy(providerOptions = options + (MISTRAL_PROVIDER_ID to marked))
}

/**
 * The request body, corrected where the shared encoder wrote OpenAI's spelling of a Mistral field.
 *
 * These are the two rewrites nothing else can reach: `seed` is a key the ENGINE writes, and the message
 * content shapes live under `messages`, which a caller's options may not touch.
 */
internal fun mistralRequestBody(body: JsonObject): JsonObject {
    var out = body
    (out["seed"] as? JsonPrimitive)?.let { out = JsonObject(out - "seed" + ("random_seed" to it)) }
    (out["messages"] as? JsonArray)?.let { messages ->
        out = JsonObject(out + ("messages" to JsonArray(messages.map { it.toMistralMessage() })))
    }
    return out
}

private fun kotlinx.serialization.json.JsonElement.toMistralMessage(): JsonObject {
    val message = this as? JsonObject ?: return JsonObject(emptyMap())
    val withImages = (message["content"] as? JsonArray)?.let { parts ->
        JsonObject(message + ("content" to JsonArray(parts.map { it.toMistralContentPart() })))
    } ?: message
    return withImages.withThinkingContent()
}

/** Mistral's `image_url` is the URL itself; OpenAI wraps it in an object the endpoint then ignores. */
private fun kotlinx.serialization.json.JsonElement.toMistralContentPart(): JsonObject {
    val part = this as? JsonObject ?: return JsonObject(emptyMap())
    if ((part["type"] as? JsonPrimitive)?.content != "image_url") return part
    val url = (part["image_url"] as? JsonObject)?.get("url") ?: return part
    return JsonObject(part + ("image_url" to url))
}

/**
 * Moves an assistant turn's `reasoning_content` into the `thinking` content part Mistral replays.
 *
 * Reasoning leads the content array. The encoded message carries the text and the reasoning as two
 * separate fields, so their original interleaving is no longer visible here — and reasoning-then-answer
 * is both the order a model produces them in and the order Mistral's own examples show.
 */
private fun JsonObject.withThinkingContent(): JsonObject {
    val reasoning = (this["reasoning_content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (reasoning.isNullOrEmpty()) return this
    val text = (this["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val parts = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "thinking")
                put("thinking", buildJsonArray { add(textChunk(reasoning)) })
                // Closed: this block was completed on a previous turn, not left mid-thought.
                put("closed", true)
            },
        )
        if (!text.isNullOrEmpty()) add(textChunk(text))
    }
    return JsonObject(this - "reasoning_content" + ("content" to parts))
}

private fun textChunk(text: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
}

/**
 * `model_length` is Mistral's own spelling of a length stop.
 *
 * Unmapped it arrives as `Other`, so a turn truncated by the context window is indistinguishable from
 * one that finished — and a loop keyed on the finish reason will not know to summarize and retry.
 */
internal fun mistralFinishReason(finish: FinishReason): FinishReason =
    if (finish.raw == "model_length") finish.copy(unified = FinishReason.Unified.Length) else finish
