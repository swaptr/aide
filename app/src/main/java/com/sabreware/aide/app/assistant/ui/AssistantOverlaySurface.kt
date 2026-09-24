package com.sabreware.aide.app.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.sabreware.aide.app.speech.VoiceLoopState
import com.sabreware.aide.app.R
import kotlin.math.roundToInt

private const val PILL_WIDTH_FRACTION = 0.95f

private val CORNER_PILL = 32.dp
private val CORNER_SHEET = 28.dp

// Auto-expand the sheet for these states; Listening/Idle stay collapsed.
internal fun shouldExpand(state: VoiceLoopState): Boolean = when (state) {
    is VoiceLoopState.Thinking,
    is VoiceLoopState.Speaking,
    is VoiceLoopState.ToolAnnouncing,
    is VoiceLoopState.Error -> true
    is VoiceLoopState.Idle,
    is VoiceLoopState.Listening -> false
}

@Composable
internal fun MorphingSurface(
    fractionProvider: () -> Float,
    maxConversationHeight: Dp,
    // Forwarded to the leaves only — never read here, so the surface shell doesn't
    // recompose on high-frequency voiceState emissions.
    voiceState: State<VoiceLoopState>,
    onTapMic: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // Corner radius, clip, shadow and the fill/border all read `fraction` in the
            // draw phase (graphicsLayer + drawBehind), so the expand animation re-draws
            // without recomposing this subtree.
            modifier = Modifier
                .fillMaxWidth(PILL_WIDTH_FRACTION)
                .graphicsLayer {
                    val f = fractionProvider().coerceIn(0f, 1f)
                    shape = RoundedCornerShape(lerp(CORNER_PILL, CORNER_SHEET, f))
                    clip = true
                    shadowElevation = 3.dp.toPx()
                }
                .drawBehind {
                    val f = fractionProvider().coerceIn(0f, 1f)
                    val c = lerp(CORNER_PILL, CORNER_SHEET, f).toPx()
                    val cr = CornerRadius(c, c)
                    drawRoundRect(color = surfaceColor, cornerRadius = cr)
                    drawRoundRect(
                        color = borderColor,
                        cornerRadius = cr,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        ) {
            ConversationArea(
                maxHeight = maxConversationHeight,
                fractionProvider = fractionProvider,
                content = {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        VoiceOrb(state = voiceState)
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier.height(12.dp),
                        )
                        VoiceConversationContent(voiceState)
                    }
                },
            )
            InputRow(voiceState = voiceState, onTapMic = onTapMic)
        }
    }
}

@Composable
private fun ConversationArea(
    maxHeight: Dp,
    fractionProvider: () -> Float,
    content: @Composable BoxScope.() -> Unit = {
        Text(
            text = stringResource(R.string.assistant_conversation_placeholder),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    },
) {
    val maxPx = with(LocalDensity.current) { maxHeight.toPx() }
    Box(
        // Height (fraction * max) resolves in the measure phase and content alpha in the
        // draw phase, so growing the panel re-measures/re-draws without recomposing.
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val h = (maxPx * fractionProvider().coerceIn(0f, 1f)).roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(placeable.width, h) { placeable.place(0, 0) }
            }
            .graphicsLayer { alpha = ((fractionProvider() - 0.4f) / 0.6f).coerceIn(0f, 1f) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
internal fun voiceStateHint(state: VoiceLoopState): String = when (state) {
    VoiceLoopState.Idle -> stringResource(R.string.assistant_input_hint)
    is VoiceLoopState.Listening -> "Listening…"
    is VoiceLoopState.Thinking -> "Thinking…"
    is VoiceLoopState.Speaking -> "Speaking…"
    is VoiceLoopState.ToolAnnouncing -> state.summary
    is VoiceLoopState.Error -> "Error: ${state.message}"
}

@Composable
private fun VoiceConversationContent(state: State<VoiceLoopState>) {
    val text = when (val s = state.value) {
        VoiceLoopState.Idle -> stringResource(R.string.assistant_conversation_placeholder)
        is VoiceLoopState.Listening -> s.partial.ifBlank { "Go ahead…" }
        is VoiceLoopState.Thinking -> s.assistantBuffer.ifBlank { "Thinking…" }
        is VoiceLoopState.Speaking -> s.sentenceInFlight
        is VoiceLoopState.ToolAnnouncing -> s.summary
        is VoiceLoopState.Error -> s.message
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
}
