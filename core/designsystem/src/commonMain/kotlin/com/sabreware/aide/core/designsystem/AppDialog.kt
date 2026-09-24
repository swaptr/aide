package com.sabreware.aide.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentWithReceiverOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.theme.AppSpacing
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/** Control handed to dialog content. [close] animates the dialog out, then runs [andThen]. Works for both
 *  presentations (bottom sheet on compact, centered dialog on wide). */
interface AppDialogController {
    fun close(andThen: () -> Unit = {})
}

/**
 * Sizing only — scrolling is not a size decision (see [ScrollOwner]).
 *
 * [Content] (default): the surface takes the body's natural height, up to what the window allows.
 * [Expandable]: the body fills a fixed-size surface (dialog: an ~80%-height card), for multi-page flows with
 * lists/search whose height should not twitch as pages swap. The sheet detents are the same either way.
 */
enum class AppDialogSize { Content, Expandable }

/**
 * The app's modal surface: a bottom sheet (drag to expand/dismiss) or a centered dialog, whichever
 * [LocalModalPresentation] resolved for this host and window (see [ModalPolicy]). Both share one content
 * contract (`content(controller)`), so leaf dialogs and multi-page flows adapt with no per-call code, and both
 * keep every byte of the content reachable: see [AppDialogSize] for who scrolls. Drawn in the app's own window
 * through [ModalLayer], over its own scrim.
 */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    // Header actions ([HeaderAction]), drawn only with a [title]. Null draws nothing: a leaf sheet has no
    // navigation of its own, so unlike a page it derives no back chevron.
    leadingAction: HeaderAction? = null,
    trailingActions: List<HeaderAction>? = null,
    // Drawn in the header band instead of [title] (which must still be given, for the header to show) — a
    // collection's search field while it searches ([com.sabreware.aide.core.designsystem.browse.collectionBar]).
    titleContent: (@Composable () -> Unit)? = null,
    // When true, the surface pads its content for the keyboard (sheet: content scroll ends at the keyboard
    // top; dialog: the card floats above the keyboard). A multi-page host ([AppDialog] backStack overload)
    // sets this false and applies the keyboard inset itself, per page.
    applyImePadding: Boolean = true,
    size: AppDialogSize = AppDialogSize.Content,
    content: @Composable ColumnScope.(AppDialogController) -> Unit,
) {
    // A leaf dialog's content is always Surface-scrolled: callers never ask, and a dialog that needs a lazy
    // list is a multi-page AppDialog whose page declares ScrollOwner.Content through PageScaffold.
    AppDialogSurface(
        onDismiss, modifier, title, subtitle, leadingAction, trailingActions, titleContent, applyImePadding, size,
        scroll = ScrollOwner.Surface,
        content = content,
    )
}

@Composable
internal fun AppDialogSurface(
    onDismiss: () -> Unit,
    modifier: Modifier,
    title: String?,
    subtitle: String?,
    leadingAction: HeaderAction?,
    trailingActions: List<HeaderAction>?,
    titleContent: (@Composable () -> Unit)?,
    applyImePadding: Boolean,
    size: AppDialogSize,
    scroll: ScrollOwner,
    content: @Composable ColumnScope.(AppDialogController) -> Unit,
) {
    // The page under a modal is covered, not read: its names stop walking until the modal leaves.
    CoverMarquees()
    // The body is MOVABLE content: the sheet and the dialog are different containers (and windows), so a
    // presentation switch — a phone rotating across the Adaptive bounds — would otherwise compose the body
    // anew, dropping its state in process and, after a recreation, restoring the `rememberSaveable` state
    // saved at that presentation's LAST visit (keys are composition positions). Movable content keeps one
    // identity across both, so the open page, its fields and its scroll come back exactly where they were.
    val latestContent by rememberUpdatedState(content)
    val body = remember {
        movableContentWithReceiverOf<ColumnScope, AppDialogController> { controller -> latestContent(controller) }
    }
    when (LocalModalPresentation.current) {
        ModalPresentation.Sheet ->
            BottomSheetContainer(onDismiss, modifier, title, subtitle, leadingAction, trailingActions, titleContent, applyImePadding, size, scroll, body)
        ModalPresentation.Dialog ->
            CenteredDialogContainer(onDismiss, modifier, title, subtitle, leadingAction, trailingActions, titleContent, applyImePadding, size, scroll, body)
    }
}

