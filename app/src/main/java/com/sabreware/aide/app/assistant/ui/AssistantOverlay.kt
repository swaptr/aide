package com.sabreware.aide.app.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sabreware.aide.app.speech.VoiceLoopState
import com.sabreware.aide.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val EXPANDED_PANEL_MAX = 360.dp

// Room for chip + spacer + input row + bottom padding so the surface fits the
// viewport in landscape.
private val RESERVED_TOP_AND_BOTTOM = 200.dp

// Scrim is a bottom-weighted gradient: the top stays see-through so the underlying
// screen reads clearly, and only the lower portion darkens enough to lift the pill.
private const val SCRIM_BOTTOM_ALPHA = 0.18f
private const val SCRIM_FADE_START = 0.75f

private const val ENTER_MS = 300
private val APPEAR_SLIDE = 140.dp

private const val FLING_VELOCITY_THRESHOLD = 500f
private const val SNAP_FRACTION_THRESHOLD = 0.5f
private const val EXPAND_ANIM_MS = 260

private val FractionSaver: Saver<Animatable<Float, AnimationVector1D>, Float> = Saver(
    save = { it.value },
    restore = { Animatable(it) },
)

@Composable
fun AssistantOverlay(
    voiceState: State<VoiceLoopState>,
    onDismiss: () -> Unit,
    showEpoch: Int = 0,
    bottomInsetPx: Int = 0,
    onTapMic: () -> Unit = {},
    needsModelSetup: Boolean = false,
    onOpenModels: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val fraction = rememberSaveable(saver = FractionSaver) { Animatable(0f) }
    val density = LocalDensity.current
    val bottomInsetDp = with(density) { bottomInsetPx.toDp() }
    val overlayLabel = stringResource(R.string.assistant_overlay_label)
    val dismissLabel = stringResource(R.string.assistant_overlay_dismiss)

    // On dark UIs a white fade lifts the pill off the background; on light UIs a black
    // fade does the same. Either way the top stays fully transparent. remember-ed so the
    // brush isn't reallocated on every (high-frequency) voiceState recomposition.
    val scrimTint = if (isSystemInDarkTheme()) Color.White else Color.Black
    val scrimBrush = remember(scrimTint) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                SCRIM_FADE_START to Color.Transparent,
                1f to scrimTint.copy(alpha = SCRIM_BOTTOM_ALPHA),
            ),
        )
    }

    // Enter slide. `showEpoch` increments on each onShow; keying the Animatable on it
    // recreates `appear` at 0 for every show, so the first painted frame is always hidden
    // (no at-rest flash from a value retained across the reused composition). epoch 0 is
    // the onPrepareShow warm-up — don't animate then, just stay hidden. Exit stays the
    // system window animation.
    val appear = remember(showEpoch) { Animatable(0f) }
    LaunchedEffect(showEpoch) {
        if (showEpoch > 0) {
            appear.animateTo(1f, tween(durationMillis = ENTER_MS, easing = FastOutSlowInEasing))
        }
    }
    val slidePx = with(density) { APPEAR_SLIDE.toPx() }

    // Auto-expand on Thinking/Speaking/Tool/Error, but not Listening (growing the sheet
    // mid-utterance is distracting). Read the state via snapshotFlow so this reacts to
    // changes WITHOUT recomposing the overlay — `voiceState.value` is deliberately not
    // read anywhere in this composable's body, only in the leaves that need it.
    LaunchedEffect(Unit) {
        snapshotFlow { shouldExpand(voiceState.value) }
            .distinctUntilChanged()
            .collect { expand ->
                if (expand && fraction.value < 1f) {
                    fraction.animateTo(1f, tween(durationMillis = EXPAND_ANIM_MS))
                }
            }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = overlayLabel }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = dismissLabel,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value }
                .background(scrimBrush),
        )

        val maxConversationDp =
            (maxHeight - RESERVED_TOP_AND_BOTTOM - bottomInsetDp).coerceAtLeast(0.dp)
        val targetMaxDp = EXPANDED_PANEL_MAX.coerceAtMost(maxConversationDp)
        val maxDragPx = with(density) {
            targetMaxDp.toPx().coerceAtLeast(1f)
        }

        val dragState = rememberDraggableState { dy ->
            scope.launch {
                val next = (fraction.value - dy / maxDragPx).coerceIn(0f, 1f)
                fraction.snapTo(next)
            }
        }
        val onDragStopped: (Float) -> Unit = { velocity ->
            val target = when {
                velocity < -FLING_VELOCITY_THRESHOLD -> 1f
                velocity > FLING_VELOCITY_THRESHOLD -> 0f
                fraction.value > SNAP_FRACTION_THRESHOLD -> 1f
                else -> 0f
            }
            scope.launch {
                fraction.animateTo(target, tween(durationMillis = EXPAND_ANIM_MS))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = (1f - appear.value) * slidePx
                    alpha = appear.value
                }
                .padding(top = 16.dp, bottom = bottomInsetDp + 24.dp)
                .pointerInput(Unit) { detectTapGestures {} }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { onDragStopped(it) },
                ),
            horizontalAlignment = Alignment.Start,
        ) {
            if (needsModelSetup) {
                Box(modifier = Modifier.padding(start = 12.dp)) {
                    SetupModelChip(onClick = onOpenModels)
                }
                Spacer(Modifier.height(10.dp))
            }
            MorphingSurface(
                fractionProvider = { fraction.value },
                maxConversationHeight = targetMaxDp,
                voiceState = voiceState,
                onTapMic = onTapMic,
            )
        }

        // Mask the thin light frame the session window draws at the screen edge: a 1.5dp
        // edge stroke matching the theme (black on dark, white on light) sits on top of
        // everything, so the stray perimeter line reads as the background instead.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(width = 1.5.dp, color = if (isSystemInDarkTheme()) Color.Black else Color.White),
        )
    }
}
