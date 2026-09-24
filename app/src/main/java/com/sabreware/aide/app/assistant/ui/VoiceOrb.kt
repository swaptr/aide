package com.sabreware.aide.app.assistant.ui

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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sabreware.aide.app.speech.VoiceLoopState

@Composable
fun VoiceOrb(state: State<VoiceLoopState>, modifier: Modifier = Modifier, size: Dp = 80.dp) {
    val s = state.value
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
    // Base scale per state, smoothed for state transitions. Deliberately does NOT read the
    // infinite `breath`/`speakingPulse` here — those are applied in the draw phase below, so
    // the composition isn't re-run every frame while thinking/speaking.
    val baseTarget = when (s) {
        VoiceLoopState.Idle -> 0.7f
        is VoiceLoopState.Listening -> rmsToScale(s.rmsDb)
        is VoiceLoopState.Thinking,
        is VoiceLoopState.Speaking,
        is VoiceLoopState.ToolAnnouncing -> 1.0f
        is VoiceLoopState.Error -> 0.7f
    }
    val baseScale by animateFloatAsState(targetValue = baseTarget, label = "orb-scale")
    val targetAlpha = when (s) {
        VoiceLoopState.Idle -> 0.35f
        is VoiceLoopState.Error -> 0.4f
        else -> 0.85f
    }
    val alpha by animateFloatAsState(targetValue = targetAlpha, label = "orb-alpha")

    Canvas(modifier = modifier.size(size)) {
        // Pulse is read only here (draw phase) — re-draws each frame, no recomposition.
        val pulse = when (s) {
            is VoiceLoopState.Thinking -> breath
            is VoiceLoopState.Speaking,
            is VoiceLoopState.ToolAnnouncing -> speakingPulse
            else -> 1f
        }
        val scale = baseScale * pulse
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
