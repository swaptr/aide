package com.sabreware.aide.core.domain.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Keeps a native free from landing on top of a running decode. One per native engine: LiteRT chat, the
 * Sherpa recognizer and VAD, and whatever engine comes next. [nativeStream] makes a turn last as long as the
 * native run; this makes a free wait for it.
 *
 * LiteRT's `Engine` and `Conversation` are handles to native memory, and a turn streams from a conversation
 * for as long as the model is generating. Freeing either one underneath a live turn is a SIGSEGV, not an
 * exception — and every path that frees them runs on a different thread from the one decoding:
 * `reset()` and `close()` from the UI, the engine's own `load()` (which closes the previous model first),
 * and the residency manager's keepAlive expiry and memory-trim eviction.
 *
 * So every turn runs inside [turn] and every free inside [drain]. One gate is shared by an engine and every
 * session it creates, because closing the engine has to wait for the conversations hanging off it too.
 *
 * A readers/writer gate rather than a plain mutex: turns are concurrent by nature (the voice loop and chat
 * can both be mid-turn), and only a free needs exclusivity. [drain] is itself serialized so two concurrent
 * frees cannot each hold half the permits and wait forever on the other.
 *
 * The one shape to avoid is calling [drain] from inside [turn]: it waits on a permit it is holding. Hold a turn
 * only around native calls, never around work that can wait on the user or load a model (LiteRT dispatches
 * tools between two holds, the Sherpa engines load before taking theirs).
 */
class NativeTurnGate {

    private val permits = Semaphore(MAX_CONCURRENT_TURNS)
    private val drainLock = Mutex()

    /** Run [block] as an in-flight turn. A [drain] already in progress holds it off until the free lands. */
    suspend fun <T> turn(block: suspend () -> T): T = permits.withPermit { block() }

    /** Wait for every in-flight turn to finish, then run [block] with no new turn able to start. */
    suspend fun <T> drain(block: suspend () -> T): T = drainLock.withLock {
        // Only the permits actually taken are returned: a drain cancelled while waiting behind a live turn used to
        // keep the ones it already held, and every later drain then waited forever.
        var held = 0
        try {
            repeat(MAX_CONCURRENT_TURNS) {
                permits.acquire()
                held++
            }
            block()
        } finally {
            repeat(held) { permits.release() }
        }
    }

    private companion object {
        /**
         * The permit count is the concurrency ceiling for turns on one engine, not a tuning knob: the app
         * runs one chat turn plus at most a voice turn at a time. It is generous so that a future surface
         * does not silently serialize, and small enough that [drain] acquiring all of them stays cheap.
         */
        const val MAX_CONCURRENT_TURNS = 8
    }
}
