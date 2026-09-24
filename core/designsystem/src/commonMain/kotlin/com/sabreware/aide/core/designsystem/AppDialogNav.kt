package com.sabreware.aide.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.PredictiveBackHandler
import com.sabreware.aide.core.designsystem.navigation.LocalNavigator
import com.sabreware.aide.core.designsystem.navigation.Navigator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Navigation + chrome surface a [AppDialog] page talks to. Pages push/pop/replace routes and close the whole
 * dialog (animated, via the underlying [AppDialogController]). Each page is handed a height-bounded slot and
 * scrolls its own content — the same page is a full screen too, where it already has to.
 */
interface NavDialogScope<R : Any> {
    /** True when there is a page to pop back to (i.e. we are not on the root route). */
    val canPop: Boolean

    /** Read-only view of the in-dialog back stack (last = current page). Mutate only via [push]/[pop]/[replaceTop]. */
    val backStack: List<R>

    fun push(route: R)

    fun pop()

    /** Replace the current page in place — back then skips it (e.g. detail → its clone's editor). */
    fun replaceTop(route: R)

    fun close(andThen: () -> Unit = {})
}

/** One `page` registration: route-type match plus the page's content. */
class NavDialogPageSpec<R : Any> @PublishedApi internal constructor(
    internal val matches: (R) -> Boolean,
    internal val content: @Composable ColumnScope.(route: R, dialog: NavDialogScope<R>) -> Unit,
)

/**
 * Registration DSL for [AppDialog] pages — mirrors the NavHost/Navigation3 `entry<Route>` shape: each route
 * type registers its content in one place.
 */
class NavDialogPagesBuilder<R : Any> @PublishedApi internal constructor() {
    @PublishedApi
    internal val specs = mutableListOf<NavDialogPageSpec<R>>()

    /**
     * A page draws its own header and body through `PageScaffold` — so the same page composable also renders
     * as a full screen, and so its [ScrollOwner] (scrolled by default) is decided in one place for both hosts.
     */
    inline fun <reified T : R> page(
        noinline content: @Composable ColumnScope.(route: T, dialog: NavDialogScope<R>) -> Unit,
    ) {
        specs += NavDialogPageSpec(
            matches = { it is T },
            content = { route, dialog -> content(route as T, dialog) },
        )
    }
}

@PublishedApi
internal val NavDialogJson = Json { ignoreUnknownKeys = true }

/**
 * An in-dialog back stack that survives recreation/process death. Routes must be `@Serializable`
 * (a sealed route interface serializes polymorphically out of the box). Extra [inputs] (e.g. a launcher
 * session) reset the stack when they change.
 */
@Composable
inline fun <reified R : Any> rememberNavDialogBackStack(start: R, vararg inputs: Any?): SnapshotStateList<R> {
    val listSerializer: KSerializer<List<R>> = remember { ListSerializer(serializer<R>()) }
    return rememberSaveable(
        start, *inputs,
        saver = Saver(
            save = { stack -> NavDialogJson.encodeToString(listSerializer, stack.toList()) },
            restore = { saved -> NavDialogJson.decodeFromString(listSerializer, saved).toMutableStateList() },
        ),
    ) { mutableStateListOf(start) }
}

/**
 * Saveable open/close gate for a route-driven dialog. [session] bumps on every [open] so callers can key
 * per-open state (e.g. `koinViewModel(key = "...-$session-...")`) and never see stale one-shot view-model
 * flags from a previous open; both the gate and the session survive recreation.
 */
@Stable
class NavDialogLauncher<R : Any> @PublishedApi internal constructor(session: Int, route: R?) {
    var session: Int by mutableIntStateOf(session)
        private set

    /** The root route the dialog was opened with, or null while closed. */
    var route: R? by mutableStateOf(route)
        private set

    fun open(route: R) {
        session += 1
        this.route = route
    }

    fun dismiss() {
        route = null
    }
}

@Composable
inline fun <reified R : Any> rememberNavDialogLauncher(): NavDialogLauncher<R> {
    val routeSerializer: KSerializer<R> = remember { serializer<R>() }
    return rememberSaveable(
        saver = listSaver(
            save = { launcher ->
                listOf(launcher.session, launcher.route?.let { NavDialogJson.encodeToString(routeSerializer, it) })
            },
            restore = { saved ->
                NavDialogLauncher(
                    session = saved[0] as Int,
                    route = (saved[1] as? String)?.let { NavDialogJson.decodeFromString(routeSerializer, it) },
                )
            },
        ),
    ) { NavDialogLauncher(0, null) }
}

