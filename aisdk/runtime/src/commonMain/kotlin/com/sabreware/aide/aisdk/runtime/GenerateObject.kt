package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.NoObjectGeneratedError
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.TypeValidationContext
import com.sabreware.aide.aisdk.TypeValidationError
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.util.jsonSchemaInstruction
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A structured result, with the raw JSON kept alongside the decoded value. */
public data class ObjectResult<T>(
    val value: T,
    /** What the model actually returned, before decoding. Kept for the bug report when decoding fails. */
    val raw: JsonElement,
    val usage: Usage,
    val steps: List<Step>,
)

/**
 * How the schema is put to the model.
 *
 * **An ours-extra, deliberately.** The reference carried a `mode: 'tool'` setting and removed it in v7
 * ("remove unused mode setting from generateObject and streamObject"), so every reference output now
 * goes out as a response format. That is right for the vendors it targets and wrong for the ones this
 * library also has to serve: an OpenAI-compatible server that predates `response_format`, or a local
 * runner that rejects the field outright, cannot do structured output at all through the response-format
 * path — and a forced tool call is the shape those servers DO understand.
 *
 * This is not a guessed wire. Both arms are built from primitives the specification already models
 * ([ResponseFormat.Json] and a [Tool.Function] with [ToolChoice.Specific]), so nothing here has to be
 * verified against a vendor's private format. A future conformance pass should not delete it to match
 * upstream.
 */
public enum class StructuredMode {

    /**
     * The schema goes out as [ResponseFormat.Json] — constrained decoding where the vendor supports it,
     * and the default because it is the only arm that can make a malformed answer impossible.
     */
    ResponseFormat,

    /**
     * The schema goes out as a single tool the model is forced to call, and the answer is read off the
     * call's arguments.
     *
     * The tool is never executed. Its arguments ARE the answer, so running it would be running the
     * model's own reply.
     */
    Tool,
}

/**
 * Repairs model text that would not parse or would not validate.
 *
 * Fires on both failures, not just the parse, because the two are the same event from the caller's
 * side — the model answered in a shape this call cannot use — and a hook that only sees malformed JSON
 * cannot fix well-formed JSON with the wrong key names, which is the commoner of the two.
 *
 * @return the repaired text, or null to let the failure stand.
 */
public fun interface RepairText {

    public suspend fun repair(text: String, error: Throwable): String?
}

/**
 * Asks the model for JSON matching [output], and returns it unwrapped.
 *
 * The schema goes to the vendor verbatim via [ResponseFormat.Json], so a provider with constrained
 * decoding enforces it at generation time rather than after the fact. Vendors without it get the schema
 * as guidance and may still return something else, which is why the result is validated here rather than
 * trusted.
 *
 * [injectSchemaIntoPrompt] is for the vendors that accept [ResponseFormat.Json] and ignore it. Putting
 * the schema in the system prompt is strictly worse than constrained decoding, so it is opt-in rather
 * than automatic — but it is much better than the alternative that was load-bearing without it, which is
 * [extractJson] fishing an object out of prose. That fallback should be a safety net, not the mechanism.
 *
 * A model that wraps its JSON in a markdown fence is a fact of life rather than an error — see
 * [extractJson]. Failing on it would mean losing a correct answer to a formatting habit.
 *
 * ```kotlin
 * val sentiment = generateOutput(
 *     model = model,
 *     prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Review: loved it, would buy again")))),
 *     output = ObjectOutput.Enum(listOf("positive", "negative", "neutral")),
 * )
 * println(sentiment.value) // "positive"
 * ```
 *
 * @param model the model asked for the answer.
 * @param prompt the conversation; a single user turn for the usual one-question call.
 * @param output the shape being asked for, and how to unwrap it — see [ObjectOutput].
 * @param options every other call setting; its `responseFormat` is overwritten with the schema.
 * @param schemaName a name for the schema, where the vendor's response-format takes one.
 * @param schemaDescription what the schema is for, forwarded the same way.
 * @param injectSchemaIntoPrompt also spell the schema out in the system prompt — for vendors that accept
 *   [ResponseFormat.Json] and ignore it.
 * @param repairText one chance to fix text that would not parse or validate — see [RepairText].
 * @param mode how the schema reaches the model — see [StructuredMode]. The default is the response
 *   format; [StructuredMode.Tool] is for servers that have no such field.
 */
