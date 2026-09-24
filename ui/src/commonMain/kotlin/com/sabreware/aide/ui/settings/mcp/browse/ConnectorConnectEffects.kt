package com.sabreware.aide.ui.settings.mcp.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.ui.platform.LocalPlatformAffordances

/**
 * Shared OAuth connect plumbing for any screen that connects connectors (the catalog screen + the search
 * screen): opens the authorization URL in the system browser (Custom Tab on Android), and — when the user
 * returns to the app without finishing sign-in (dismissed the browser) — cancels the in-flight flow so it
 * doesn't hang.
 */
@Composable
fun ConnectorConnectEffects(viewModel: ConnectorBrowseViewModel) {
    // Connector OAuth needs the host's REAL browser (never a WebView). Without one the flow cannot be
    // completed at all, so the collector is not started — the connect button reports failure rather than
    // opening nothing and hanging on a redirect that will never arrive.
    val openUrl = LocalPlatformAffordances.current.urlOpener?.rememberLauncher()
    DisposableEffect(Unit) {
        AideLog.i("ConnOAuth", "ConnectorConnectEffects MOUNTED (browser collector active)")
        onDispose { AideLog.i("ConnOAuth", "ConnectorConnectEffects DISPOSED (browser collector gone)") }
    }
    if (openUrl != null) {
        LaunchedEffect(Unit) {
            viewModel.launchBrowser.collect { url ->
                AideLog.i("ConnOAuth", "launchBrowser collected url=$url")
                openUrl(url)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onReturnedToForeground() }
}
