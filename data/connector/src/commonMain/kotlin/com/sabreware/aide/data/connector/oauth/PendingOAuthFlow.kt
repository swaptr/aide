package com.sabreware.aide.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.RedirectResult
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred

/**
 * Single in-flight OAuth redirect gate, shared by both redirect strategies (loopback socket +
 * custom-scheme [OAuthCallbackActivity][com.sabreware.aide.platform.android.intent.OAuthCallbackActivity]). Process-singleton
 * (no Hilt) so the Activity can reach it.
 *
 * Correctness hinges on the [Phase] + flow [token]:
 *  - [arm] mints a token and returns the deferred to await; superseding a prior flow cancels it.
 *  - The receiver calls [markReceiving] the instant a redirect starts arriving (loopback: socket accepted;
 *    scheme: callback Activity). From then on a racing [cancelIfPending] (fired on the host's ON_RESUME) is
 *    a no-op — this is what prevents a *successful* sign-in from being false-cancelled when the user returns
 *    to the app while the token exchange is still running.
 *  - [deliver] is token-checked so a stale receiver from a superseded flow can't complete the new flow.
 */
object PendingOAuthFlow {

    const val CANCELLED = "cancelled"

    private enum class Phase { AWAITING, RECEIVING, DONE }

    data class Armed(val token: Long, val deferred: CompletableDeferred<RedirectResult>)

    private val lock = SynchronizedObject()
    private var lastToken: Long = 0
    private var current: Long = -1
    private var phase: Phase = Phase.DONE
    private var deferred: CompletableDeferred<RedirectResult>? = null
    private var onCancel: (() -> Unit)? = null

    fun arm(onCancel: () -> Unit): Armed = synchronized(lock) {
        // Supersede any prior in-flight flow (+ tear down its socket) before starting a new one.
        this.onCancel?.invoke()
        deferred?.complete(RedirectResult(null, null, CANCELLED))
        val token = ++lastToken
        current = token
        phase = Phase.AWAITING
        this.onCancel = onCancel
        Armed(token, CompletableDeferred<RedirectResult>().also { deferred = it })
    }

    /** A redirect is now arriving for [flowToken] — stop allowing cancellation so a racing resume can't drop it. */
    fun markReceiving(flowToken: Long): Unit = synchronized(lock) {
        if (flowToken == current && phase == Phase.AWAITING) phase = Phase.RECEIVING
    }

    /** Loopback delivery (token-checked: a stale superseded socket can't complete the current flow). */
    fun deliver(flowToken: Long, result: RedirectResult): Unit = synchronized(lock) {
        if (flowToken == current) complete(result)
    }

    /** Custom-scheme delivery from the callback Activity, which can't know the token (one flow in flight). */
    fun deliverFromCallback(result: RedirectResult): Unit = synchronized(lock) {
        if (phase != Phase.DONE) complete(result)
    }

    /** User returned to the app without finishing sign-in → cancel, but only while still purely awaiting. */
    fun cancelIfPending(): Unit = synchronized(lock) {
        if (phase == Phase.AWAITING) {
            onCancel?.invoke()
            complete(RedirectResult(null, null, CANCELLED))
        }
    }

    private fun complete(result: RedirectResult) {
        phase = Phase.DONE
        deferred?.complete(result)
        deferred = null
        onCancel = null
    }
}
