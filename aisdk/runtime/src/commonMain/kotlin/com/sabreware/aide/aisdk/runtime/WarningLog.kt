package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.Warning

/**
 * Where a round's warnings go once the run has finished with them.
 *
 * A warning is already on the [Step] and on [RunResult.warnings]; this is the reference's `logWarnings`,
 * the side channel for the caller who never reads the result — which in practice is every caller until
 * the first `topK` that silently did nothing. The default writes to standard output, as the reference
 * writes to the console; a host with a logger of its own passes one, and [None] is for the test or the
 * CLI that would rather read the result.
 *
 * A pluggable value rather than the reference's process-wide global, for the same reason
 * [com.sabreware.aide.aisdk.runtime.telemetry.TelemetryRegistry] is one: two runs in one process can
 * report to two places, and nothing leaks between tests.
 */
public fun interface WarningLogger {

    /**
     * @param warnings never empty — a round with nothing to say does not reach the logger.
     * @param provider the provider the round called, for the log line; null where no model was involved.
     * @param modelId the model the round called, likewise.
     */
    public fun log(warnings: List<Warning>, provider: String?, modelId: String?)

    public companion object {

        /** Writes one formatted line per warning to standard output — the reference's default. */
        public val Console: WarningLogger = WarningLogger { warnings, provider, modelId ->
            for (warning in warnings) println(formatWarning(warning, provider, modelId))
        }

        /** Discards everything. */
        public val None: WarningLogger = WarningLogger { _, _, _ -> }
    }
}

/**
 * One warning as a log line, in the reference's wording, so a line read out of a Kotlin host and one
 * read out of a Node host say the same thing about the same call.
 */
public fun formatWarning(warning: Warning, provider: String? = null, modelId: String? = null): String {
    val scope = if (provider != null && modelId != null) " ($provider / $modelId)" else ""
    val prefix = "AI SDK Warning$scope:"
    return when (warning) {
        is Warning.Unsupported ->
            "$prefix The feature \"${warning.feature}\" is not supported." + warning.details.asSuffix()
        is Warning.Compatibility ->
            "$prefix The feature \"${warning.feature}\" is used in a compatibility mode." + warning.details.asSuffix()
        is Warning.Deprecated -> "$prefix Deprecated: \"${warning.setting}\". ${warning.message}"
        is Warning.Other -> "$prefix ${warning.message}"
    }
}

private fun String?.asSuffix(): String = if (isNullOrEmpty()) "" else " $this"

/** Hands [warnings] to the logger unless there are none — the guard the reference keeps in `logWarnings`. */
internal fun WarningLogger.logIfAny(warnings: List<Warning>, provider: String?, modelId: String?) {
    if (warnings.isNotEmpty()) log(warnings, provider, modelId)
}
