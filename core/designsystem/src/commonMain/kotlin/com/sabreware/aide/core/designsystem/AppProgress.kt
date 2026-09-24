package com.sabreware.aide.core.designsystem

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * THE linear progress bar for the whole app: a plain filled track with no gap and no Material 3
 * "stop indicator" dot at the end. M3's default `LinearProgressIndicator` draws that dot for
 * accessibility contrast on a determinate bar, but it reads as a stray mark once every call site
 * already picks a track color with enough contrast against its fill — so it is switched off HERE,
 * once, rather than left for every call site to notice and disable on its own.
 *
 * [progress] is the deferred-read `() -> Float` overload so a ticking value (a download's byte
 * count) only repaints the draw phase, never recomposes the row around it.
 */
@Composable
fun AppLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