// In-dialog page swaps reuse the app-wide [NavMotion] slide so dialog and screen nav are identical. The size
// TWEEN (not snap) animates the surface height on the slide's own timeline — and being part of the seekable
// transition, the height scrubs with the predictive-back gesture too. The bottom sheet re-anchors its detents
// to this animating height each frame, so a taller/shorter page grows/shrinks smoothly.
private fun dialogPageTransform(forward: Boolean): ContentTransform =
    ContentTransform(
        targetContentEnter = NavMotion.enter(forward),
        initialContentExit = NavMotion.exit(forward),
        sizeTransform = SizeTransform(clip = false) { _, _ -> tween(NavMotion.DurationMs, easing = NavMotion.Easing) },
    )

/**
 * An [AppDialog] hosting an in-dialog back stack with automatic chrome: the header title/subtitle come from
 * each page's registration, the back chevron and predictive back appear whenever the stack is poppable, and
 * page changes run a shared-axis horizontal slide. On compact the surface is a bottom sheet (detents own the
 * height); on wide it's a centered dialog. Callers register pages in the [pages] DSL; the back stack usually
 * comes from [rememberNavDialogBackStack] (gated by a [rememberNavDialogLauncher] on the host screen).
 *
 * Edge-to-edge — pages render their own insets/padding/spacing. Never nest another dialog on top: model
 * confirms as pushed pages instead.
 */
