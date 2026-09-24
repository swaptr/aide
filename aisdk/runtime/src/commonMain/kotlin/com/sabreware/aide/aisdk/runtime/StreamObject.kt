package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.NoObjectGeneratedError
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.TypeValidationContext
import com.sabreware.aide.aisdk.TypeValidationError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flow

/**
 * Notified when a structured stream fails, instead of the failure being thrown.
 *
 * Exists because the two audiences want opposite things from the same event. A caller awaiting one
 * object wants an exception. A UI streaming partials into a view wants to KEEP what it has rendered and
 * be told the rest is not coming — and an exception unwinding through the collector takes the partials
 * with it. Absent a handler the failure still throws, so nothing changes for callers that never asked.
 */
public fun interface StreamObjectOnError {

    /** @param error what went wrong: a provider error mid-stream, or a final object that would not fit. */
    public suspend fun onError(error: Throwable)
}

/** What a structured stream emits. */
public sealed interface ObjectStreamEvent<out T> {

    /** The raw text, forwarded as it arrives, for a caller that wants to show the JSON itself. */
    public data class TextDelta(val delta: String) : ObjectStreamEvent<Nothing>

    /**
     * The object as far as it has been written.
     *
     * Emitted only when the reading CHANGED, so a caller can render each one without diffing: a delta
     * that lands mid-key or mid-number moves the text and not the object, and re-emitting an identical
     * value would make every consumer recompute a view that did not move.
     */
    public data class Partial<T>(val value: T) : ObjectStreamEvent<T>

    /** The validated object. Exactly one, last, unless the run failed. */
    public data class Finish<T>(val result: ObjectResult<T>) : ObjectStreamEvent<T>
}

/**
 * Reads a structured answer out of a run that is already happening.
 *
 * This is what makes structured output reachable from a TOOL-CALLING run rather than only from a
 * dedicated call: the loop is unchanged, and the object is read off the text the model was going to emit
 * anyway. A separate structured entry point cannot do that, because a run that stops to call a tool has
 * no single response to parse.
 *
 * Text is accumulated across every round, since a model that answers, calls a tool, and then answers
 * again has written its object across two of them.
 *
 * ```kotlin
 * streamText(model, prompt, options = options, toolExecutor = executor, stopWhen = stepCountIs(5))
 *     .objectEvents(ObjectOutput.Object(schema))
 *     .collect { event -> if (event is ObjectStreamEvent.Partial) render(event.value) }
 * ```
 *
 * @param output the shape being read out of the run's text — see [ObjectOutput].
 * @param repairText one chance to fix a final text that would not parse or validate — see [RepairText].
 * @param onError notified of a failure instead of it being thrown — see [StreamObjectOnError].
 */
public fun <T> Flow<RunEvent>.objectEvents(
    output: ObjectOutput<T>,
    repairText: RepairText? = null,
    onError: StreamObjectOnError? = null,
): Flow<ObjectStreamEvent<T>> = flow {
    val text = StringBuilder()
    var lastPartial: Any? = NOTHING_YET

    collect { event ->
        when (event) {
            // A provider error mid-stream is reported and then survived: the text so far may still
            // hold a complete object, and ending here would discard an answer that arrived before
            // the connection did not.
            is RunEvent.Error -> onError?.onError(event.error) ?: throw event.error

            is RunEvent.Part -> {
                val delta = (event.part as? StreamPart.TextDelta)?.delta ?: return@collect
                text.append(delta)
                emit(ObjectStreamEvent.TextDelta(delta))
                val partial = parsePartialJson(text.toString()).value?.let { output.partial(it) }
                if (partial != null && partial != lastPartial) {
                    lastPartial = partial
                    emit(ObjectStreamEvent.Partial(partial))
                }
            }

            is RunEvent.Finish -> {
                val settled = runCatching {
                    finalObject(text.toString(), output, event.result, repairText)
                }
                settled.getOrNull()?.let { emit(ObjectStreamEvent.Finish(it)) }
                    ?: settled.exceptionOrNull()?.let { failure ->
                        // Without a handler the failure is thrown, which is the behaviour every
                        // existing caller relies on; with one it is reported and the stream ends
                        // quietly, so a UI can keep the partials it already rendered.
                        onError?.onError(failure) ?: throw failure
                    }
            }

            else -> Unit
        }
    }
}

