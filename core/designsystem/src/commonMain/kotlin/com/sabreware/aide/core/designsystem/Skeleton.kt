package com.sabreware.aide.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * Shared shimmer driver for [skeleton] placeholders. Create one per loading surface with
 * [rememberSkeletonShimmer] and pass it to every [Modifier.skeleton] on that surface so they sweep in phase.
 * Honors the system "remove animations" setting — when motion is off, [animated] is false and skeletons render
 * as static blocks (no infinite animation observed in the draw phase).
 */
@Stable
class SkeletonShimmer internal constructor(
    internal val progress: State<Float>,
    internal val animated: Boolean,
)

@Composable
fun rememberSkeletonShimmer(periodMillis: Int = 1200): SkeletonShimmer {
    val durationScale = animatorDurationScale()
    val animated = remember(durationScale) { durationScale != 0f }
    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
        label = "skeleton-progress",
    )
    return remember(animated, progress) { SkeletonShimmer(progress, animated) }
}

/**
 * Renders the node as a shimmering placeholder block when [visible]. The block is theme-colored (a faint
 * `onSurface` wash with a brighter travelling band) and fills whatever bounds the caller gives the node — so a
 * skeleton "conforms to the UI" simply by tagging real-sized boxes with it. The block is decorative, so it's
 * cleared from the accessibility tree.
 */
fun Modifier.skeleton(
    visible: Boolean,
    shimmer: SkeletonShimmer,
    shape: Shape = RoundedCornerShape(6.dp),
): Modifier = if (!visible) this else composed {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    if (!shimmer.animated) {
        clip(shape).background(base).clearAndSetSemantics {}
    } else {
        clip(shape)
            .drawBehind {
                val w = size.width
                val x = shimmer.progress.value * 2f * w - w
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = Offset(x, 0f),
                        end = Offset(x + w, 0f),
                    ),
                )
            }
            .clearAndSetSemantics {}
    }
}
