package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * `clickable` + one consistent tap haptic — the single tap surface for Compose app + assistant-overlay
 * UI, so every on-screen button feels the same (mirrors the IME's per-key haptic; Compose haptics were
 * otherwise absent). The tick is the platform "on-screen button" haptic ([rememberHapticTick]), which
 * already honours the user's touch-feedback setting — and keeps the default ripple indication.
 *
 * Prefer this over a bare `Modifier.clickable` for anything the user taps as a control.
 */
@Composable
fun Modifier.hapticClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
): Modifier {
    val tick = rememberHapticTick()
    return clickable(enabled = enabled, role = role, onClickLabel = onClickLabel) {
        tick()
        onClick()
    }
}

/**
 * Shared circular icon button — a centred icon in a tappable disc, over [hapticClickable]. Replaces the
 * hand-rolled `Box + background + clickable` circles (chat Send/Stop, assistant Mic) so size, haptic and
 * shape are uniform. `containerColor = Transparent` gives a bare icon button; set it for a filled disc.
 * Colour is caller-owned (pass the resting/disabled [tint] yourself); [enabled] only gates the tap.
 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    iconRes: DrawableResource,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    containerColor: Color = Color.Transparent,
    shape: Shape = CircleShape,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor)
            .hapticClickable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
