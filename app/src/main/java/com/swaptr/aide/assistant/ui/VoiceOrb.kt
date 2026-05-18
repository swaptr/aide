package com.swaptr.aide.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swaptr.aide.domain.speech.VoiceLoopState

@Composable
fun VoiceOrb(state: VoiceLoopState, modifier: Modifier = Modifier, size: Dp = 80.dp) {
    val baseColor = MaterialTheme.colorScheme.primary
    val infinite = rememberInfiniteTransition(label = "orb-pulse")
    val breath by infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-breath",
    )
    val speakingPulse by infinite.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 380, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-speaking",
    )
    val targetScale = when (state) {
        VoiceLoopState.Idle -> 0.7f
        is VoiceLoopState.Listening -> rmsToScale(state.rmsDb)
        is VoiceLoopState.Thinking -> breath
        is VoiceLoopState.Speaking,
        is VoiceLoopState.ToolAnnouncing -> speakingPulse
        is VoiceLoopState.Error -> 0.7f
    }
    val scale by animateFloatAsState(targetValue = targetScale, label = "orb-scale")
    val targetAlpha = when (state) {
        VoiceLoopState.Idle -> 0.35f
        is VoiceLoopState.Error -> 0.4f
        else -> 0.85f
    }
    val alpha by animateFloatAsState(targetValue = targetAlpha, label = "orb-alpha")

    Canvas(modifier = modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val brush = Brush.radialGradient(
            colors = listOf(baseColor.copy(alpha = alpha), baseColor.copy(alpha = 0f)),
            center = center,
            radius = radius * scale,
        )
        drawCircle(brush = brush, radius = radius * scale, center = center)
        drawCircle(
            color = baseColor.copy(alpha = alpha * 0.6f),
            radius = radius * scale * 0.55f,
            center = center,
        )
    }
}

private fun rmsToScale(rmsDb: Float): Float {
    // -50..-10 dB → 0.7..1.2 so quiet rooms still pulse and a loud talker doesn't pin the radius.
    val clamped = rmsDb.coerceIn(-50f, -10f)
    val normalised = (clamped + 50f) / 40f
    return 0.7f + normalised * 0.5f
}

@Suppress("unused")
private val Idle: Color get() = Color.Unspecified
