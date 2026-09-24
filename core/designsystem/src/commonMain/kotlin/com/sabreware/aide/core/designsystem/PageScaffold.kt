package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.designsystem.navigation.navigator

/** Where a [PageScaffold] is being rendered. A host provides this; a page never sets it. Defaults to [Screen]
 *  so any page on the app's NavDisplay is a screen unless a modal flow re-provides [Dialog]. */
enum class PagePresentation { Screen, Dialog }

val LocalPagePresentation = staticCompositionLocalOf { PagePresentation.Screen }

/**
 * A host-agnostic page: a title + header actions ([HeaderAction]) + a [body], defined ONCE and rendered with the right
 * chrome for wherever it's hosted: [AppHeader] placed as a page on a full screen, as a modal inside a dialog.
 * Back navigation + actions route through [navigator] (the nearest host's navigator), so the same page works
 * as a full app page or a dialog page with no per-host code. Register the page ONCE (an `entry<T>`); the back
 * stack element decides whether it is a screen or a page in a modal.
 *
 * **Scrolling is inferred, not requested.** By default ([ScrollOwner.Surface]) the scaffold scrolls the body
 * under its pinned header in both hosts, so an ordinary page never adds a `verticalScroll` and is never
 * clipped — and must not add one (two same-axis scrollers nest and throw). A page whose body already IS a
 * scroller (a `LazyColumn`, a tabbed pager of lists) passes [ScrollOwner.Content] and receives a bounded,
 * fill-height `contentModifier` instead.
 */
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingAction: HeaderAction? = null,
    trailingActions: List<HeaderAction>? = null,
    scroll: ScrollOwner = ScrollOwner.Surface,
    /** Replaces the title band — a collection's search field while it searches ([collectionTopBar]). */
    titleContent: (@Composable () -> Unit)? = null,
    body: @Composable (contentModifier: Modifier) -> Unit,
) {
    // The band a page's header draws: its title, or what the page put there instead.
    val band: @Composable () -> Unit = titleContent ?: { PageTitle(title = title, subtitle = subtitle) }
    val scrolled = scroll == ScrollOwner.Surface
    val scrollState = rememberScrollState()
    when (LocalPagePresentation.current) {
        // Both branches honour `modifier` and `subtitle`. They used to diverge — the screen branch dropped
        // both silently — which is precisely the one-definition-two-hosts failure this component exists to
        // prevent: a page gains a subtitle, it appears in the sheet, and nobody notices it is missing on the
        // full screen.
        PagePresentation.Screen -> AppScaffold(
            titleContent = band,
            modifier = modifier,
            leadingAction = leadingAction,
            trailingActions = trailingActions,
        ) { contentModifier ->
            // On a wide window cap + center the page body (menu/detail content) instead of stretching it
            // edge-to-edge. The scroll spans the full pane so the gesture and scrollbar work in the margins
            // too; a self-scrolling body keeps a fill-height modifier so its list fills the pane.
            Box(
                modifier = contentModifier.then(if (scrolled) Modifier.verticalScroll(scrollState) else Modifier),
                contentAlignment = Alignment.TopCenter,
            ) {
                val fill = if (scrolled) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
                body(Modifier.widthIn(max = ContentMaxWidth).then(fill))
            }
        }

        PagePresentation.Dialog -> Column(modifier.fillMaxWidth()) {
            AppHeader(
                title = title,
                subtitle = subtitle,
                leadingAction = rememberLeadingAction(leadingAction),
                trailingActions = trailingActions,
                titleContent = titleContent,
            )
            // Bounded to the space the header leaves (the dialog host bounds the page), so the header stays
            // pinned and the body scrolls — or, for a self-scrolling body, fills — beneath it.
            body(
                Modifier
                    .weight(1f, fill = !scrolled)
                    .fillMaxWidth()
                    .then(if (scrolled) Modifier.verticalScroll(scrollState) else Modifier),
            )
        }
    }
}

/**
 * Host-provided, per-rendered-page back availability. A transition host (AppDialog's AnimatedContent)
 * composes the *incoming* page while the outgoing route is still on the stack — the predictive-back
 * gesture composes the page underneath BEFORE the pop commits — so any read of the live back stack
 * (even one frozen at first composition) captures the wrong depth and leaves a stale chevron on the
 * root page. A host that knows the rendered route's own depth provides it here; null (unset) falls back
 * to the frozen live query below.
 */
val LocalPageCanGoBack = compositionLocalOf<Boolean?> { null }

/**
 * **The** leading-slot rule for a navigated page, in one place, shared by both hosts (the screen top bar and
 * [PageScaffold]'s dialog header): the page's own [leadingAction] if it passed one, else a back chevron when
 * the page can go back, else nothing. No page hand-rolls the chevron or takes an `onClose` for it: how to
 * leave is the host's business, and the host already knows (pop the back stack, or close the modal flow).
 *
 * "Can go back": [LocalPageCanGoBack] wins when a host provides it; it is the rendered page's OWN depth,
 * correct even while a transition has both pages composed. The fallback freezes `canGoBack` at first
 * composition ON PURPOSE: it queries the whole back stack live, so a page sliding OUT would re-read it
 * mid-transition and flash a back arrow on the page you just left. A page's own depth never changes once it
 * is on the stack. Both reads run whether or not [leadingAction] is set, so the frozen value is never skipped.
 */
@Composable
internal fun rememberLeadingAction(leadingAction: HeaderAction?): HeaderAction? {
    val nav = navigator()
    val frozen = remember { nav.canGoBack }
    val canGoBack = LocalPageCanGoBack.current ?: frozen
    val back = remember(nav, canGoBack) { if (canGoBack) HeaderAction.back { nav.goBack() } else null }
    return leadingAction ?: back
}