/**
 * The body slot under the header, shared by both containers — the one place a modal's scrolling is decided.
 *
 * It is ALWAYS height-bounded: a weight in the surface's column gives it exactly the space the header leaves,
 * and never more. That bound is what guarantees nothing is clipped — a [ScrollOwner.Surface] body scrolls
 * inside it, a [ScrollOwner.Content] body is handed it and scrolls itself. `fill = false` lets a short body
 * keep its natural height (the surface wraps it); [fill] = true is the fixed-size surface. [insets] pad the
 * viewport (keyboard, nav bar) OUTSIDE the scroll, so the scrollable area ends where the visible area does and
 * the last row can always be scrolled above the keyboard.
 */
@Composable
private fun ColumnScope.DialogBodySlot(
    fill: Boolean,
    scroll: ScrollOwner,
    insets: Modifier,
    controller: AppDialogController,
    content: @Composable ColumnScope.(AppDialogController) -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f, fill = fill)
            .fillMaxWidth()
            .then(insets)
            .then(if (scroll == ScrollOwner.Surface) Modifier.verticalScroll(rememberScrollState()) else Modifier),
    ) { content(controller) }
}

// ── Shared tokens ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Resting positions of the bottom sheet, as downward offset from fully-shown.
 *
 * Every sheet is the same: closed → opens at [Peek] → swipe up to [Full] → scroll it like any other sheet.
 * [Peek] shows [PeekFraction] of the window, or the whole content when that is shorter; [Full] shows as much
 * of the content as the window fits, and exists only when there IS more than the peek to show. So a short
 * sheet has one stop and cannot be expanded into empty space, and a tall one has exactly two.
 */
private enum class SheetValue { Hidden, Peek, Full }

/** Fraction of the window the [SheetValue.Peek] detent shows — the FIRST detent, where every sheet opens. */
private const val PeekFraction = 0.6f
private const val ScrimMaxAlpha = 0.4f

/** Max bottom-sheet width: a sheet on a wide-but-short window (a phone in landscape) is centered at this cap.
 *  Matches Material3's `BottomSheetDefaults.SheetMaxWidth` (640.dp). */
private val SheetMaxWidth = 640.dp

/** Centered-dialog card: capped width, fixed-height fraction for [AppDialogSize.Expandable], and the margin
 *  it keeps from the window's safe area. */
private val DialogMaxWidth = 560.dp
private const val DialogHeightFraction = 0.8f
private val DialogEdgeMargin = 24.dp

/** Gap between the card's content and its bottom edge. Enough that a full-bleed list row (or a button row)
 *  clears the 28.dp corner radius instead of being pinched by it. The header supplies the matching gap at the
 *  top, so headerless content is the caller's own header's problem, not the card's. */
private val DialogContentBottomPadding = AppSpacing.lg

// Snappy + no bounce: a short open/settle keeps content tappable quickly.
private val SettleSpec: AnimationSpec<Float> = spring(stiffness = Spring.StiffnessMedium)
private val CloseSpec: AnimationSpec<Float> = tween(durationMillis = 200, easing = FastOutLinearInEasing)

// ── Closing, shared by both presentations ───────────────────────────────────────────────────────────────

/**
 * A close is a one-way door. The **first** [close] owns the payload and every later one is inert.
 *
 * Both surfaces stay on screen for their whole exit animation, so a second tap — on the scrim, on Cancel,
 * on the row behind — used to arrive while the first close was still animating, overwrite the stored payload
 * with the default no-op and cancel the running animation. The destructive path was the worst case: the
 * dialog visibly dismissed and the delete never ran. [isClosing] is the other half of the fix — it lets the
 * surface stop accepting pointer input the moment it starts leaving, so the second tap never happens.
 */
private abstract class ClosingDialogController(
    private val onDismiss: () -> Unit,
) : AppDialogController {
    private var dismissed = false
    private var pending: (() -> Unit)? = null

    /** True from the first [close] until the exit lands (or is reversed by a drag). Observable. */
    var isClosing by mutableStateOf(false)
        private set

    final override fun close(andThen: () -> Unit) {
        if (isClosing || dismissed) return
        isClosing = true
        pending = andThen
        startClose()
    }

    /** Start the exit. Implementations call [finishDismiss] when it lands, [abortClose] if it is reversed. */
    protected abstract fun startClose()

    /** The exit was interrupted (a drag pulled the sheet back up) — let a later close take over. */
    protected fun abortClose() {
        isClosing = false
        pending = null
    }

    fun finishDismiss() {
        if (dismissed) return
        dismissed = true
        val payload = pending
        pending = null
        payload?.invoke()
        onDismiss()
    }
}

