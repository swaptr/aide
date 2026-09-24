package com.sabreware.aide.aisdk.providers.zai

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Z.AI quirks, applied around the OpenAI-compatible model rather than inside it.
 *
 * A wrapper, not a subclass and not new knobs on the compat model: the tool-choice rule and the finish
 * vocabulary are Z.AI's alone, and threading either through the shared model would grow it a parameter
 * with exactly one caller. (If a second vendor turns out to share the "auto-only tool choice" shape,
 * lifting it into a compat knob is the architecture pass's call — noted, deliberately not done here.)
 *
 * What happens on each call, in order:
 *
 * 1. `providerOptions["zai"]` is translated from the reference's camelCase names to the wire's
 *    snake_case fields — see [translateOptions]. The compat model then spreads the translated object
 *    into the request body verbatim, which is how `do_sample`, `thinking`, `tool_stream` and the rest
 *    reach the wire without the shared request type learning any of them.
 * 2. The tool choice is normalized: `none` drops the tools instead of being sent, anything but `auto`
 *    warns and falls back — Z.AI supports only automatic selection.
 * 3. The delegate runs the call; its finish reason is then remapped through Z.AI's vocabulary
 *    ([zaiFinishReason]) on both the streaming and non-streaming paths.
 */
internal class ZaiLanguageModel(private val delegate: LanguageModel) : LanguageModel {

    override val provider: String = ZAI_PROVIDER_ID

    override val modelId: String get() = delegate.modelId

    /**
     * GLM's vision models fetch image and video URLs for themselves, so a URL part is passed through
     * rather than downloaded and re-uploaded — the reference declares exactly these two wildcards.
     */
    override suspend fun supportedUrls(): Map<String, List<Regex>> = mapOf(
        "image/*" to listOf(HTTP_URL),
        "video/*" to listOf(HTTP_URL),
    )

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val normalized = normalize(options)
        val result = delegate.doGenerate(normalized.options)
        return result.copy(
            finishReason = zaiFinishReason(result.finishReason),
            warnings = result.warnings + normalized.warnings,
        )
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val normalized = normalize(options)
        val result = delegate.doStream(normalized.options)
        return result.copy(
            stream = result.stream.map { part ->
                when (part) {
                    is StreamPart.StreamStart ->
                        part.copy(warnings = part.warnings + normalized.warnings)
                    is StreamPart.Finish ->
                        part.copy(finishReason = zaiFinishReason(part.finishReason))
                    else -> part
                }
            },
        )
    }

    private data class Normalized(val options: CallOptions, val warnings: List<Warning>)

    private fun normalize(options: CallOptions): Normalized {
        val warnings = mutableListOf<Warning>()

        var tools = options.tools
        var toolChoice = options.toolChoice
        when (toolChoice) {
            null, ToolChoice.Auto -> Unit
            // "No tools" is expressed by sending none: Z.AI has no `tool_choice: "none"`.
            ToolChoice.None -> {
                tools = null
                toolChoice = null
            }
            // Neither `required` nor a named tool is expressible; automatic selection is all there is.
            else -> {
                val spelling = if (toolChoice is ToolChoice.Required) "required" else "tool"
                warnings += Warning.Unsupported(
                    feature = "toolChoice $spelling",
                    details = "Z.AI currently supports only automatic tool selection.",
                )
                toolChoice = null
            }
        }

        val zai = options.providerOptions?.get(ZAI_PROVIDER_ID)
        val providerOptions = if (zai == null) {
            options.providerOptions
        } else {
            options.providerOptions.orEmpty() + (ZAI_PROVIDER_ID to translateOptions(zai))
        }

        return Normalized(
            options = options.copy(tools = tools, toolChoice = toolChoice, providerOptions = providerOptions),
            warnings = warnings,
        )
    }
}

private val HTTP_URL = Regex("^https?://")

/**
 * `providerOptions["zai"]`, translated from the reference's documented camelCase names to the wire.
 *
 * Only the known options survive — the reference validates against a schema that strips unknown keys,
 * and spreading an unrecognized key into the body would hand Z.AI a field it never documented. A value
 * of the wrong JSON type reads as absent (the house rule); a value of the right type but outside the
 * documented contract throws [InvalidArgumentError] BEFORE the request, because sending it is a
 * guaranteed vendor 400 with a worse message.
 */
@Suppress("ThrowsCount")
internal fun translateOptions(zai: JsonObject): JsonObject = buildJsonObject {
    zai.optBoolean("doSample")?.let { put("do_sample", it) }

    zai.optObject("thinking")?.let { thinking ->
        put(
            "thinking",
            buildJsonObject {
                thinking.optString("type")?.let { type ->
                    if (type !in THINKING_TYPES) {
                        throw InvalidArgumentError(
                            message = "Z.AI thinking.type must be one of $THINKING_TYPES, got \"$type\".",
                            argument = "thinking.type",
                        )
                    }
                    put("type", type)
                }
                thinking.optBoolean("clearThinking")?.let { put("clear_thinking", it) }
            },
        )
    }

    zai.optString("reasoningEffort")?.let { effort ->
        if (effort !in REASONING_EFFORTS) {
            throw InvalidArgumentError(
                message = "Z.AI reasoningEffort must be one of $REASONING_EFFORTS, got \"$effort\".",
                argument = "reasoningEffort",
            )
        }
        put("reasoning_effort", effort)
    }

    zai.optBoolean("toolStream")?.let { put("tool_stream", it) }

    zai.optString("requestId")?.let { requestId ->
        if (requestId.length !in REQUEST_ID_LENGTH) {
            throw InvalidArgumentError(
                message = "Z.AI requestId must be $REQUEST_ID_LENGTH characters long, " +
                    "got ${requestId.length}.",
                argument = "requestId",
            )
        }
        put("request_id", requestId)
    }

    zai.optString("userId")?.let { userId ->
        if (userId.length !in USER_ID_LENGTH) {
            throw InvalidArgumentError(
                message = "Z.AI userId must be $USER_ID_LENGTH characters long, got ${userId.length}.",
                argument = "userId",
            )
        }
        put("user_id", userId)
    }
}

private val THINKING_TYPES = setOf("enabled", "disabled")

private val REASONING_EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

private val REQUEST_ID_LENGTH = 6..64

private val USER_ID_LENGTH = 6..128

/**
 * Z.AI's finish vocabulary, mapped onto the unified reasons — with the raw string kept, as everywhere.
 *
 * Unmapped, all three arrive as `Other`: a safety refusal (`sensitive`) looks like an unremarkable end
 * of turn, an overflowed context (`model_context_window_exceeded`) is indistinguishable from success,
 * and a vendor-side transport failure (`network_error`) reports neither retryably nor loudly.
 */
internal fun zaiFinishReason(finish: FinishReason): FinishReason = when (finish.raw) {
    "sensitive" -> finish.copy(unified = FinishReason.Unified.ContentFilter)
    "model_context_window_exceeded" -> finish.copy(unified = FinishReason.Unified.Length)
    "network_error" -> finish.copy(unified = FinishReason.Unified.Error)
    else -> finish
}
