package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/**
 * Base screen scaffold: the page header ([AppHeader]) + edge banner regions + the standard window insets. [content]
 * receives the inset-applied [Modifier] for its root, so the screen owns its scroll: fill-height list/pager
 * screens (chat, task list) apply it directly; ordinary screens use [AppPage], which wraps it in a scroll.
 *
 * **Banners dock to the screen edges, outside the header.** [topBanner] renders above the header, under
 * the status bar; the app-wide [AppBannerHost] shares that top region; [bottomPanel] renders below the
 * content, under the nav bar. Each is an [InlineEdgePanel] that bleeds its background edge-to-edge and insets
 * its own content (the matching system bar is baked in by edge) — so the header's own status inset and the
 * content's nav inset are turned OFF here and owned by the bar regions instead (applying a system-bar inset
 * twice just shrinks the bar awkwardly). A baseline status-/nav-bar spacer keeps the header and content
 * clear of the system bars when no banner shows; a banner overlays it and pushes the rest of the screen.
 */
@Composable
fun AppScaffold(
    titleContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingAction: HeaderAction? = null,
    trailingActions: List<HeaderAction>? = null,
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    headerColor: Color = MaterialTheme.colorScheme.surface,
    topBanner: @Composable () -> Unit = {},
    bottomPanel: @Composable () -> Unit = {},
    /** [HeaderPlacement.Page], or [HeaderPlacement.CenteredPage] when [titleContent] is a centered control. */
    placement: HeaderPlacement = HeaderPlacement.Page,
    content: @Composable (contentModifier: Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                TopEdgeRegion { topBanner() }
                // The one header ([AppHeader]), placed as a page: full width, so the leading action stays pinned
                // to the pane's leading edge and the trailing ones to the trailing edge (capping the bar instead
                // left the hamburger floating mid-bar on a wide window). Content below is what caps + centers.
                // The status bar is owned by the banner region above, so the header applies no inset of its own.
                Surface(color = headerColor) {
                    AppHeader(
                        leadingAction = rememberLeadingAction(leadingAction),
                        trailingActions = trailingActions,
                        placement = placement,
                        titleContent = titleContent,
                    )
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                bottomBar()
                BottomEdgeRegion { bottomPanel() }
            }
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        // Top + bottom system bars are owned by the edge regions above/below; keep only the horizontal
        // insets (display cutouts in landscape) for the content here.
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { inner ->
        content(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        )
    }
}

/** The top banner region: a status-bar-height baseline (so the header below clears the status bar when no
 *  banner shows) with the global [AppBannerHost] and any page-local banner overlaid on top of it. */
@Composable
private fun TopEdgeRegion(pageBanner: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
        AppBannerHost(LocalAppBanner.current)
        pageBanner()
    }
}

/** Mirror of [TopEdgeRegion] for the bottom: a nav-bar-height baseline with the page-local bottom panel
 *  overlaid, so content clears the nav bar when no panel shows and the panel bleeds under it when it does. */
@Composable
private fun BottomEdgeRegion(panel: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
        panel()
    }
}

@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingAction: HeaderAction? = null,
    trailingActions: List<HeaderAction>? = null,
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    headerColor: Color = MaterialTheme.colorScheme.surface,
    topBanner: @Composable () -> Unit = {},
    bottomPanel: @Composable () -> Unit = {},
    /** Replaces the title band while set — a collection's search field ([com.sabreware.aide.core.designsystem.browse.collectionBar]). */
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable (contentModifier: Modifier) -> Unit,
) {
    AppScaffold(
        titleContent = titleContent ?: { PageTitle(title, subtitle) },
        modifier = modifier,
        leadingAction = leadingAction,
        trailingActions = trailingActions,
        snackbarHost = snackbarHost,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        headerColor = headerColor,
        topBanner = topBanner,
        bottomPanel = bottomPanel,
        content = content,
    )
}

/**
 * The standard, always-scrollable page: the page header + insets + `imePadding` + a vertical-scroll content
 * [Column]. Use for normal form/content/detail screens. Fill-height screens that own their scroll (a
 * `LazyColumn` or pager — chat, task list) use [AppScaffold] instead.
 */
@Composable
fun AppPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingAction: HeaderAction? = null,
    trailingActions: List<HeaderAction>? = null,
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    headerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppPage(
        titleContent = { PageTitle(title, subtitle) },
        modifier = modifier,
        leadingAction = leadingAction,
        trailingActions = trailingActions,
        snackbarHost = snackbarHost,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        headerColor = headerColor,
        content = content,
    )
}

@Composable
fun AppPage(
    titleContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingAction: HeaderAction? = null,
    trailingActions: List<HeaderAction>? = null,
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    headerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppScaffold(
        titleContent = titleContent,
        modifier = modifier,
        leadingAction = leadingAction,
        trailingActions = trailingActions,
        snackbarHost = snackbarHost,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        headerColor = headerColor,
    ) { contentModifier ->
        // On a wide window the content column caps at ContentMaxWidth and centers instead of stretching
        // edge-to-edge; on a phone it fills the screen (screen < cap). The scroll stays full-width so the
        // scrollbar/overscroll span the whole page.
        Column(
            modifier = contentModifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                content = content,
            )
        }
    }
}

/** A page's title text in the header band: [HeaderText], start-aligned like the content under it. */
@Composable
internal fun PageTitle(title: String, subtitle: String? = null) {
    HeaderText(title = title, subtitle = subtitle, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
}
