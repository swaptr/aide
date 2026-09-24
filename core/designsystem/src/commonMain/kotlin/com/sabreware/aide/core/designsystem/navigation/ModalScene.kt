package com.sabreware.aide.core.designsystem.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.NavDisplay
import com.sabreware.aide.core.designsystem.AppDialogController
import com.sabreware.aide.core.designsystem.AppDialogSize
import com.sabreware.aide.core.designsystem.AppDialogSurface
import com.sabreware.aide.core.designsystem.LocalPageCanGoBack
import com.sabreware.aide.core.designsystem.LocalPagePresentation
import com.sabreware.aide.core.designsystem.NavMotion
import com.sabreware.aide.core.designsystem.PagePresentation
import com.sabreware.aide.core.designsystem.ScrollOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * A page shown in a MODAL container (a sheet, or a dialog on a wide window) as part of [flow], instead of as a
 * full screen. Presentation is the caller's intent — Settings opens Models as a page, the chat pill opens the
 * same Models page in a sheet — so it lives on the back-stack element, never on the route type. Every page of
 * one flow is wrapped with the same [flow]; that run is ONE container.
 */
@Serializable
data class InModal(val flow: String, val key: NavKey) : NavKey

/**
 * Open [start] as the first page of [flow] in a modal container (a sheet, or a dialog on a wide window) over the
 * current page. Every page it then pushes stays in that container; closing it pops them all. From a page that
 * is itself in a flow, this opens the new flow over it.
 */
fun Navigator.openModal(flow: String, start: NavKey) = navigate(InModal(flow, start))

/**
 * Wraps an entry provider so an [InModal] key renders exactly the page its inner key does — ONE registration
 * per page for every host — tagged with its flow, which [ModalSceneStrategy] reads.
 */
fun modalAware(pages: (NavKey) -> NavEntry<NavKey>): (NavKey) -> NavEntry<NavKey> = { key ->
    if (key is InModal) {
        val inner = pages(key.key)
        NavEntry(
            key = key,
            contentKey = "${key.flow}/${inner.contentKey}",
            metadata = inner.metadata + (ModalFlowMetadata to key.flow),
        ) { inner.Content() }
    } else {
        // A screen carries its own key, so [rememberPageDepthDecorator] finds it without guessing from strings.
        val page = pages(key)
        NavEntry(key = key, contentKey = page.contentKey, metadata = page.metadata + (ScreenKeyMetadata to key)) {
            page.Content()
        }
    }
}

/**
 * A screen's back chevron from its OWN place in the stack, frozen for the entry: predictive back composes the
 * page underneath before the pop commits, and a live read there would flash the wrong icon. Pages in a modal
 * flow get theirs from [ModalSceneStrategy]'s scene instead, so this decorator leaves them alone.
 */
@Composable
fun rememberPageDepthDecorator(backStack: List<NavKey>): NavEntryDecorator<NavKey> =
    remember(backStack) {
        NavEntryDecorator { entry ->
            val key = entry.metadata[ScreenKeyMetadata] as? NavKey
            if (key == null) {
                entry.Content()
            } else {
                val canGoBack = remember(entry.contentKey) { backStack.indexOf(key) > 0 }
                CompositionLocalProvider(LocalPageCanGoBack provides canGoBack) { entry.Content() }
            }
        }
    }

/**
 * Renders a trailing run of [InModal] entries of one flow as ONE modal container over the page beneath it.
 *
 * A regular [Scene], not an overlay: its key is (flow, page beneath), so pushing and popping pages inside the
 * flow keeps the same scene — NavDisplay just recomposes it with the live pages — and the container never
 * re-opens. The scene handles back itself (a page pop inside the flow is scrubbed by predictive back; back on
 * the flow's first page closes the container), so NavDisplay's own back never sees a modal page.
 */
class ModalSceneStrategy(private val backStack: MutableList<NavKey>) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        val flow = entries.lastOrNull()?.modalFlow ?: return null
        val pages = entries.takeLastWhile { it.modalFlow == flow }
        // A flow always opens over a page; a stack that is ONLY a flow has nothing to sit on.
        val base = entries.getOrNull(entries.size - pages.size - 1) ?: return null
        return ModalScene(flow, base, pages, previousEntries = entries.dropLast(1), backStack)
    }
}

private const val ModalFlowMetadata = "aide.modal.flow"
private const val ScreenKeyMetadata = "aide.screen.key"

private val NavEntry<*>.modalFlow: String? get() = metadata[ModalFlowMetadata] as? String

private data class ModalSceneKey(val flow: String, val base: Any)

private class ModalScene(
    private val flow: String,
    private val base: NavEntry<NavKey>,
    private val pages: List<NavEntry<NavKey>>,
    override val previousEntries: List<NavEntry<NavKey>>,
    private val backStack: MutableList<NavKey>,
) : Scene<NavKey> {
    override val key: Any = ModalSceneKey(flow, base.contentKey)
    override val entries: List<NavEntry<NavKey>> = listOf(base) + pages

    // The page beneath must not slide when a flow opens or closes: the container's own motion (the sheet
    // rising, the dialog fading) IS the transition. This scene is on top both ways, so NavDisplay reads these.
    override val metadata: Map<String, Any> =
        NavDisplay.transitionSpec { NoMotion } +
            NavDisplay.popTransitionSpec { NoMotion } +
            NavDisplay.predictivePopTransitionSpec { NoMotion }

    override val content: @Composable () -> Unit = {
        base.Content()
        ModalPages(
            pages = pages,
            onPop = { if (backStack.isInFlow(flow, depth = 2)) backStack.removeAt(backStack.lastIndex) },
            onClose = { while (backStack.isInFlow(flow, depth = 1)) backStack.removeAt(backStack.lastIndex) },
            push = { backStack.add(InModal(flow, it)) },
            pushRaw = { backStack.add(it) },
            replace = { backStack[backStack.lastIndex] = InModal(flow, it) },
        )
    }
}

