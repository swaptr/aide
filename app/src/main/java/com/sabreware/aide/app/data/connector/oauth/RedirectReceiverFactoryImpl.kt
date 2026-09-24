package com.sabreware.aide.app.data.connector.oauth

import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.domain.connector.ConnectorPrefs
import com.sabreware.aide.core.domain.connector.oauth.RedirectReceiver
import com.sabreware.aide.core.domain.connector.oauth.RedirectReceiverFactory
import com.sabreware.aide.core.domain.prefs.OAuthRedirectStrategy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/** Picks the redirect receiver per the user's [OAuthRedirectStrategy] setting (default LOOPBACK). */
class RedirectReceiverFactoryImpl(
    private val prefs: PreferenceStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : RedirectReceiverFactory {

    override suspend fun create(): RedirectReceiver =
        when (prefs.flow(ConnectorPrefs.OAuthRedirect).first()) {
            OAuthRedirectStrategy.LOOPBACK -> LoopbackRedirectReceiver(scope, io)
            OAuthRedirectStrategy.CUSTOM_SCHEME -> CustomSchemeRedirectReceiver()
        }
}
