package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Scrolls any one-line content past its slot when it does not fit — text, or a row of icon + text.
 *
 * - Fits → nothing happens; no timer, no coroutine.
 * - Cut off and fully visible (inside every clipping ancestor and the window) → after [MarqueeSettleMs] plus a
 *   per-node stagger, loops endlessly: the content and a copy [MarqueeGap] behind it slide one width + gap at
 *   [MarqueeSpeed], then it rests [MarqueeRestMs] at its normal (ellipsised) look.
 * - Leaves view, unplaced, detached or covered ([CoverMarquees]) → that node's loop is cancelled; others are
 *   untouched. Scrolling or a moving sheet never pauses rows that stay fully in view. Back in view → its clock
 *   restarts.
 * - Hover/focus on the enclosing surface ([LocalMarqueeAttention]) shortens the first wait to [MarqueeDwellMs].
 * - Content that wraps to more lines is never scrolled. Needs a [MarqueeHost] ancestor.
 *
 * Per frame: one float read in this node's draw; the content's recorded layer is replayed twice, clipped to
 * the slot. All work runs in the node's coroutine scope, cancelled on detach.
 */
fun Modifier.marquee(): Modifier = this then MarqueeElement

/** One-line, ellipsised [Text] with [marquee]. The default for every title and subtitle. */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier.marquee(),
        color = color,
        style = style,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Enables [marquee] for everything beneath it. Provided by the app shell root and by every [AppDialog]. */
@Composable
fun MarqueeHost(content: @Composable () -> Unit) {
    val gate = remember { MarqueeGate() }
    CompositionLocalProvider(LocalMarqueeGate provides gate, content = content)
}

/** Pauses the enclosing host's marquees while composed — for a modal drawn over the page. */
@Composable
fun CoverMarquees() {
    val gate = LocalMarqueeGate.current ?: return
    DisposableEffect(gate) {
        gate.cover(true)
        onDispose { gate.cover(false) }
    }
}

/** Per-host pause state ([cover]) and the registered cut-off nodes (fully visible, or placed while paused). */
@Stable
class MarqueeGate internal constructor() {
    private val nodes = LinkedHashSet<MarqueeNode>()

    /** True while a modal covers this host's page; no walk runs then. */
    internal var moving = false
        private set

    private var covers = 0

    /** How many names are registered right now: overflowing, attached, placed and fully in view. */
    internal val registered: Int get() = nodes.size

    /** A modal over this host's page appears ([covered] = true) or leaves; they nest. */
    fun cover(covered: Boolean) {
        covers = (covers + if (covered) 1 else -1).coerceAtLeast(0)
        publish()
    }

    internal fun register(node: MarqueeNode) {
        nodes += node
    }

    internal fun unregister(node: MarqueeNode) {
        nodes -= node
    }

    private fun publish() {
        val now = covers > 0
        if (now == moving) return
        moving = now
        // A copy: a node told to re-decide may unregister itself.
        nodes.toList().forEach { it.update() }
    }
}

/** The nearest [MarqueeHost]'s gate; null outside one, where names never walk. */
val LocalMarqueeGate = staticCompositionLocalOf<MarqueeGate?> { null }

/** The interaction source of the surface a marquee sits on; its hover/focus shortens the first wait. */
val LocalMarqueeAttention = staticCompositionLocalOf<InteractionSource?> { null }

/** Stateless: one instance serves every node. */
private data object MarqueeElement : ModifierNodeElement<MarqueeNode>() {
    override fun create() = MarqueeNode()
    override fun update(node: MarqueeNode) = Unit
}