@Composable
fun <R : Any> AppDialog(
    backStack: SnapshotStateList<R>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Content (default) = surface height tracks each page; Expandable = one fixed size for the whole lifetime
    // (pages fill it, no per-page resize). Use Expandable for navigation flows with lists/search + keyboard.
    size: AppDialogSize = AppDialogSize.Content,
    pages: NavDialogPagesBuilder<R>.() -> Unit,
) {
    // Built ONCE. The page DSL is a static declaration ("declared once per host"), but rebuilding it per
    // recomposition handed AnimatedContent a fresh content lambda every frame, so its subtree could never
    // skip — on the app's main navigation surface, which is where perceived smoothness lives.
    val specs = remember { NavDialogPagesBuilder<R>().apply(pages).specs }
    val current = backStack.last()
    requireNotNull(specs.firstOrNull { it.matches(current) }) {
        "No page registered for route $current"
    }
    val canPop = backStack.size > 1
    // Slide direction (push=true / pop=false). Source of truth — NOT stack index, which can't tell a pop from
    // a push: the popped route is already off the stack by the time the transition segment builds.
    var forward by remember { mutableStateOf(true) }

    val fixed = size != AppDialogSize.Content

    // Seekable so the predictive-back gesture scrubs the real page transition (destination tracks the finger),
    // same as NavHost — not a separate nudge-then-fresh-slide on release.
    val transitionState = remember { SeekableTransitionState(current) }
    val transition = rememberTransition(transitionState, label = "nav-dialog-page")
    var progress by remember { mutableFloatStateOf(0f) }
    var inPredictiveBack by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (inPredictiveBack && canPop) {
        val target = backStack[backStack.lastIndex - 1]
        LaunchedEffect(target) { snapshotFlow { progress }.collect { transitionState.seekTo(it, target) } }
    } else {
        LaunchedEffect(current) {
            if (transitionState.currentState != current) {
                transitionState.animateTo(current)
            } else if (transitionState.fraction > 0f) {
                val totalMs = (transition.totalDurationNanos / 1_000_000L).toInt()
                animate(
                    transitionState.fraction, 0f,
                    animationSpec = tween((transitionState.fraction * totalMs).toInt()),
                ) { value, _ ->
                    scope.launch { if (value > 0f) transitionState.seekTo(value) else transitionState.snapTo(current) }
                }
            }
        }
    }

    AppDialogSurface(
        onDismiss = onDismiss,
        modifier = modifier,
        // No title or header actions: each page draws its own header via PageScaffold inside the slide, so the header
        // travels with its body. Per-page imePadding (below) instead of AppDialog's shared wrapper.
        title = null,
        subtitle = null,
        leadingAction = null,
        trailingActions = null,
        titleContent = null,
        applyImePadding = false,
        size = size,
        // The surface only BOUNDS each page; the page's own PageScaffold decides who scrolls (ScrollOwner), exactly
        // as it does on a full screen. A surface scroll here would scroll the page header away with the body and
        // nest a second scroller around every list page.
        scroll = ScrollOwner.Content,
    ) { controller ->
        // Each page's saved state (its tab, scroll, search, selection) lives as long as the page is ON the
        // stack, not just while it is drawn: AnimatedContent disposes a page the moment the slide to the next
        // one ends, and without this, going back rebuilt it from scratch — first tab, top of the list. Created
        // inside the surface so it travels with the body across a sheet ⇄ dialog switch.
        val saveable = rememberSaveableStateHolder()
        val liveKeys = backStack.mapIndexed { depth, route -> pageKey(depth, route) }
        var keptKeys by remember { mutableStateOf(liveKeys) }
        LaunchedEffect(liveKeys) {
            // Popped pages forget their state, so pushing the same route again opens it fresh.
            (keptKeys - liveKeys.toSet()).forEach(saveable::removeState)
            keptKeys = liveKeys
        }

        val dialogScope = remember(controller, backStack) {
            NavDialogScopeImpl(
                stack = backStack,
                controller = controller,
                setForward = { forward = it },
            )
        }

        PredictiveBackHandler(enabled = canPop) { events ->
            forward = false
            progress = 0f
            try {
                events.collect { event ->
                    inPredictiveBack = true
                    progress = event.progress
                }
                // Commit: clear the flag BEFORE the pop so the non-predictive effect owns the finish.
                inPredictiveBack = false
                if (canPop) backStack.removeAt(backStack.lastIndex)
            } catch (_: CancellationException) {
                inPredictiveBack = false
            }
        }

        transition.AnimatedContent(
            modifier = Modifier.weight(1f, fill = fixed).fillMaxWidth(),
            transitionSpec = { dialogPageTransform(forward) },
        ) { route ->
            val spec = specs.firstOrNull { it.matches(route) }
            Column(modifier = (if (fixed) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).imePadding()) {
                if (spec != null) {
                    // Bounded, so the page's PageScaffold can pin its header and scroll its body under it.
                    Column(
                        modifier = Modifier.weight(1f, fill = fixed).fillMaxWidth(),
                    ) {
                        val columnScope = this
                        // Pages call `navigator()` → this dialog's navigator (nearest wins over the app's), so
                        // the same `navigate(route)` works inside the dialog and on a full screen.
                        // LocalPagePresentation = Dialog so any PageScaffold in a page renders dialog chrome.
                        // LocalPageCanGoBack = the RENDERED route's depth (same rule as the spec header
                        // above), not the live stack: predictive back composes the page underneath before
                        // the pop commits, so a live/frozen canGoBack read there captures depth 2 and
                        // leaves a stale chevron on the root page.
                        // The rendered page's depth: where it sits on the stack, or just past the top for the page
                        // sliding out after a pop (its state is being dropped anyway).
                        val depth = backStack.lastIndexOf(route).takeIf { it >= 0 } ?: backStack.size
                        CompositionLocalProvider(
                            LocalNavigator provides dialogScope,
                            LocalPagePresentation provides PagePresentation.Dialog,
                            LocalPageCanGoBack provides (route != backStack.first()),
                        ) {
                            saveable.SaveableStateProvider(pageKey(depth, route)) {
                                spec.content(columnScope, route, dialogScope)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A page's saved-state key: its depth AND route, so two pages composed at once during a slide never share one,
 * and a route re-pushed after a pop starts fresh. A String, because it goes into the platform's saved state.
 */
private fun pageKey(depth: Int, route: Any): String = "$depth:$route"

private class NavDialogScopeImpl<R : Any>(
    private val stack: SnapshotStateList<R>,
    private val controller: AppDialogController,
    private val setForward: (Boolean) -> Unit,
) : NavDialogScope<R>, Navigator {
    override val canPop: Boolean get() = stack.size > 1
    override val backStack: List<R> get() = stack

    override fun push(route: R) {
        setForward(true)
        stack.add(route)
    }

    override fun pop() {
        if (stack.size > 1) {
            setForward(false)
            stack.removeAt(stack.lastIndex)
        }
    }

    override fun replaceTop(route: R) {
        setForward(true)
        stack[stack.lastIndex] = route
    }

    override fun close(andThen: () -> Unit) = controller.close(andThen)

    // Navigator (host-agnostic): the dialog IS a navigator — `navigate` pushes a page, `goBack` pops, and
    // popping past the root closes the dialog.
    override val canGoBack: Boolean get() = stack.size > 1

    @Suppress("UNCHECKED_CAST")
    override fun navigate(route: Any) = push(route as R)

    override fun goBack(): Boolean =
        if (stack.size > 1) {
            pop()
            true
        } else {
            controller.close()
            false
        }
}