/**
 * Asks the model for [output] and streams it as it is written.
 *
 * Defaults to a single round, as [streamText] does: a structured call is asking one question, and a
 * caller that wants tools in the middle of it wants [streamText] with [objectEvents] instead.
 *
 * ```kotlin
 * streamObject(
 *     model = model,
 *     prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Three rhyming color names")))),
 *     output = ObjectOutput.Array(itemSchema),
 * ).collect { event ->
 *     if (event is ObjectStreamEvent.Partial) render(event.value)
 * }
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
 * @param repairText one chance to fix a final text that would not parse or validate — see [RepairText].
 * @param onError notified of a failure instead of it being thrown — see [StreamObjectOnError].
 * @param mode how the schema reaches the model — see [StructuredMode].
 */
@Suppress("LongParameterList")
public fun <T> streamObject(
    model: LanguageModel,
    prompt: Prompt,
    output: ObjectOutput<T>,
    options: CallOptions = CallOptions(prompt = prompt),
    schemaName: String? = null,
    schemaDescription: String? = null,
    injectSchemaIntoPrompt: Boolean = false,
    repairText: RepairText? = null,
    onError: StreamObjectOnError? = null,
    mode: StructuredMode = StructuredMode.ResponseFormat,
): Flow<ObjectStreamEvent<T>> {
    val instructed = if (injectSchemaIntoPrompt) prompt.withSchemaInstruction(output.requestSchema) else prompt
    return streamText(
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
            StructuredMode.Tool -> options.copy(
                responseFormat = null,
                tools = listOf(output.asForcedTool(schemaName, schemaDescription)),
                toolChoice = ToolChoice.Specific(schemaName ?: STRUCTURED_TOOL_NAME),
            )
        },
    ).let { events ->
        when (mode) {
            StructuredMode.ResponseFormat -> events.objectEvents(output, repairText, onError)
            // The answer arrives as tool-input deltas rather than text, so the reader is fed those.
            StructuredMode.Tool ->
                events.withoutDeclinedToolChoice().toolInputAsText().objectEvents(output, repairText, onError)
        }
    }
}

/**
 * Drops the error a round reports for answering in prose instead of calling the forced tool.
 *
 * The prose already reached the reader as text deltas and is read at the finish exactly as
 * `generateObject` reads it from the violation's content — see [ToolChoiceViolationError]. Every other
 * error passes, to [StreamObjectOnError] or the collector.
 */
private fun Flow<RunEvent>.withoutDeclinedToolChoice(): Flow<RunEvent> =
    filterNot { it is RunEvent.Error && it.error is ToolChoiceViolationError }

/**
 * Re-labels a forced tool call's input deltas as text, so one reader serves both modes.
 *
 * [StructuredMode.Tool] puts the answer in the call's arguments, which stream as `tool-input` parts.
 * Rewriting them into text deltas here means [objectEvents] does not need a second parsing path — and
 * a second path is exactly where the partial-object handling would drift between the two modes.
 */
private fun Flow<RunEvent>.toolInputAsText(): Flow<RunEvent> = flow {
    // Which calls already arrived as deltas. A provider that streams input ALSO emits the assembled
    // ToolCallPart at the end, and relabelling both would feed the reader the answer twice — text that
    // then parses only by the truncation-salvage path, if at all.
    val streamed = mutableSetOf<String>()
    collect { event ->
        val part = (event as? RunEvent.Part)?.part
        when {
            part is StreamPart.ToolInputDelta -> {
                streamed += part.id
                emit(RunEvent.Part(StreamPart.TextDelta(part.id, part.delta), event.stepIndex))
            }
            // Only for a provider that never streamed the input: then the whole call IS the answer.
            part is StreamPart.ToolCallPart && part.toolCall.toolCallId !in streamed ->
                emit(RunEvent.Part(StreamPart.TextDelta(part.toolCall.toolCallId, part.toolCall.input), event.stepIndex))
            part is StreamPart.ToolCallPart -> Unit
            else -> emit(event)
        }
    }
}

