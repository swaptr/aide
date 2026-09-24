package com.sabreware.aide.app.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppIconButton
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.app.speech.VoiceLoopState
import com.sabreware.aide.app.R
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun InputRow(voiceState: State<VoiceLoopState>, onTapMic: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillGlyph(Res.drawable.ic_lc_plus, size = 20.dp)
        PillGlyph(Res.drawable.ic_lc_menu, size = 18.dp)
        Text(
            text = voiceStateHint(voiceState.value),
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
internal fun SetupModelChip(onClick: () -> Unit) {
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
            painter = painterResource(Res.drawable.ic_lc_sparkles),
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
private fun PillGlyph(resId: DrawableResource, size: Dp) {
    Icon(
        painter = painterResource(resId),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun MicButton(state: State<VoiceLoopState>, onTap: () -> Unit) {
    // derivedStateOf so the button only recomposes when the active/idle distinction flips,
    // not on every Listening rms/partial emission.
    val active by remember {
        derivedStateOf {
            val s = state.value
            s is VoiceLoopState.Listening ||
                s is VoiceLoopState.Thinking ||
                s is VoiceLoopState.Speaking ||
                s is VoiceLoopState.ToolAnnouncing
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val bg = remember(active, primary) {
        if (active) primary.copy(alpha = 0.18f) else Color.Transparent
    }
    val tint = if (active) primary else onSurface
    AppIconButton(
        onClick = onTap,
        iconRes = Res.drawable.ic_lc_mic,
        contentDescription = stringResource(R.string.assistant_voice_input),
        size = 34.dp,
        iconSize = 22.dp,
        tint = tint,
        containerColor = bg,
    )
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
            painter = painterResource(Res.drawable.ic_lc_sparkles),
            // Decorative until it's a real action — otherwise TalkBack announces a button that
            // can't be activated (no clickable). Wire onClick + restore the label when it acts.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}