/**
 * Swallows every pointer event while [blocked], at [PointerEventPass.Initial] so nothing downstream ever
 * sees the gesture. A surface on its way out must not accept another tap: the second tap is never the one
 * the user meant, and the row under it may be destructive.
 */
private fun Modifier.blockPointerInput(blocked: Boolean): Modifier =
    if (!blocked) {
        this
    } else {
        this.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    }

// ── Centered dialog (medium / expanded) ─────────────────────────────────────────────────────────────────

/** Drives the fade/scale out then the one-shot [onDismiss]. [close]'s andThen runs after the exit. */
private class CenteredDialogControllerImpl(
    private val visible: MutableTransitionState<Boolean>,
    onDismiss: () -> Unit,
) : ClosingDialogController(onDismiss) {
    override fun startClose() {
        visible.targetState = false
    }
}

@Composable
private fun CenteredDialogContainer(
    onDismiss: () -> Unit,
    modifier: Modifier,
    title: String?,
    subtitle: String?,
    leadingAction: HeaderAction?,
    trailingActions: List<HeaderAction>?,
    titleContent: (@Composable () -> Unit)?,
    applyImePadding: Boolean,
    size: AppDialogSize,
    scroll: ScrollOwner,
    content: @Composable ColumnScope.(AppDialogController) -> Unit,
) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visibleState.targetState = true } // entrance
    val controller = remember(visibleState) { CenteredDialogControllerImpl(visibleState) { latestDismiss() } }
    val fixed = size != AppDialogSize.Content

    // Fire onDismiss once the exit animation has fully played out (after it has been shown at least once, so
    // the initial hidden→shown frame doesn't count as a dismissal).
    var hasShown by remember { mutableStateOf(false) }
    LaunchedEffect(visibleState.currentState) { if (visibleState.currentState) hasShown = true }
    LaunchedEffect(visibleState.isIdle) {
        if (hasShown && visibleState.isIdle && !visibleState.currentState) controller.finishDismiss()
    }

    ModalLayer {
        BackHandler { controller.close() }

        // One AnimatedVisibility drives the whole overlay (a single transition off `visibleState`, so isIdle is
        // reliable): the container fades, and the card additionally scales via `animateEnterExit`.
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            val visScope = this
            Box(modifier = Modifier.fillMaxSize().blockPointerInput(controller.isClosing)) {
                // Full-bleed scrim (dims under the system bars too); tap to dismiss.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = ScrimMaxAlpha))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { controller.close() },
                )

                // The card, centered inside the safe area (+ keyboard when applyImePadding), never edge-to-edge.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .then(if (applyImePadding) Modifier.imePadding() else Modifier)
                        .padding(DialogEdgeMargin),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = with(visScope) {
                            Modifier.animateEnterExit(
                                enter = scaleIn(initialScale = 0.92f),
                                exit = scaleOut(targetScale = 0.92f),
                            )
                        }
                            .then(modifier)
                            .widthIn(max = DialogMaxWidth)
                            .fillMaxWidth()
                            .then(if (fixed) Modifier.fillMaxHeight(DialogHeightFraction) else Modifier),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        // The modal layer sits above the shell's MarqueeHost, so a modal provides its own.
                        MarqueeHost {
                            Column(modifier = if (fixed) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                                if (title != null) {
                                    DialogHeader(title, subtitle, leadingAction, trailingActions, titleContent)
                                }
                                DialogBodySlot(
                                    fill = fixed,
                                    scroll = scroll,
                                    // VERTICAL breathing room is the CARD's job, not the content's: unlike the
                                    // bottom sheet — whose bottom edge is the screen edge and which leans on the
                                    // nav-bar/ime inset for this — a centred card floats, and the enclosing Box
                                    // already consumed the system bars, so there is no inset left to inherit
                                    // (content ended up flush against the rounded bottom corners on every
                                    // platform). Horizontal padding stays 0 so list rows still bleed edge to
                                    // edge and their selection highlight spans the full card width.
                                    insets = Modifier.padding(bottom = DialogContentBottomPadding),
                                    controller = controller,
                                    content = content,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Bottom sheet (compact) ──────────────────────────────────────────────────────────────────────────────

/**
 * Drives close + dismissal for the bottom sheet. [onDismiss] fires exactly once across every close path
 * (scrim, back, drag-away, [close]); [close]'s andThen is stored before animating so whichever path reaches
 * Hidden still runs it.
 */
@OptIn(ExperimentalFoundationApi::class)
private class BottomSheetControllerImpl(
    private val state: AnchoredDraggableState<SheetValue>,
    private val scope: CoroutineScope,
    onDismiss: () -> Unit,
) : ClosingDialogController(onDismiss) {
    override fun startClose() {
        scope.launch {
            try {
                state.animateTo(SheetValue.Hidden, CloseSpec)
            } catch (_: CancellationException) {
            }
            // A drag can reverse the exit mid-flight; the sheet is still up, so hand the door back rather
            // than leaving it permanently half-closed and unclosable.
            if (state.targetValue == SheetValue.Hidden) finishDismiss() else abortClose()
        }
    }
}

/**
 * The compact-width bottom sheet, on [AnchoredDraggable] (the primitive under Material's `ModalBottomSheet`).
 * Short content wraps; tall content opens at a half detent and drags or scrolls up to full.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomSheetContainer(
    onDismiss: () -> Unit,
    modifier: Modifier,
    title: String?,
    subtitle: String?,
    leadingAction: HeaderAction?,
    trailingActions: List<HeaderAction>?,
    titleContent: (@Composable () -> Unit)?,
    applyImePadding: Boolean,
    size: AppDialogSize,
    scroll: ScrollOwner,
    content: @Composable ColumnScope.(AppDialogController) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latestDismiss by rememberUpdatedState(onDismiss)
    val state = remember { AnchoredDraggableState(initialValue = SheetValue.Hidden) }
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, animationSpec = SettleSpec)
    val controller = remember(state, scope) { BottomSheetControllerImpl(state, scope) { latestDismiss() } }

    ModalLayer {
        BackHandler { controller.close() }

        val statusTopPx = WindowInsets.statusBars.getTop(density)
        val fixed = size != AppDialogSize.Content
        val imeInsets = WindowInsets.ime
        val imeOpen by remember(imeInsets, density) { derivedStateOf { imeInsets.getBottom(density) > 0 } }
        var containerPx by remember { mutableIntStateOf(0) }
        var contentPx by remember { mutableIntStateOf(0) }
        var anchorsReady by remember { mutableStateOf(false) }
        // Set when an update arrives mid-animation, when anchors cannot be swapped without fighting the
        // spring. Without the retry, content that grows during the open animation keeps the pre-growth
        // height forever.
        var anchorUpdateDeferred by remember { mutableStateOf(false) }
        // True from the moment the sheet first SETTLES on a detent. Until then it is still entering, and
        // anchors may be swapped mid-animation because the entrance re-targets itself (see below); after it,
        // a swap has to wait for the spring or it fights it.
        var hasOpened by remember { mutableStateOf(false) }

        // The surface is ALWAYS the full window height and the content always sits at its top; which detent
        // the sheet rests at is the only thing that decides how much of it you see. Nothing about the sheet's
        // size tracks the content, so content that lands late (async markdown, a streaming trace) can move a
        // detent but can never make the sheet swell.
        val maxHeightPx = (containerPx - statusTopPx).coerceAtLeast(0)

        // Reads its inputs at CALL time, not composition time: it runs from the layout phase (onSizeChanged)
        // as well as from the effects below.
        fun applyAnchors(): AnchorUpdate {
            val maxH = (containerPx - statusTopPx).coerceAtLeast(0)
            val peek = (containerPx * PeekFraction).roundToInt().coerceAtMost(maxH)
            return updateSheetAnchors(state, maxH, contentPx, peek, imeOpen, !hasOpened)
        }

        fun recordAnchorUpdate(result: AnchorUpdate) {
            when (result) {
                AnchorUpdate.Applied -> {
                    anchorsReady = true
                    anchorUpdateDeferred = false
                }
                AnchorUpdate.Deferred -> anchorUpdateDeferred = true
                AnchorUpdate.NotReady -> Unit
            }
        }

        // Keyed on the insets too: they arrive a frame or two late on Android, and an effect keyed only on
        // `state` captured the first (zero) value — leaving the Hidden anchor short of the real sheet height,
        // so the scrim never faded fully out and the sheet never fully left the screen.
        LaunchedEffect(state, statusTopPx, imeOpen, fixed) {
            snapshotFlow { containerPx }.collect { recordAnchorUpdate(applyAnchors()) }
        }

        // The retry half: the moment the sheet settles, apply whatever update the animation turned away.
        LaunchedEffect(state) {
            snapshotFlow { state.settledValue == state.targetValue }.collect { settled ->
                if (settled && anchorUpdateDeferred) recordAnchorUpdate(applyAnchors())
            }
        }

        // The entrance rises to the FIRST detent and keeps re-targeting it until the sheet lands. Content
        // that arrives or grows after the animation started moves that detent, and an animation already in
        // flight would otherwise finish on the stale one — the sheet visibly swelling to full height and
        // then collapsing back, which is the bug this replaced. collectLatest redirects the running
        // animation from wherever it is, so the correction is part of the entrance, not a second animation
        // after it.
        LaunchedEffect(anchorsReady) {
            if (!anchorsReady) return@LaunchedEffect
            snapshotFlow { state.anchors.positionOf(SheetValue.Peek) }
                .takeWhile { !hasOpened && !controller.isClosing }
                .collectLatest {
                    if (!controller.isClosing && state.settledValue == SheetValue.Hidden) {
                        state.animateTo(SheetValue.Peek, SettleSpec)
                    }
                }
        }

        LaunchedEffect(imeOpen, anchorsReady) {
            if (imeOpen && anchorsReady && state.anchors.hasPositionFor(SheetValue.Full) &&
                state.targetValue != SheetValue.Full && state.targetValue != SheetValue.Hidden
            ) {
                state.animateTo(SheetValue.Full, SettleSpec)
            }
        }

        LaunchedEffect(state) {
            snapshotFlow { state.settledValue }.collect {
                if (it != SheetValue.Hidden) hasOpened = true else if (hasOpened) controller.finishDismiss()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .blockPointerInput(controller.isClosing)
                .onSizeChanged { containerPx = it.height },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (state.offset.isNaN()) {
                            0f
                        } else {
                            val hidden = state.anchors.positionOf(SheetValue.Hidden)
                            val shown = state.anchors.positionOf(SheetValue.Peek)
                            ((hidden - state.offset) / (hidden - shown)).coerceIn(0f, 1f) * ScrimMaxAlpha
                        }
                    }
                    .background(MaterialTheme.colorScheme.scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { controller.close() },
            )

            if (containerPx > 0) {
                Surface(
                    modifier = modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = SheetMaxWidth)
                        .fillMaxWidth()
                        .height(with(density) { maxHeightPx.toDp() })
                        .offset { IntOffset(0, if (state.offset.isNaN()) containerPx else state.offset.roundToInt()) }
                        .anchoredDraggable(
                            state,
                            Orientation.Vertical,
                            flingBehavior = flingBehavior,
                        )
                        .nestedScroll(remember(state) { sheetNestedScroll(state) }),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    // Its own host: the modal layer sits above the shell's.
                    MarqueeHost {
                        Column(
                            modifier = Modifier
                                .topAlignedInSheet()
                                .fillMaxWidth()
                                .onSizeChanged {
                                    contentPx = it.height
                                    recordAnchorUpdate(applyAnchors())
                                },
                        ) {
                            BottomSheetDragHandle()
                            if (title != null) {
                                DialogHeader(title, subtitle, leadingAction, trailingActions, titleContent)
                            }
                            DialogBodySlot(
                                fill = fixed,
                                scroll = scroll,
                                insets = Modifier.windowInsetsPadding(
                                    if (applyImePadding) {
                                        WindowInsets.ime.union(WindowInsets.navigationBars)
                                    } else {
                                        WindowInsets.navigationBars
                                    },
                                ),
                                controller = controller,
                                content = content,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Inner scroll ↔ sheet drag: drag up expands the sheet first, then scrolls; drag down at the top collapses. */
@OptIn(ExperimentalFoundationApi::class)
private fun sheetNestedScroll(state: AnchoredDraggableState<SheetValue>): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y))
            else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (available.y < 0 && state.requireOffset() > state.anchors.minPosition()) {
                state.anchors.closestAnchor(state.requireOffset())?.let { state.animateTo(it, SettleSpec) }
                return available
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            state.anchors.closestAnchor(state.requireOffset())?.let { state.animateTo(it, SettleSpec) }
            return available
        }
    }

/**
 * Why an anchor update can fail, and whether the caller should come back. [Deferred] is the interesting
 * one: anchors cannot be swapped while the spring is running without the sheet jumping, so the update is
 * refused — but it still has to land, or content that grew during the open animation keeps the old height.
 */
private enum class AnchorUpdate { Applied, Deferred, NotReady }

/**
 * Recompute the two detents and settle on the right one. [sheetHeightPx] is the surface — always the full
 * window — and [contentPx] is how much of it the content actually occupies, so [SheetValue.Full] stops at the
 * end of the content rather than dragging empty surface into view. Run in the layout phase (onSizeChanged) so
 * a detent applies the same frame the content that moved it grows.
 *
 * [opening] is true while the sheet has never settled. Anchors then apply mid-animation, because during the
 * entrance the alternative — deferring until the spring lands — is exactly what let a sheet finish on a stale
 * anchor and re-snap afterwards; the entrance re-animates to whatever this hands it.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun updateSheetAnchors(
    state: AnchoredDraggableState<SheetValue>,
    sheetHeightPx: Int,
    contentPx: Int,
    peekFractionPx: Int,
    imeOpen: Boolean,
    opening: Boolean,
): AnchorUpdate {
    if (sheetHeightPx <= 0 || contentPx <= 0 || peekFractionPx <= 0) return AnchorUpdate.NotReady
    if (!opening && state.settledValue != state.targetValue) return AnchorUpdate.Deferred
    val h = sheetHeightPx.toFloat()
    val fullPx = contentPx.coerceAtMost(sheetHeightPx)
    val peekPx = fullPx.coerceAtMost(peekFractionPx)
    val anchors = DraggableAnchors {
        SheetValue.Hidden at h
        SheetValue.Peek at h - peekPx
        // Only when there is more to show. A sheet whose content fits in the peek has ONE stop, so an upward
        // drag cannot pull empty surface over the screen.
        if (fullPx > peekPx) SheetValue.Full at h - fullPx
    }
    val target = when {
        // A sheet that is leaving stays leaving. Without this, a keyboard animation racing the close
        // re-targets Full and snaps the dismissed sheet back to full height for a frame. It also covers the
        // frames before the entrance starts, where Hidden is simply where the sheet still is.
        state.targetValue == SheetValue.Hidden -> SheetValue.Hidden
        imeOpen && anchors.hasPositionFor(SheetValue.Full) -> SheetValue.Full
        // Still entering: the entrance owns the target and re-animates to it, so hand it the peek rather than
        // whichever stop the content implied when the animation started.
        opening -> SheetValue.Peek
        !anchors.hasPositionFor(state.targetValue) -> SheetValue.Peek
        else -> state.targetValue
    }
    state.updateAnchors(anchors, target)
    return AnchorUpdate.Applied
}

/**
 * Lets the sheet's content keep its NATURAL height inside a surface that is always the full window: measure
 * with no minimum, report the surface's, place at the top.
 *
 * Both halves earn their keep. Measuring with no minimum is what leaves the content its own size, which is
 * what the detents above are built from. Reporting the minimum is what keeps it top-aligned: a layout that
 * returns less than its min constraint does not win the argument — [androidx.compose.ui.layout.Placeable]
 * coerces the size back up and offsets the real content by half the difference, which is how a short menu
 * ended up floating in the vertical middle of a full-height sheet.
 */
private fun Modifier.topAlignedInSheet(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        layout(placeable.width, maxOf(placeable.height, constraints.minHeight)) { placeable.place(0, 0) }
    }

@Composable
private fun BottomSheetDragHandle() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .padding(top = 12.dp, bottom = 4.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(100))
                .height(4.dp)
                .width(32.dp),
        )
    }
}

/** A leaf sheet's header: its title, or the band content standing in for it. */
@Composable
private fun DialogHeader(
    title: String,
    subtitle: String?,
    leadingAction: HeaderAction?,
    trailingActions: List<HeaderAction>?,
    titleContent: (@Composable () -> Unit)?,
) {
    AppHeader(
        title = title,
        subtitle = subtitle,
        leadingAction = leadingAction,
        trailingActions = trailingActions,
        titleContent = titleContent,
    )
}
