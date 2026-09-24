package com.sabreware.aide.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.resources.*
import org.jetbrains.compose.resources.painterResource

/**
 * THE app pull-to-refresh host — Material's `PullToRefreshBox` mechanics with the app's own indicator:
 * the Lucide `rotate-cw` glyph (matching every other `ic_lc_*` icon) in an elevated disc. Pulling winds
 * the arrow with the drag; refreshing spins it continuously. Use this for every refreshable list so the
 * gesture looks identical app-wide — never the stock `PullToRefreshBox` indicator directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
        indicator = { AppRefreshIndicator(state = state, isRefreshing = isRefreshing) },
        content = content,
    )
}

private val IndicatorSize = 40.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.AppRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
) {
    val thresholdPx = with(LocalDensity.current) { PullToRefreshDefaults.PositionalThreshold.toPx() }
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .size(IndicatorSize)
            .graphicsLayer {
                // Stock indicator's positioning math: parked above the edge at rest, riding the drag
                // down; PullToRefreshBox animates distanceFraction to 1 while refreshing and back to 0
                // on completion, so this also covers the settle/dismiss motion.
                val progress = state.distanceFraction
                translationY = progress * thresholdPx - size.height
                alpha = if (progress > 0f) 1f else 0f
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val angle: Float
            if (isRefreshing) {
                val spin = rememberInfiniteTransition(label = "refreshSpin")
                val spinAngle by spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                    label = "refreshSpinAngle",
                )
                angle = spinAngle
            } else {
                // Wind up with the pull; keeps turning on over-pull so the gesture always feels live.
                angle = state.distanceFraction * 240f
            }
            Icon(
                painter = painterResource(Res.drawable.ic_lc_rotate_cw),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = angle },
            )
        }
    }
}
