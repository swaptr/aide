package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.resources.*
import org.jetbrains.compose.resources.painterResource

/**
 * The app's flat top **banner** (Material "banner": a persistent, *displacing* message — no scrim — for
 * notices the user shouldn't miss). One reusable surface, used at two clearly separate scopes:
 *
 *  - **Page-local** — a screen passes [InlineBanner] to its `AppPage`/`AppScaffold` `topBanner` slot. It
 *    lives in that screen's composition, so it is born and dies with the page, and it stays bounded by the
 *    content pane — the sidebar and app bar never move for it.
 *  - **Global** — `LocalAppBanner.current.show(...)` drives the hoisted [AppBannerState]; [AppBannerHost]
 *    (one per [AppScaffold], below the app bar) renders it on whatever screen is showing. ONLY for states
 *    that are true on every route: an outage, an update, offline.
 *
 * Mixing the two is the bug to avoid. A page-local concern in the global state flashes onto other pages
 * during navigation; and hoisting a page banner to the window root displaces the persistent navigation
 * chrome, which is why chat's "no model" prompt is an empty-state CTA instead.
 */
@Composable
fun InlineBanner(
    visible: Boolean,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    InlineEdgePanel(
        visible = visible,
        edge = PanelEdge.Top,
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction ?: {}) { Text(actionLabel) }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_lc_x),
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** One global banner — Material's app-wide "banner" (an update, an outage). For page-specific notices use a
 *  page-local [InlineBanner] instead. */
data class AppBanner(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val dismissible: Boolean = true,
)

/**
 * Hoisted holder for the **global** banner. Mirrors `SnackbarHostState`: the real instance is provided once
 * at the app root via [LocalAppBanner], any screen reaches it with `LocalAppBanner.current` and calls [show]
 * / [dismiss], and [AppBannerHost] renders it. One at a time — the newest [show] wins. This is for app-wide
 * messages ONLY; per-page notices belong in a page-local [InlineBanner].
 */
@Stable
class AppBannerState {
    var current by mutableStateOf<AppBanner?>(null)
        private set

    fun show(banner: AppBanner) {
        current = banner
    }

    fun show(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
        show(AppBanner(message = message, actionLabel = actionLabel, onAction = onAction))

    fun dismiss() {
        current = null
    }
}

@Composable
fun rememberAppBannerState(): AppBannerState = remember { AppBannerState() }

/**
 * The app-wide banner controller. Defaults to a standalone empty state so any [AppScaffold] composes without
 * an explicit provider (previews, tests) and simply shows no banner; the real, app-root instance is provided
 * once so every screen shares it.
 */
val LocalAppBanner = staticCompositionLocalOf { AppBannerState() }

/**
 * Renders the **global** [AppBannerState] as a top [InlineBanner] (below the app bar, no scrim, pushes
 * content down). Hosted once by [AppScaffold]; zero layout footprint while no global banner is set.
 */
@Composable
fun AppBannerHost(state: AppBannerState, modifier: Modifier = Modifier) {
    val banner = state.current
    // Keep the last banner mounted through the exit animation — [current] is already null by then.
    var shown by remember { mutableStateOf(banner) }
    if (banner != null) shown = banner
    val b = shown

    InlineBanner(
        visible = banner != null,
        message = b?.message.orEmpty(),
        modifier = modifier,
        actionLabel = b?.actionLabel,
        onAction = {
            b?.onAction?.invoke()
            state.dismiss()
        },
        onDismiss = if (b?.dismissible == true) ({ state.dismiss() }) else null,
    )
}
