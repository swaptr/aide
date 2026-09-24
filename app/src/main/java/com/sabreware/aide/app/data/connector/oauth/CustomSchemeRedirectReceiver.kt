package com.sabreware.aide.app.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.RedirectReceiver
import com.sabreware.aide.core.domain.connector.oauth.RedirectResult
import com.sabreware.aide.data.connector.oauth.PendingOAuthFlow

/**
 * Custom-scheme redirect: arms the gate at construction (before the browser opens) and awaits the
 * `com.sabreware.aide://oauth-callback?…` URI delivered to
 * [OAuthCallbackActivity][com.sabreware.aide.platform.android.intent.OAuthCallbackActivity] via
 * [PendingOAuthFlow.deliverFromCallback]. If the flow is abandoned before `await()` (e.g. client
 * registration fails), [cancel] resolves the armed gate so it isn't leaked.
 */
class CustomSchemeRedirectReceiver : RedirectReceiver {

    override val redirectUri: String = "com.sabreware.aide://oauth-callback"

    private val armed = PendingOAuthFlow.arm(onCancel = {})

    override suspend fun await(): RedirectResult = armed.deferred.await()

    override fun cancel() {
        PendingOAuthFlow.cancelIfPending()
    }
}
