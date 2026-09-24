package com.sabreware.aide.ui.settings

import androidx.compose.runtime.Composable
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.domain.prefs.OAuthRedirectStrategy

/** Plain name for how sign-in returns to the app — shared by every row that shows the choice and the sheet. */
internal fun redirectStrategyLabel(strategy: OAuthRedirectStrategy): String = when (strategy) {
    OAuthRedirectStrategy.LOOPBACK -> "Standard"
    OAuthRedirectStrategy.CUSTOM_SCHEME -> "App link"
}

/**
 * Picks how a connector's sign-in comes back to the app once the browser is done. Standard (a local address
 * on this device, the default) works with the most services; an app link is simpler but some services
 * refuse it.
 */
@Composable
fun OAuthRedirectStrategySheet(
    selected: OAuthRedirectStrategy,
    onSelect: (OAuthRedirectStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = "Sign-in method",
    ) { _ ->
        AppMenu(
            items = listOf(
                AppMenuEntry(
                    title = redirectStrategyLabel(OAuthRedirectStrategy.LOOPBACK),
                    subtitle = "Works with most services. Recommended.",
                    selected = selected == OAuthRedirectStrategy.LOOPBACK,
                    onClick = { onSelect(OAuthRedirectStrategy.LOOPBACK) },
                ),
                AppMenuEntry(
                    title = redirectStrategyLabel(OAuthRedirectStrategy.CUSTOM_SCHEME),
                    subtitle = "Returns through a link to this app. Some services refuse it.",
                    selected = selected == OAuthRedirectStrategy.CUSTOM_SCHEME,
                    onClick = { onSelect(OAuthRedirectStrategy.CUSTOM_SCHEME) },
                ),
            ),
        )
    }
}
