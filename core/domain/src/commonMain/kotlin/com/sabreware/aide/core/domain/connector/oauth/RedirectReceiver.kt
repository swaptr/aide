package com.sabreware.aide.core.domain.connector.oauth

/** What the browser handed back at the redirect URI. */
data class RedirectResult(val code: String?, val state: String?, val error: String?)

/**
 * Catches the OAuth redirect for one authorization flow. Two impls, chosen by the user's
 * [com.sabreware.aide.core.domain.prefs.OAuthRedirectStrategy]: a loopback `ServerSocket` on `127.0.0.1`, or the
 * custom-scheme `OAuthCallbackActivity` feeding a `PendingOAuthFlow`.
 */
interface RedirectReceiver {
    /** The exact `redirect_uri` to use in the authorization + token requests (and to register via DCR). */
    val redirectUri: String

    /** Suspends until the browser redirects back, then returns the captured code/state (or error). */
    suspend fun await(): RedirectResult

    /** Tears down the receiver (closes the socket / clears the pending flow). */
    fun cancel()
}

/** Creates a fresh [RedirectReceiver] per flow, per the current redirect-strategy preference. */
interface RedirectReceiverFactory {
    suspend fun create(): RedirectReceiver
}
