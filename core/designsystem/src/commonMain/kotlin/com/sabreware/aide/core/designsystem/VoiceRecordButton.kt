package com.sabreware.aide.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.domain.speech.MicActivity
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import kotlin.math.PI
import kotlin.math.sin
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

/**
 * A live mic meter: [barCount] rounded bars that rise and fall with the voice.
 *
 * Two distinct states, which is the whole point of the component:
 * - **[speaking] = false** — a slow, shallow travelling ripple. It says "the mic is open, I hear nothing
 *   yet" without claiming to hear a voice. Never frozen: a still meter reads as broken, not as quiet.
 * - **[speaking] = true** — the bars swing over their full range, scaled by [level] (0..1). Two sine
 *   components at different rates keep the motion irregular so it reads as speech, not as a metronome.
 *
 * [speaking] comes from real voice-activity detection (`MicActivityMonitor`), so the big motion plays
 * only while someone is actually talking.
 */
@Composable
fun VoiceBars(
    level: Float,
    speaking: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    color: Color = LocalContentColor.current,
    barWidth: Dp = 3.dp,
    barGap: Dp = 3.dp,
) {
    val transition = rememberInfiniteTransition(label = "voiceBars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(SweepMs, easing = LinearEasing)),
        label = "voiceBarsPhase",
    )

    // Asymmetric envelope — snaps onto a syllable, decays out of it. animateFloatAsState can only do one
    // duration, and a symmetric one either lags the attack or strobes on the release.
    val energy = remember { Animatable(0f) }
    val target = if (speaking) level.coerceIn(0f, 1f).coerceAtLeast(SpeakingFloor) else 0f
    LaunchedEffect(target) {
        val rising = target > energy.value
        energy.animateTo(
            targetValue = target,
            animationSpec = tween(if (rising) AttackMs else ReleaseMs, easing = LinearEasing),
        )
    }

    Canvas(modifier.width(barWidth * barCount + barGap * (barCount - 1))) {
        drawVoiceBars(
            barCount = barCount,
            phase = phase,
            energy = energy.value,
            color = color,
            barWidthPx = barWidth.toPx(),
            barGapPx = barGap.toPx(),
        )
    }
}

/** Bar height at rest, as a fraction of the meter — a dot, not a line. */
private const val MinBarFraction = 0.16f
/** How far the idle ripple lifts a bar above [MinBarFraction]. Visible, but clearly "not speech". */
private const val IdleSwing = 0.14f
/** Floor applied to [level] while speech is detected, so a quiet talker still visibly moves the bars. */
private const val SpeakingFloor = 0.34f
private const val SweepMs = 900
private const val AttackMs = 90
private const val ReleaseMs = 280

private fun DrawScope.drawVoiceBars(
    barCount: Int,
    phase: Float,
    energy: Float,
    color: Color,
    barWidthPx: Float,
    barGapPx: Float,
) {
    val maxHeight = size.height
    val radius = barWidthPx / 2f
    repeat(barCount) { i ->
        // Idle: one slow wave crawling across the bars.
        val idleWave = 0.5f + 0.5f * sin(phase * 0.35f + i * 0.9f)
        val idle = MinBarFraction + IdleSwing * idleWave

        // Speech: two sines at incommensurate rates, plus a centre-weighted profile so the middle bars
        // swing widest — that asymmetry is what makes a bank of bars read as a voice meter.
        val fast = 0.5f + 0.5f * sin(phase + i * 1.9f)
        val slow = 0.5f + 0.5f * sin(phase * 1.7f + i * 0.6f + 0.9f)
        val mix = 0.62f * fast + 0.38f * slow
        val weight = 0.62f + 0.38f * sin(PI.toFloat() * (i + 0.5f) / barCount)
        val swing = (0.32f + 0.68f * mix) * weight

        val fraction = idle + (1f - idle) * energy * swing
        val h = (maxHeight * fraction).coerceIn(barWidthPx, maxHeight)
        drawRoundRect(
            color = color,
            topLeft = Offset(x = i * (barWidthPx + barGapPx), y = (maxHeight - h) / 2f),
            size = Size(barWidthPx, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
    }
}

/**
 * The live mic signal while [active] — level envelope plus the voice-activity verdict.
 *
 * Reads the shared [MicActivityMonitor] rather than any one capturer, so it works for every source: our
 * own AudioRecord/Java Sound pump, Sherpa's Silero VAD, and the Android system recogniser (which owns the
 * mic and publishes through its own callbacks). Emits [MicActivity.Idle] while inactive so meters settle.
 */
@Composable
fun rememberMicActivity(active: Boolean): State<MicActivity> {
    val monitor = koinInject<MicActivityMonitor>()
    return produceState(MicActivity.Idle, active, monitor) {
        if (!active) {
            value = MicActivity.Idle
            return@produceState
        }
        monitor.state.collect { value = it }
    }
}

/**
 * The app's recording button: an icon at rest, an expanding pill with a live [VoiceBars] meter while
 * [recording]. Use it for any start/stop mic affordance so they all look and behave the same.
 *
 * [level]/[speaking] are the meter's inputs — pass [rememberMicActivity]'s value.
 */
@Composable
fun VoiceRecordButton(
    recording: Boolean,
    level: Float,
    speaking: Boolean,
    onClick: () -> Unit,
    iconRes: DrawableResource,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    idleTint: Color = LocalContentColor.current,
) {
    val width by animateDpAsState(
        targetValue = if (recording) size + RecordingWidthGain else size,
        animationSpec = tween(220),
        label = "voiceButtonWidth",
    )
    val container by animateColorAsState(
        targetValue = if (recording) activeColor.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(220),
        label = "voiceButtonContainer",
    )
    Box(
        modifier = modifier
            .width(width)
            .height(size)
            .clip(CircleShape)
            .background(container)
            .hapticClickable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            VoiceBars(
                level = level,
                speaking = speaking,
                modifier = Modifier.height(iconSize),
                color = activeColor,
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = idleTint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** How much wider the button gets while recording — enough for the meter, not enough to reflow the row. */
private val RecordingWidthGain = 24.dp