@Suppress("LongParameterList")
public suspend fun <T> generateOutput(
    model: LanguageModel,
    prompt: Prompt,
    output: ObjectOutput<T>,
    options: CallOptions = CallOptions(prompt = prompt),
    schemaName: String? = null,
    schemaDescription: String? = null,
    injectSchemaIntoPrompt: Boolean = false,
    repairText: RepairText? = null,
    mode: StructuredMode = StructuredMode.ResponseFormat,
): ObjectResult<T> {
    val instructed = if (injectSchemaIntoPrompt) prompt.withSchemaInstruction(output.requestSchema) else prompt

    val result = try {
        generateText(
            model = model,
            prompt = instructed,
            options = when (mode) {
                StructuredMode.ResponseFormat -> options.copy(
                    responseFormat = ResponseFormat.Json(
                        schema = output.requestSchema,
                        name = schemaName,
                        description = schemaDescription,
                    ),
                )
                // No executor is passed, so the forced call is never run: its arguments are the answer.
                StructuredMode.Tool -> options.copy(
                    responseFormat = null,
                    tools = listOf(output.asForcedTool(schemaName, schemaDescription)),
                    toolChoice = ToolChoice.Specific(schemaName ?: STRUCTURED_TOOL_NAME),
                )
            },
        )
    } catch (declined: ToolChoiceViolationError) {
        // A model that ignored the forced tool and answered in prose has still often answered correctly,
        // and the violation carries the round's content for exactly this recovery. A choice enforced by
        // anything else — a caller's own `toolChoice` in response-format mode — stays refused.
        if (mode != StructuredMode.Tool) throw declined
        return readDeclinedRound(declined, output, repairText)
    }

    // In tool mode the answer is the call's arguments; in response-format mode it is the text. Both
    // reach the same parse/repair path, so a repair hook does not have to know which mode it is in.
    val text = when (mode) {
        StructuredMode.ResponseFormat -> result.allText
        StructuredMode.Tool -> result.structuredToolInput(schemaName)
    }
    val value = attempt(text, output).getOrElse { cause ->
        val repaired = repairText?.repair(text, cause) ?: throw noObject(text, result, cause)
        attempt(repaired, output).getOrElse { throw noObject(repaired, result, it) }
    }

    return ObjectResult(value.value, value.raw, result.usage, result.steps)
}

/**
 * The object read from a round that answered in prose instead of calling the forced tool.
 *
 * The run threw before it recorded a step, so the one returned is assembled from what the violation
 * carries — the content, the finish reason, the cost — and is the round that was refused, with no run id.
 * Losing a correct answer to a disobedience the caller cannot fix would be the worse outcome.
 */
private suspend fun <T> readDeclinedRound(
    declined: ToolChoiceViolationError,
    output: ObjectOutput<T>,
    repairText: RepairText?,
): ObjectResult<T> {
    val text = declined.content.filterIsInstance<Content.Text>().joinToString("") { it.text }
    fun noObject(text: String, cause: Throwable) = NoObjectGeneratedError(
        message = "No object generated. The model returned: ${text.take(ERROR_EXCERPT)}",
        text = text,
        usage = declined.usage,
        finishReason = declined.finishReason,
        cause = cause,
    )
    val value = attempt(text, output).getOrElse { cause ->
        val repaired = repairText?.repair(text, cause) ?: throw noObject(text, cause)
        attempt(repaired, output).getOrElse { throw noObject(repaired, it) }
    }
    val step = Step(content = declined.content, finishReason = declined.finishReason, usage = declined.usage)
    return ObjectResult(value.value, value.raw, declined.usage, listOf(step))
}

/** The single tool a [StructuredMode.Tool] call offers, carrying the strategy's own request schema. */
internal fun ObjectOutput<*>.asForcedTool(
    schemaName: String?,
    schemaDescription: String?,
): Tool.Function = Tool.Function(
    name = schemaName ?: STRUCTURED_TOOL_NAME,
    inputSchema = requestSchema ?: JsonObject(emptyMap()),
    description = schemaDescription ?: "Respond with the structured answer.",
    // Constrained decoding where the vendor offers it for tools — the same guarantee the response
    // format arm is asking for, requested through the field these servers actually implement.
    strict = true,
)

