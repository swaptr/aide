package com.sabreware.aide.desktop.connector

import com.sabreware.aide.core.domain.connector.oauth.RedirectReceiver
import com.sabreware.aide.core.domain.connector.oauth.RedirectReceiverFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Desktop [RedirectReceiverFactory]. Desktop only supports the loopback strategy (there's no custom-scheme
 * `OAuthCallbackActivity` peer), so every flow gets a fresh [DesktopLoopbackRedirectReceiver] — mirroring the
 * per-flow construction of `:app`'s loopback branch (the receiver arms the `PendingOAuthFlow` gate + starts
 * `accept()` in its constructor, so it must be built anew each `create()`).
 */
class DesktopRedirectReceiverFactory(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : RedirectReceiverFactory {

    override suspend fun create(): RedirectReceiver = DesktopLoopbackRedirectReceiver(scope, io)
}
