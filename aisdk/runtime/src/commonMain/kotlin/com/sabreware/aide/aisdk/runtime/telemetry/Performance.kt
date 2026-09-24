package com.sabreware.aide.aisdk.runtime.telemetry

import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Usage
import kotlin.math.ceil

/**
 * The gaps between a round's output chunks, summarized.
 *
 * Six numbers rather than the list, because the list is a thousand entries long on a real answer and the
 * questions asked of it are always the same three: how smooth was it (median), how bad were the stalls
 * (p90, max), and did the vendor throttle (min, avg).
 */
public data class OutputChunkTiming(
    /** The shortest gap. */
    val minMs: Long,
    /** The 10th percentile gap. */
    val p10Ms: Long,
    /** The median gap. */
    val medianMs: Long,
    /** The mean gap. */
    val avgMs: Double,
    /** The 90th percentile gap — the stall a user notices. */
    val p90Ms: Long,
    /** The longest gap. */
    val maxMs: Long,
)

/**
 * Throughput for one round, derived when it finishes.
 *
 * The two `effective` rates divide by the whole response time and are always available; the other two
 * divide by the split that only a stream reveals — time to the first output chunk, and time after it —
 * and are null for a `generateText` round, which has no chunks to split on.
 */
public data class StepPerformance(
    /** Output tokens over the whole response time. */
    val effectiveOutputTokensPerSecond: Double,
    /** Output tokens over the time AFTER the first output chunk: the generation rate proper. Streaming only. */
    val outputTokensPerSecond: Double?,
    /** Input tokens over the time BEFORE the first output chunk: the prompt-processing rate. Streaming only. */
    val inputTokensPerSecond: Double?,
    /** Input plus output tokens over the whole response time. */
    val effectiveTotalTokensPerSecond: Double,
    /** Gap statistics; null with fewer than two output chunks, because one chunk has no gap. */
    val timeBetweenOutputChunksMs: OutputChunkTiming?,
)

/**
 * A token rate, or zero when it cannot be computed.
 *
 * Zero rather than null or infinity for an unknown count or a zero duration, as the reference: the number
 * goes into dashboards that divide by it and serialize it, and "0 tok/s" reads as "unmeasured" where
 * `Infinity` reads as a bug.
 */
public fun tokensPerSecond(tokens: Int?, durationMs: Long?): Double {
    if (tokens == null || durationMs == null || durationMs == 0L) return 0.0
    val rate = MILLIS_PER_SECOND * tokens / durationMs
    return if (rate.isFinite()) rate else 0.0
}

/** Summarizes [gapsMs]; null when there is nothing to summarize. Nearest-rank percentiles, as the reference. */
public fun outputChunkTiming(gapsMs: List<Long>): OutputChunkTiming? {
    if (gapsMs.isEmpty()) return null
    val sorted = gapsMs.sorted()
    return OutputChunkTiming(
        minMs = sorted.first(),
        p10Ms = sorted.nearestRank(P10),
        medianMs = sorted.nearestRank(MEDIAN),
        avgMs = sorted.sum().toDouble() / sorted.size,
        p90Ms = sorted.nearestRank(P90),
        maxMs = sorted.last(),
    )
}

/** `sorted[ceil(p * n) - 1]` — the nearest-rank method, which never reports a value nobody observed. */
private fun List<Long>.nearestRank(percentile: Double): Long =
    this[(ceil(percentile * size).toInt() - 1).coerceIn(0, lastIndex)]

/**
 * Whether this part is generated OUTPUT, for time-to-first-output and the chunk gaps.
 *
 * The reference's set: a non-empty text, reasoning or tool-input delta, a file, or a complete tool call.
 * Wider than [StepMetrics.timeToFirstTokenMs]'s text-or-reasoning, because a tool-only round DID produce
 * output — the arguments — and its generation rate is as measurable as prose.
 */
internal fun StreamPart.isOutputChunk(): Boolean = when (this) {
    is StreamPart.TextDelta -> delta.isNotEmpty()
    is StreamPart.ReasoningDelta -> delta.isNotEmpty()
    is StreamPart.ToolInputDelta -> delta.isNotEmpty()
    is StreamPart.FilePart, is StreamPart.ReasoningFilePart, is StreamPart.ToolCallPart -> true
    else -> false
}

internal fun stepPerformance(
    usage: Usage,
    responseTimeMs: Long,
    timeToFirstOutputMs: Long?,
    gapsMs: List<Long>,
): StepPerformance = StepPerformance(
    effectiveOutputTokensPerSecond = tokensPerSecond(usage.outputTokens.total, responseTimeMs),
    outputTokensPerSecond = timeToFirstOutputMs?.let { tokensPerSecond(usage.outputTokens.total, responseTimeMs - it) },
    inputTokensPerSecond = timeToFirstOutputMs?.let { tokensPerSecond(usage.inputTokens.total, it) },
    effectiveTotalTokensPerSecond = tokensPerSecond(
        usage.inputTokens.total.plusOrNull(usage.outputTokens.total),
        responseTimeMs,
    ),
    timeBetweenOutputChunksMs = outputChunkTiming(gapsMs),
)

/** Null only when BOTH are unknown — the reference's `sumTokenCounts`. */
private fun Int?.plusOrNull(other: Int?): Int? = when {
    this == null -> other
    other == null -> this
    else -> this + other
}

private const val MILLIS_PER_SECOND = 1_000.0
private const val P10 = 0.1
private const val MEDIAN = 0.5
private const val P90 = 0.9