/**
 * The forced call's arguments, which in tool mode are the answer.
 *
 * Falls back to the run's text when no call was made: a model that ignored a forced tool and answered
 * in prose has still often answered correctly, and discarding that to report "no tool call" would lose
 * a good answer to a disobedience the caller cannot fix.
 */
private fun RunResult.structuredToolInput(schemaName: String?): String {
    val name = schemaName ?: STRUCTURED_TOOL_NAME
    val call = steps.flatMap { it.toolCalls }.firstOrNull { it.toolName == name }
        ?: steps.flatMap { it.toolCalls }.firstOrNull()
    return call?.input ?: allText
}

/**
 * Asks the model for a JSON object matching [schema].
 *
 * ```kotlin
 * val schema = buildJsonObject {
 *     put("type", "object")
 *     putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
 * }
 * val result = generateObjectJson(model, prompt, schema)
 * println(result.value)
 * ```
 *
 * @param model the model asked for the answer.
 * @param prompt the conversation; a single user turn for the usual one-question call.
 * @param schema the JSON Schema the object must fit, sent to the vendor verbatim.
 * @param options every other call setting; its `responseFormat` is overwritten with the schema.
 * @param schemaName a name for the schema, where the vendor's response-format takes one.
 * @param schemaDescription what the schema is for, forwarded the same way.
 * @param injectSchemaIntoPrompt also spell the schema out in the system prompt — for vendors that accept
 *   [ResponseFormat.Json] and ignore it.
 * @param repairText one chance to fix text that would not parse or validate — see [RepairText].
 * @param mode how the schema reaches the model — see [StructuredMode].
 */
@Suppress("LongParameterList")
public suspend fun generateObjectJson(
    model: LanguageModel,
    prompt: Prompt,
    schema: JsonSchema,
    options: CallOptions = CallOptions(prompt = prompt),
    schemaName: String? = null,
    schemaDescription: String? = null,
    injectSchemaIntoPrompt: Boolean = false,
    repairText: RepairText? = null,
    mode: StructuredMode = StructuredMode.ResponseFormat,
): ObjectResult<JsonElement> = generateOutput(
    model, prompt, ObjectOutput.Object(schema), options, schemaName, schemaDescription,
    injectSchemaIntoPrompt, repairText, mode,
)

/**
 * As [generateObjectJson], but decodes into [T] with [deserializer].
 *
 * Kept separate rather than folded in, because a caller that only wants to read a couple of fields
 * should not have to declare a serializable class for the privilege.
 *
 * ```kotlin
 * @Serializable
 * data class City(val name: String, val population: Int)
 *
 * val result = generateObject(model, prompt, schema, City.serializer())
 * println(result.value.name)
 * ```
 *
 * @param model the model asked for the answer.
 * @param prompt the conversation; a single user turn for the usual one-question call.
 * @param schema the JSON Schema the object must fit, sent to the vendor verbatim.
 * @param deserializer decodes the returned JSON into [T]; a decode failure reaches [repairText].
 * @param options every other call setting; its `responseFormat` is overwritten with the schema.
 * @param schemaName a name for the schema, where the vendor's response-format takes one.
 * @param schemaDescription what the schema is for, forwarded the same way.
 * @param injectSchemaIntoPrompt also spell the schema out in the system prompt — for vendors that accept
 *   [ResponseFormat.Json] and ignore it.
 * @param repairText one chance to fix text that would not parse or validate — see [RepairText].
 * @param mode how the schema reaches the model — see [StructuredMode].
 */
@Suppress("LongParameterList")
public suspend fun <T> generateObject(
    model: LanguageModel,
    prompt: Prompt,
    schema: JsonSchema,
    deserializer: DeserializationStrategy<T>,
    options: CallOptions = CallOptions(prompt = prompt),
    schemaName: String? = null,
    schemaDescription: String? = null,
    injectSchemaIntoPrompt: Boolean = false,
    repairText: RepairText? = null,
    mode: StructuredMode = StructuredMode.ResponseFormat,
): ObjectResult<T> = generateOutput(
    model, prompt, ObjectOutput.Decoded(schema, deserializer), options, schemaName, schemaDescription,
    injectSchemaIntoPrompt, repairText, mode,
)

