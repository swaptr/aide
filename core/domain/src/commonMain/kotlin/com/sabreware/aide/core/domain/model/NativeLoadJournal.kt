package com.sabreware.aide.core.domain.model

/**
 * A durable note of which model was being loaded into a native engine when the process last died.
 *
 * Sherpa (ONNX via JNI) and LiteRT both execute inside the app process. A segfault in either is not an
 * exception — it is the process ending, with no stack, no `catch`, and nothing in the log that names the
 * cause. The user sees the app vanish, reopens it, picks the same model, and it vanishes again.
 *
 * Ollama solves this by running inference in child processes, so a crashing runner cannot take the service
 * with it. That is the right answer for a daemon on a workstation and the wrong one for a mobile app, where
 * a second process per model is a memory budget and a lifecycle problem. What transfers is the diagnosis,
 * not the isolation: write down what is about to be attempted, clear it when the attempt returns, and a
 * marker still present at next launch names exactly what killed the process.
 *
 * Backed by the preference store rather than memory — the whole value is surviving a death that runs no
 * `finally` block.
 */
interface NativeLoadJournal {

    /** Record that [key] is about to enter native code. Overwrites any previous entry. */
    suspend fun begin(key: String)

    /** The attempt returned — successfully or with an ordinary exception. Clears the entry. */
    suspend fun finish()

    /**
     * The entry that survived a process death, or null if the last attempt completed. Reading does NOT
     * clear it; call [acknowledge] once the answer has been acted on, so a crash is not re-reported on
     * every launch and is not lost if the app dies again before it can be used.
     */
    suspend fun crashedKey(): String?

    /** Forget the surviving entry, after acting on it. */
    suspend fun acknowledge()
}

/**
 * Runs [block] with [key] journalled, so a native crash inside it is attributable at next launch.
 *
 * The entry is cleared on ANY normal return, exception included: an exception means the process survived,
 * which is precisely the case this is not about.
 */
suspend fun <T> NativeLoadJournal.around(key: String, block: suspend () -> T): T {
    begin(key)
    try {
        return block()
    } finally {
        finish()
    }
}
