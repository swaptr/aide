package com.sabreware.aide.core.domain.error

/**
 * A failure as a person should meet it: WHAT went wrong in a few words, what to DO about it, and the raw
 * technical message kept for whoever wants it (behind "Details", copyable) — never dumped in the list.
 *
 * The raw text of a network failure is written for developers ("Failed to connect to localhost/127.0.0.1:11434
 * (port 11434) from /10.0.2.16 (port 51432) after 10000ms: isConnected failed: ECONNREFUSED"). Every surface
 * that reports one reads it through [UserError.from], so the same failure reads the same everywhere.
 */
data class UserError(
    val kind: Kind,
    /** One short line: "Can't reach localhost:11434". */
    val title: String,
    /** What to try, or null when there is nothing useful to say. */
    val hint: String?,
    /** The original message, for Details and for bug reports. */
    val detail: String,
) {
    enum class Kind(val label: String) {
        Offline("Offline"),
        Unreachable("Unreachable"),
        Timeout("Timed out"),
        Auth("Key rejected"),
        Forbidden("Not allowed"),
        NotFound("Not found"),
        RateLimited("Rate limited"),
        Server("Server error"),
        Unknown("Failed"),
    }

    companion object {
        /**
         * Classifies [message] (an exception's, or a vendor's error body) into a [UserError]. [host] names the
         * thing that failed ("localhost:11434", "OpenRouter") so the title can say which. Matching is on the
         * message text, which is what survives every wire and platform (Ktor on Android and desktop alike).
         */
        fun from(message: String?, host: String? = null): UserError {
            val raw = message?.trim().orEmpty().ifEmpty { "Unknown error" }
            val text = raw.lowercase()
            val status = STATUS.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val where = host?.takeIf { it.isNotBlank() }
            fun of(kind: Kind, title: String, hint: String?) = UserError(kind, title, hint, raw)
            return when {
                OFFLINE.any { it in text } -> of(Kind.Offline, "No internet connection", "Check your network and try again.")
                UNREACHABLE.any { it in text } ->
                    of(Kind.Unreachable, where?.let { "Can't reach $it" } ?: "Can't reach the server", "Check the address and that the server is running.")
                TIMEOUT.any { it in text } -> of(Kind.Timeout, where?.let { "$it took too long" } ?: "The request timed out", "Try again in a moment.")
                status == 401 || AUTH.any { it in text } -> of(Kind.Auth, "The API key was rejected", "Check the key, or create a new one.")
                status == 403 -> of(Kind.Forbidden, "This key isn't allowed to do that", "Check the key's permissions or plan.")
                status == 404 -> of(Kind.NotFound, where?.let { "$it has no such endpoint" } ?: "Not found", "Check the base URL.")
                status == 429 || RATE.any { it in text } -> of(Kind.RateLimited, "Too many requests", "Wait a moment, or check your plan's limits.")
                status != null && status >= 500 -> of(Kind.Server, where?.let { "$it had a problem" } ?: "The server had a problem", "Try again later.")
                else -> of(Kind.Unknown, raw.lineSequence().first().take(SHORT_MAX).let { if (it.length < raw.length) "$it…" else it }, null)
            }
        }

        private const val SHORT_MAX = 80
        private val STATUS = Regex("""\b(?:status|http|code)?[ :=]*([45]\d\d)\b""")
        private val OFFLINE = listOf("unable to resolve host", "unknownhost", "no address associated", "network is unreachable", "enetunreach")
        private val UNREACHABLE = listOf("failed to connect", "connection refused", "econnrefused", "connectexception", "no route to host", "connection reset")
        private val TIMEOUT = listOf("timed out", "timeout", "sockettimeout")
        private val AUTH = listOf("unauthorized", "invalid api key", "incorrect api key", "invalid_api_key", "authentication", "api key not valid", "invalid x-api-key")
        private val RATE = listOf("rate limit", "rate_limit", "too many requests", "quota")
    }
}