/**
 * Adds the JSON directive to the prompt's system instructions.
 *
 * MERGED into an existing leading system message, caller's prose first, rather than prepended as a
 * second system turn: prepending puts the boilerplate directive ahead of the instructions the caller
 * actually wrote, inverting the emphasis — and is the shape [standardizePrompt] would then have to
 * special-case. Matches the reference's `injectJsonInstructionIntoMessages`.
 */
internal fun Prompt.withSchemaInstruction(schema: JsonSchema?): Prompt {
    val first = firstOrNull()
    return if (first is ModelMessage.System) {
        listOf(first.copy(content = jsonSchemaInstruction(schema, prompt = first.content))) + drop(1)
    } else {
        listOf(ModelMessage.System(jsonSchemaInstruction(schema))) + this
    }
}

/** A parsed and unwrapped response, with the raw JSON kept for the caller. */
private class Validated<T>(val value: T, val raw: JsonElement)

/**
 * One reading of the model's text, with the reason it did not work when it did not.
 *
 * Both failures — no JSON in the text at all, and JSON that does not fit the strategy — come back the
 * same way, because they are the same event to a repair hook: the model answered in a shape this call
 * cannot use. A hook that only saw the malformed-JSON half could not fix well-formed JSON with the wrong
 * key names, which is the commoner of the two.
 */
private fun <T> attempt(text: String, output: ObjectOutput<T>): Result<Validated<T>> {
    val json = extractJson(text)
        ?: return Result.failure(
            TypeValidationError(
                null,
                "Expected JSON, but the model returned: ${text.take(ERROR_EXCERPT)}",
                context = TypeValidationContext(field = RESPONSE_TEXT_FIELD, entityName = output::class.simpleName),
            ),
        )
    return runCatching { Validated(output.validate(json), json) }
}

/**
 * The failure a structured call reports, carrying what the call cost.
 *
 * The text, the usage, the finish reason and the response id all ride along because a structured call
 * that fails has still been paid for, and debugging one whose error carried none of that means running
 * it again to find out what the model said.
 */
private fun noObject(
    text: String,
    result: RunResult,
    cause: Throwable,
): NoObjectGeneratedError = NoObjectGeneratedError(
    message = "No object generated. The model returned: ${text.take(ERROR_EXCERPT)}",
    text = text,
    usage = result.usage,
    finishReason = result.finishReason,
    response = result.response?.metadata,
    cause = cause,
)

/**
 * Pulls a JSON value out of model text.
 *
 * Tries the whole string first, then a fenced block, then the outermost braces. Models that were told to
 * return JSON still narrate around it or wrap it in a fence, and treating that as a failure throws away a
 * correct answer over a formatting habit. Nothing is repaired — only located.
 */
internal fun extractJson(text: String): JsonElement? {
    val trimmed = text.trim()
    parseJsonValueOrNull(trimmed)?.let { return it }

    // ```json … ``` or a bare ``` … ``` fence.
    FENCE.find(trimmed)?.groupValues?.get(1)?.let { fenced ->
        parseJsonValueOrNull(fenced.trim())?.let { return it }
    }

    // Outermost object or array, for a model that narrated around its answer.
    val start = trimmed.indexOfFirst { it == '{' || it == '[' }
    val end = trimmed.indexOfLast { it == '}' || it == ']' }
    if (start in 0..<end) {
        parseJsonValueOrNull(trimmed.substring(start, end + 1))?.let { return it }
    }
    return null
}

/** Convenience for the common case: a JSON object rather than any JSON value. */
public fun ObjectResult<JsonElement>.asObject(): JsonObject =
    value as? JsonObject
        ?: throw TypeValidationError(
            value = value,
            message = "Expected a JSON object, got ${value::class.simpleName}",
            context = TypeValidationContext(field = "result.value"),
        )

private val FENCE = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

private const val ERROR_EXCERPT = 200

/** The field a [TypeValidationContext] names when it is the model's whole reply that did not fit. */
internal const val RESPONSE_TEXT_FIELD = "response.text"

/** The tool name a [StructuredMode.Tool] call uses when the caller named no schema. */
internal const val STRUCTURED_TOOL_NAME: String = "json"