/**
 * The validated result, or the failure carrying what the run cost.
 *
 * A stream that was cut off has a repaired parse available where a non-streaming call would have
 * nothing, so the last resort here is not "no object" but "the object as far as it got" — tried only
 * after the whole text has failed, so a complete answer is never quietly replaced by a truncated
 * reading of itself.
 */
private suspend fun <T> finalObject(
    text: String,
    output: ObjectOutput<T>,
    result: RunResult,
    repairText: RepairText?,
): ObjectResult<T> {
    val located = extractJson(text)
    var mismatch: Throwable? = null
    if (located != null) {
        runCatching { output.validate(located) }
            .onSuccess { return ObjectResult(it, located, result.usage, result.steps) }
            .onFailure { mismatch = it }
    }

    // The strategy's own error names the field it rejected; it rides along as the cause so that is not
    // lost under the wider "did not fit".
    val cause = TypeValidationError(
        located,
        "The response did not fit ${output::class.simpleName}.",
        mismatch,
        context = TypeValidationContext(field = RESPONSE_TEXT_FIELD, entityName = output::class.simpleName),
    )
    repairText?.repair(text, cause)?.let { repaired ->
        extractJson(repaired)?.let { json ->
            runCatching { output.validate(json) }
                .onSuccess { return ObjectResult(it, json, result.usage, result.steps) }
        }
    }

    val partial = parsePartialJson(text)
    if (partial.state == PartialJsonState.Repaired) {
        val value = partial.value?.let { runCatching { output.validate(it) }.getOrNull() }
        if (value != null) return ObjectResult(value, partial.value, result.usage, result.steps)
    }

    throw NoObjectGeneratedError(
        message = "No object generated. The model returned: ${text.take(STREAM_ERROR_EXCERPT)}",
        text = text,
        usage = result.usage,
        finishReason = result.finishReason,
        response = result.response?.metadata,
        cause = cause,
    )
}

/**
 * Complete array elements, each emitted once, as it finishes streaming.
 *
 * For a list-shaped run — [ObjectOutput.Array], or any strategy whose value is a list — this is the
 * consumer-side complement of the held-back last element: a partial list only ever grows by elements
 * that are DONE, so each new index is emitted exactly once and never revised. The element the strategy
 * held back while it was still being written arrives with the finish, off the validated final list.
 *
 * A caller rendering a table appends rows from this flow; one that re-rendered every
 * [ObjectStreamEvent.Partial] would rebuild the whole table per delta to display the same rows.
 *
 * ```kotlin
 * streamObject(model, prompt, output = ObjectOutput.Array(itemSchema))
 *     .elementStream()
 *     .collect { element -> appendRow(element) }
 * ```
 */
public fun <T> Flow<ObjectStreamEvent<List<T>>>.elementStream(): Flow<T> = flow {
    var published = 0
    collect { event ->
        val elements = when (event) {
            is ObjectStreamEvent.Partial -> event.value
            is ObjectStreamEvent.Finish -> event.result.value
            is ObjectStreamEvent.TextDelta -> return@collect
        }
        // A partial can only grow — the strategy drops the half-written tail — so anything past the
        // published count is new and settled. Guarding with the max keeps a misbehaving strategy from
        // re-emitting rows rather than corrupting the caller's table.
        for (index in published until elements.size) emit(elements[index])
        published = maxOf(published, elements.size)
    }
}

/** Distinguishes "no partial yet" from a strategy whose first partial is legitimately null. */
private val NOTHING_YET = Any()

private const val STREAM_ERROR_EXCERPT = 200
