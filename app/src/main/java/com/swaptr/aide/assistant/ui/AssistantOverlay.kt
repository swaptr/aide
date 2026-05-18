package com.swaptr.aide.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.swaptr.aide.R
import com.swaptr.aide.domain.speech.VoiceLoopState
import kotlinx.coroutines.launch

private const val PILL_WIDTH_FRACTION = 0.95f

private val EXPANDED_PANEL_MAX = 360.dp

// Room for chip + spacer + input row + bottom padding so the surface fits the
// viewport in landscape.
private val RESERVED_TOP_AND_BOTTOM = 200.dp

private val CORNER_PILL = 32.dp
private val CORNER_SHEET = 28.dp

private const val SCRIM_ALPHA = 0.25f

private const val FLING_VELOCITY_THRESHOLD = 500f
private const val SNAP_FRACTION_THRESHOLD = 0.5f
private const val EXPAND_ANIM_MS = 260

private val FractionSaver: Saver<Animatable<Float, AnimationVector1D>, Float> = Saver(
    save = { it.value },
    restore = { Animatable(it) },
)

@Composable
fun AssistantOverlay(
    onDismiss: () -> Unit,
    bottomInsetPx: Int = 0,
    voiceState: VoiceLoopState = VoiceLoopState.Idle,
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

    // Skip auto-expand during Listening — growing the sheet while the user speaks is
    // distracting and can clip mid-utterance partials with chrome.
    LaunchedEffect(voiceState) {
        val shouldExpand = when (voiceState) {
            is VoiceLoopState.Thinking,
            is VoiceLoopState.Speaking,
            is VoiceLoopState.ToolAnnouncing,
            is VoiceLoopState.Error -> true
            is VoiceLoopState.Idle,
            is VoiceLoopState.Listening -> false
        }
        if (shouldExpand && fraction.value < 1f) {
            fraction.animateTo(1f, tween(durationMillis = EXPAND_ANIM_MS))
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = overlayLabel }
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = dismissLabel,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
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
    }
}

@Composable
private fun MorphingSurface(
    fractionProvider: () -> Float,
    maxConversationHeight: Dp,
    voiceState: VoiceLoopState,
    onTapMic: () -> Unit,
) {
    val f = fractionProvider().coerceIn(0f, 1f)
    val conversationHeight = maxConversationHeight * f
    val cornerRadius = lerp(CORNER_PILL, CORNER_SHEET, f)
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(PILL_WIDTH_FRACTION)
                .shadow(elevation = 3.dp, shape = shape, clip = false)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = shape,
                ),
        ) {
            ConversationArea(
                height = conversationHeight,
                contentAlpha = ((f - 0.4f) / 0.6f).coerceIn(0f, 1f),
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
    height: Dp,
    contentAlpha: Float,
    content: @Composable BoxScope.() -> Unit = {
        Text(
            text = stringResource(R.string.assistant_conversation_placeholder),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.alpha(contentAlpha),
        )
    },
) {
    if (height.value <= 0f) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun InputRow(voiceState: VoiceLoopState, onTapMic: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillGlyph(R.drawable.ic_lc_plus, size = 20.dp)
        PillGlyph(R.drawable.ic_lc_menu, size = 18.dp)
        Text(
            text = voiceStateHint(voiceState),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp),
        )
        MicButton(state = voiceState, onTap = onTapMic)
        SparkleButton()
    }
}

@Composable
private fun voiceStateHint(state: VoiceLoopState): String = when (state) {
    VoiceLoopState.Idle -> stringResource(R.string.assistant_input_hint)
    is VoiceLoopState.Listening -> "Listening…"
    is VoiceLoopState.Thinking -> "Thinking…"
    is VoiceLoopState.Speaking -> "Speaking…"
    is VoiceLoopState.ToolAnnouncing -> state.summary
    is VoiceLoopState.Error -> "Error: ${state.message}"
}

@Composable
private fun VoiceConversationContent(state: VoiceLoopState) {
    val text = when (state) {
        VoiceLoopState.Idle -> stringResource(R.string.assistant_conversation_placeholder)
        is VoiceLoopState.Listening -> state.partial.ifBlank { "Go ahead…" }
        is VoiceLoopState.Thinking -> state.assistantBuffer.ifBlank { "Thinking…" }
        is VoiceLoopState.Speaking -> state.sentenceInFlight
        is VoiceLoopState.ToolAnnouncing -> state.summary
        is VoiceLoopState.Error -> state.message
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SetupModelChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .shadow(elevation = 4.dp, shape = shape, clip = false)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lc_sparkles),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.assistant_setup_model),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// Touch targets intentionally NOT enforced — minimumInteractiveComponentSize() would
// inflate the pill height. Restore 48 dp targets when glyphs become real actions.
@Composable
private fun PillGlyph(resId: Int, size: Dp) {
    Icon(
        painter = painterResource(resId),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun MicButton(state: VoiceLoopState, onTap: () -> Unit) {
    val active = state is VoiceLoopState.Listening ||
                 state is VoiceLoopState.Thinking ||
                 state is VoiceLoopState.Speaking ||
                 state is VoiceLoopState.ToolAnnouncing
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
             else Color.Transparent
    val tint = if (active) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lc_mic),
            contentDescription = stringResource(R.string.assistant_voice_input),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SparkleButton() {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lc_sparkles),
            contentDescription = stringResource(R.string.assistant_actions),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}
