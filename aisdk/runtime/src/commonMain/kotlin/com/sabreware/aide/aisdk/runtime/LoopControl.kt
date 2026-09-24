package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeout

/**
 * How long each part of a run may take before it is abandoned.
 *
 * Every field is null by default, and null means no limit — a run that has never hung should not start
 * failing because a default guessed too low. But a limit somewhere is the difference between a slow
 * answer and a coroutine that never completes: a provider can accept a stream, send nothing, and hold
 * the connection open indefinitely, and a tool can await something that never arrives.
 *
 * [firstChunkMs] and [chunkMs] are the ones that catch a silent provider, because they measure the gap
 * between events rather than the total, so a legitimately long answer is not cut off for being long.
 */
public data class RunTimeouts(
    /** The whole run, every round and every tool. */
    val totalMs: Long? = null,
    /** One round: the model call plus the tools it triggered. */
    val stepMs: Long? = null,
    /** From issuing the request to the first stream event. Streaming only. */
    val firstChunkMs: Long? = null,
    /** Between two consecutive stream events. Streaming only. */
    val chunkMs: Long? = null,
    /** One tool execution, for every tool [tools] does not name. */
    val toolMs: Long? = null,
    /**
     * Per-tool limits, by tool name, overriding [toolMs] for that tool.
     *
     * A run's tools do not share one budget: a calculator that takes a second is broken and a browser
     * that takes a minute is working. The reference spells the key as `{name}Ms`; a map keyed by the
     * name itself says the same thing without a string convention.
     */
    val tools: Map<String, Long> = emptyMap(),
) {

    /** The limit for one execution of [toolName] — its own entry in [tools], else [toolMs]. */
    public fun toolTimeoutMs(toolName: String): Long? = tools[toolName] ?: toolMs
}

/** [withTimeout], for a limit that may not exist. */
internal suspend fun <T> withOptionalTimeout(millis: Long?, block: suspend () -> T): T =
    if (millis == null) block() else withTimeout(millis) { block() }

/**
 * Fails a stream that goes quiet.
 *
 * Two limits rather than one because the first gap is a different event from the rest: a model that
 * thinks for forty seconds before its first token is normal, and a forty-second hole in the middle of a
 * reply is a dead connection.
 *
 * The gap is measured rather than the total, so a legitimately long answer is never cut off for being
 * long — which is what a single overall deadline gets wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.withChunkTimeouts(firstMs: Long?, betweenMs: Long?): Flow<T> {
    if (firstMs == null && betweenMs == null) return this
    return flow {
        coroutineScope {
            val events = this@withChunkTimeouts.produceIn(this)
            var limit = firstMs
            while (true) {
                val received = withOptionalTimeout(limit) { events.receiveCatching() }
                received.exceptionOrNull()?.let { throw it }
                emit(received.getOrNull() ?: break)
                limit = betweenMs
            }
        }
    }
}

/** Where a run is, when [PrepareStep] is asked what to do next. */
public data class StepContext(
    /** The round about to run, counted from zero. */
    val stepIndex: Int,
    /** Every round so far, in order. Empty on the first. */
    val steps: List<Step>,
    /** The prompt as it now stands, with every appended assistant and tool turn. */
    val prompt: Prompt,
)

/**
 * Per-round overrides. Null leaves the run's own setting alone.
 *
 * The two that pay for this whole mechanism are [model] — a cheap model for the tool rounds and a strong
 * one for the answer — and [activeTools], which narrows the surface the model can reach once the run has
 * established what it is doing.
 */
public data class StepPlan(
    /** The model this round calls instead of the run's own. */
    val model: LanguageModel? = null,
    /** This round's tool-choice mode — force a tool, forbid them, or leave it to the model. */
    val toolChoice: ToolChoice? = null,
    /**
     * The only tool names offered this round. Null offers all of them.
     *
     * A name here that matches no tool is silently absent rather than an error: the point is to narrow,
     * and a caller narrowing to a tool that is not on this run's list wanted it gone either way.
     */
    val activeTools: List<String>? = null,
    /**
     * The order tools are sent in, this round — overriding the run's own `toolOrder`.
     *
     * It matters for a reason that has nothing to do with the model's behaviour: vendors cache on a
     * prefix of the serialized request, and a tool list whose order varies run to run misses that cache
     * every time. Names not listed follow the listed ones, ALPHABETICALLY — the reference's rule, and the
     * one that makes the order a function of the tool set alone rather than of whoever assembled it.
     */
    val toolOrder: List<String>? = null,
    /** This round's sampling temperature. */
    val temperature: Double? = null,
    /** This round's generation cap. */
    val maxOutputTokens: Int? = null,
    /**
     * Replaces the leading system turn from this round ON, for the rest of the run.
     *
     * Carries forward rather than applying once because an instruction change mid-run — "you have the
     * answer, now write it up" — describes the run's new phase, not one round of it; a caller that
     * wants it back afterwards sets it back.
     */
    val instructions: String? = null,
    /**
     * Replaces the WHOLE conversation from this round on — the compaction hook.
     *
     * The list replaces everything: the run's original prompt and every turn the loop has appended
     * since. [pruneMessages] over [StepContext.prompt] is the intended producer, and the override
     * carries forward — later rounds append to the replacement, not to the history it replaced.
     *
     * The replacement is re-validated exactly like a caller's prompt, because it IS one: a prune that
     * drops half of a call/result pair should fail here, at the round that made it, rather than as the
     * vendor's 400 naming neither the message nor the id.
     */
    val messages: Prompt? = null,
)

/** Chooses what changes about the next round, given how the run has gone so far. */
public fun interface PrepareStep {

    /** @return the overrides for the round [context] describes, or null to change nothing. */
    public suspend fun prepare(context: StepContext): StepPlan?
}

/**
 * Applies this plan to the round's options; a null plan changes nothing but the tool order.
 *
 * [runToolOrder] is the run-level order, which the plan's own [StepPlan.toolOrder] outranks for its
 * round — the reference's `prepareStepResult?.toolOrder ?? toolOrder`.
 */
internal fun StepPlan?.applyTo(options: CallOptions, runToolOrder: List<String>? = null): CallOptions {
    val tools = options.tools?.narrow(this?.activeTools)?.reorder(this?.toolOrder ?: runToolOrder)
    if (this == null) return options.copy(tools = tools)
    return options.copy(
        tools = tools,
        toolChoice = toolChoice ?: options.toolChoice,
        temperature = temperature ?: options.temperature,
        maxOutputTokens = maxOutputTokens ?: options.maxOutputTokens,
    )
}

private fun List<Tool>.narrow(active: List<String>?): List<Tool> =
    if (active == null) this else filter { it.name in active }

/**
 * Listed names first, in listed order; everything else after, alphabetically.
 *
 * A null order leaves the caller's own order alone — the reference does the same — because a caller who
 * said nothing about order may have chosen one, and sorting it away would be a change nobody asked for.
 */
private fun List<Tool>.reorder(order: List<String>?): List<Tool> {
    if (order == null) return this
    val rank = order.withIndex().associate { (index, name) -> name to index }
    val (listed, unlisted) = partition { it.name in rank }
    return listed.sortedBy { rank.getValue(it.name) } + unlisted.sortedBy { it.name }
}
