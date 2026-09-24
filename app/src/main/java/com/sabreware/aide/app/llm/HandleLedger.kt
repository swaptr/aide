package com.sabreware.aide.app.llm

/**
 * Every live child handle of ONE loaded native engine (for LiteRT, its `Conversation`s), so none can outlive
 * it. Generic because the rule is not LiteRT's: any engine whose sessions point into its memory needs it.
 *
 * A conversation points into its engine's native memory: freeing it after the engine is a use-after-free,
 * and so is opening one on an engine being freed. Frees come from several places on different threads (a
 * session's reset or close, queued behind its last turn; the engine's own close), and without one owner
 * their order was a race: a session's queued free could land after the engine's. The ledger makes the rules
 * mechanical:
 *  - [open] refuses once the engine is closing, so no conversation is born on a freed engine.
 *  - [free] closes a conversation at most once; after [closeAll] it is a no-op, because the ledger already
 *    closed it in the right order.
 *  - [closeAll] closes the remaining conversations, then runs the engine's own free, as one step.
 *
 * Callers run [free] and [closeAll] inside [NativeTurnGate.drain], so no decode is running on what they free.
 */
internal class HandleLedger<T : AutoCloseable> {

    private val lock = Any()
    private val live = LinkedHashSet<T>()
    private var closed = false

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Opens a conversation on a live engine. Throws once the engine is closing. */
    fun open(create: () -> T): T = synchronized(lock) {
        check(!closed) { "Engine closed" }
        create().also(live::add)
    }

    fun free(conversation: T) = synchronized(lock) {
        if (live.remove(conversation)) runCatching { conversation.close() }
    }

    /** Conversations first, then [freeEngine]. Idempotent. */
    fun closeAll(freeEngine: () -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        live.forEach { runCatching { it.close() } }
        live.clear()
        freeEngine()
    }
}