private val NoMotion = ContentTransform(EnterTransition.None, ExitTransition.None)

/** Whether the top [depth] keys of the stack all belong to [flow]. */
private fun List<NavKey>.isInFlow(flow: String, depth: Int): Boolean =
    size >= depth && takeLast(depth).all { (it as? InModal)?.flow == flow }

/**
 * The flow's pages inside the modal container: the app's page motion ([NavMotion]), seekable so predictive
 * back scrubs a pop, and a size tween on the same timeline. Each page's saved state and view models come from
 * NavDisplay's entry decorators, exactly as for a page on a screen.
 */
@Composable
private fun ModalPages(
    pages: List<NavEntry<NavKey>>,
    onPop: () -> Unit,
    onClose: () -> Unit,
    push: (NavKey) -> Unit,
    pushRaw: (NavKey) -> Unit,
    replace: (NavKey) -> Unit,
) {
    val top = pages.last()
    val depthOf = remember { mutableMapOf<Any, Int>() }
    pages.forEachIndexed { i, page -> depthOf[page.contentKey] = i }

    // Slide direction from the depth change: a deeper top is a push, a shallower one a pop.
    var lastDepth by remember { mutableIntStateOf(pages.size) }
    val forward = pages.size >= lastDepth
    LaunchedEffect(pages.size) { lastDepth = pages.size }

    val transitionState = remember { SeekableTransitionState(top) }
    val transition = rememberTransition(transitionState, label = "modal-page")
    var progress by remember { mutableFloatStateOf(0f) }
    var inPredictiveBack by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canPop = pages.size > 1

    if (inPredictiveBack && canPop) {
        val target = pages[pages.lastIndex - 1]
        LaunchedEffect(target.contentKey) { snapshotFlow { progress }.collect { transitionState.seekTo(it, target) } }
    } else {
        LaunchedEffect(top.contentKey) {
            if (transitionState.currentState.contentKey != top.contentKey) {
                transitionState.animateTo(top)
            } else if (transitionState.fraction > 0f) {
                val totalMs = (transition.totalDurationNanos / 1_000_000L).toInt()
                animate(transitionState.fraction, 0f, animationSpec = tween((transitionState.fraction * totalMs).toInt())) { value, _ ->
                    scope.launch { if (value > 0f) transitionState.seekTo(value) else transitionState.snapTo(top) }
                }
            }
        }
    }

    val latestOnClose by rememberUpdatedState(onClose)
    AppDialogSurface(
        // Every close path (drag away, scrim, back on the first page, a page's goBack) ends here, AFTER the
        // container's exit animation — so the flow leaves the stack once, with one animation.
        onDismiss = { latestOnClose() },
        modifier = Modifier,
        title = null,
        subtitle = null,
        leadingAction = null,
        trailingActions = null,
        titleContent = null,
        applyImePadding = false,
        size = AppDialogSize.Expandable,
        scroll = ScrollOwner.Content,
    ) { controller ->
        val navigator = remember(controller) { ModalNavigator(push, pushRaw, replace, { onPop() }, controller) }
        navigator.depth = pages.size

        PredictiveBackHandler(enabled = canPop) { events ->
            progress = 0f
            try {
                events.collect { event ->
                    inPredictiveBack = true
                    progress = event.progress
                }
                inPredictiveBack = false
                onPop()
            } catch (_: CancellationException) {
                inPredictiveBack = false
            }
        }

        transition.AnimatedContent(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentKey = { it.contentKey },
            transitionSpec = {
                ContentTransform(
                    targetContentEnter = NavMotion.enter(forward),
                    initialContentExit = NavMotion.exit(forward),
                    sizeTransform = SizeTransform(clip = false) { _, _ -> tween(NavMotion.DurationMs, easing = NavMotion.Easing) },
                )
            },
        ) { page ->
            Column(Modifier.fillMaxSize().imePadding()) {
                CompositionLocalProvider(
                    LocalNavigator provides navigator,
                    LocalPagePresentation provides PagePresentation.Dialog,
                    // The RENDERED page's own depth, so the page underneath during a predictive-back scrub
                    // never shows a stale chevron.
                    LocalPageCanGoBack provides ((depthOf[page.contentKey] ?: 0) > 0),
                ) {
                    Column(Modifier.weight(1f)) { page.Content() }
                }
            }
        }
    }
}

/** The navigator a flow's pages see: [navigate] pushes a page into the flow, [goBack] pops or closes it. */
private class ModalNavigator(
    private val push: (NavKey) -> Unit,
    private val pushRaw: (NavKey) -> Unit,
    private val replaceTop: (NavKey) -> Unit,
    private val pop: () -> Unit,
    private val controller: AppDialogController,
) : Navigator {
    var depth: Int = 1

    override val canGoBack: Boolean get() = depth > 1

    // A page already wrapped (another flow opened from this one) goes on as-is; anything else joins this flow.
    override fun navigate(route: Any) = if (route is InModal) pushRaw(route) else push(route as NavKey)

    override fun replace(route: Any) = replaceTop(route as NavKey)

    override fun goBack(): Boolean =
        if (depth > 1) {
            pop()
            true
        } else {
            controller.close()
            false
        }
}