/** One per marquee: overflow detection, visibility, the loop and its cleanup. */
internal class MarqueeNode :
    Modifier.Node(),
    LayoutModifierNode,
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    UnplacedAwareModifierNode,
    CompositionLocalConsumerModifierNode {

    private var gate: MarqueeGate? = null
    private var attention: InteractionSource? = null

    /** Whether the text overruns its slot on one line — the only state in which anything else happens. */
    private var overflowing = false

    /** The whole text's single-line width in px, cached from the last measure. */
    private var contentWidth = 0

    /** The slot's width in px while walking — what the walk is clipped to. */
    private var slotWidth = 0
    private var gapPx = 0f
    private var speedPx = 0f
    private var rtl = false

    private var placed = false
    private var registered = false
    private var attended = false

    /** This name's offset into its first settle — constant per node, so neighbours start at different moments. */
    private val stagger = (hashCode() and Int.MAX_VALUE) % MarqueeStaggerMs
    private var walking = false
    private var job: Job? = null
    private var attentionJob: Job? = null

    /** The travel of the current pass in px — the one value that changes per frame, read only in [draw]. */
    private val offset = mutableFloatStateOf(0f)

    override val shouldAutoInvalidate = false

    override fun onAttach() {
        gate = currentValueOf(LocalMarqueeGate)
        attention = currentValueOf(LocalMarqueeAttention)
        with(currentValueOf(LocalDensity)) {
            gapPx = MarqueeGap.toPx()
            speedPx = MarqueeSpeed.toPx()
        }
    }

    override fun onDetach() {
        // The framework has cancelled the scope; drop every reference and every registration with it.
        gate?.unregister(this)
        gate = null
        attention = null
        job = null
        attentionJob = null
        walking = false
        offset.floatValue = 0f
        overflowing = false
        registered = false
        placed = false
        attended = false
        contentWidth = 0
        slotWidth = 0
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        rtl = layoutDirection == LayoutDirection.Rtl
        if (walking) {
            // Laid out whole on one line; the slot stays the width it was.
            val whole = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
            contentWidth = whole.width
            slotWidth = constraints.constrainWidth(whole.width)
            return layout(slotWidth, whole.height) {
                // At the origin, in its own layer, so a frame replays the recorded text rather than re-drawing
                // it. RTL's end-alignment is a draw translation, so the content and this node share one origin
                // and the clip in [draw] is the slot in either space.
                whole.placeWithLayer(0, 0)
            }
        }
        var overruns = false
        if (gate != null && constraints.hasBoundedWidth) {
            // Cached by the text itself: reading it costs nothing a fitting row notices.
            val full = measurable.maxIntrinsicWidth(Constraints.Infinity)
            if (full > constraints.maxWidth) {
                // A paragraph (the text wraps to more lines at this width) is never walked.
                val oneLine = measurable.minIntrinsicHeight(Constraints.Infinity)
                val here = measurable.minIntrinsicHeight(constraints.maxWidth)
                overruns = here <= oneLine
                contentWidth = full
            }
        }
        val placeable = measurable.measure(constraints)
        if (overruns != overflowing) {
            overflowing = overruns
            if (!overruns) {
                stop()
                setRegistered(false)
                attentionJob?.cancel()
                attentionJob = null
                attended = false
            } else {
                watchAttention()
            }
        }
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun ContentDrawScope.draw() {
        if (!walking) {
            drawContent()
            return
        }
        // Content + its copy one width + gap behind, shifted by [offset], clipped to the slot. The clip is the
        // slot's recorded width, never `size`: the content overhangs the slot while walking, and a clip derived
        // from the drawn size let the walk run past the slot's end edge into its padding.
        val shift = contentWidth + gapPx
        val start = if (rtl) (slotWidth - contentWidth).toFloat() else 0f
        val dx = start + if (rtl) offset.floatValue else -offset.floatValue
        val w = slotWidth.toFloat()
        // Soft edges while walking: text slides in and out through a short fade rather than being sliced mid-glyph
        // at the slot's edge — the hard cut is what made a walking title look broken. Offscreen layer so the
        // DstIn gradients mask only this text, never what is behind it.
        val fade = minOf(MarqueeFade.toPx(), w / 4f)
        val bounds = Rect(0f, 0f, w, size.height)
        drawIntoCanvas { it.saveLayer(bounds, Paint()) }
        clipRect(left = 0f, top = 0f, right = w, bottom = size.height) {
            translate(left = dx) {
                this@draw.drawContent()
                translate(left = if (rtl) -shift else shift) { this@draw.drawContent() }
            }
        }
        drawRect(
            brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade),
            topLeft = Offset.Zero,
            size = Size(fade, size.height),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            brush = Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = w - fade, endX = w),
            topLeft = Offset(w - fade, 0f),
            size = Size(fade, size.height),
            blendMode = BlendMode.DstIn,
        )
        drawIntoCanvas { it.restore() }
    }

    // Re-checks visibility on every move, so each node's clock starts when it enters view and dies when it leaves.
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!overflowing) return
        placed = true
        update(coordinates)
    }

    override fun onUnplaced() {
        placed = false
        if (!overflowing) return
        stop()
        setRegistered(false)
    }

    /** Paused or not fully visible → stop and unregister; otherwise register and start the loop if idle. */
    fun update(coordinates: LayoutCoordinates? = null) {
        val gate = gate
        if (!isAttached || gate == null || !overflowing) return
        if (gate.moving) {
            // Paused (sheet opening/dragged, modal over it): stop, but stay registered so the resume reaches it —
            // a settled sheet moves nothing, so no position change would re-check it.
            stop()
            if (placed) setRegistered(true)
            return
        }
        val visible = placed && fullyVisible(coordinates ?: requireLayoutCoordinates())
        if (!visible) {
            stop()
            setRegistered(false)
            return
        }
        setRegistered(true)
        if (job?.isActive == true) return
        if (coroutineScope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return
        job = coroutineScope.launch {
            delay(if (attended) MarqueeDwellMs else MarqueeSettleMs + stagger)
            loop()
        }
    }

    /**
     * Endless while in view — Museo's loop. A pass slides the name and its copy by name-plus-gap, which lands the
     * copy exactly where the name began; the name then rests as its ordinary ellipsised self for [MarqueeRestMs]
     * (a plain delay: no frames), and the next pass starts. Entering and leaving the whole-line layout is one
     * relayout of this one text per pass; every frame in between is a draw-phase float write.
     */
    private suspend fun loop() {
        try {
            while (true) {
                // Re-read per pass: the content may have changed (a live download line) since the last one.
                val distance = contentWidth + gapPx
                if (!overflowing || distance <= 0f || speedPx <= 0f) return
                val durationNanos = distance / speedPx * NanosPerSecond
                setWalking(true)
                val start = withFrameNanos(FrameTime)
                var fraction = 0f
                while (fraction < 1f) {
                    val now = withFrameNanos(FrameTime)
                    fraction = ((now - start) / durationNanos).coerceAtMost(1f)
                    // Read only in draw: this node's draw is the only thing invalidated per frame.
                    offset.floatValue = fraction * distance
                }
                setWalking(false)
                delay(MarqueeRestMs)
            }
        } finally {
            if (walking) setWalking(false)
        }
    }

    private fun setWalking(now: Boolean) {
        walking = now
        offset.floatValue = 0f
        if (isAttached) {
            invalidateMeasurement()
            invalidateDraw()
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        if (walking) setWalking(false)
    }

    private fun setRegistered(now: Boolean) {
        if (now == registered) return
        registered = now
        if (now) gate?.register(this) else gate?.unregister(this)
    }

    private fun watchAttention() {
        val source = attention ?: return
        if (attentionJob?.isActive == true) return
        attentionJob = coroutineScope.launch {
            var hovers = 0
            var focused = false
            source.interactions.collect {
                when (it) {
                    is HoverInteraction.Enter -> hovers++
                    is HoverInteraction.Exit -> hovers = (hovers - 1).coerceAtLeast(0)
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                }
                attend(hovers > 0 || focused)
            }
        }
    }

    private fun attend(now: Boolean) {
        if (now == attended) return
        attended = now
        // Attention only shortens the wait before the first pass; a loop already running carries on.
        if (!walking) {
            job?.cancel()
            job = null
        }
        update()
    }

    private fun fullyVisible(coordinates: LayoutCoordinates): Boolean {
        if (!coordinates.isAttached) return false
        // Clipped by every clipping ancestor (a list viewport, a pager, a sheet body) and by the window.
        val bounds = coordinates.boundsInWindow()
        val size = coordinates.size
        return size.width > 0 && bounds.width >= size.width - SlackPx && bounds.height >= size.height - SlackPx
    }
}

/** Time fully in view before the first pass. */
internal const val MarqueeSettleMs = 1200L

/** First wait under hover/focus. */
internal const val MarqueeDwellMs = 600L

/** How far a walking text fades in and out at the slot's edges. */
private val MarqueeFade = 16.dp

/** Max per-node offset added to the first wait. */
internal const val MarqueeStaggerMs = 1500L

/** Rest between passes. */
internal const val MarqueeRestMs = 3000L

/** Space between the content and its copy. */
internal val MarqueeGap = 56.dp

/** Scroll speed per second. */
internal val MarqueeSpeed = 32.dp

private const val NanosPerSecond = 1_000_000_000f
private const val SlackPx = 0.5f

/** Hoisted so a frame's wait allocates no lambda. */
private val FrameTime: (Long) -> Long = { it }
