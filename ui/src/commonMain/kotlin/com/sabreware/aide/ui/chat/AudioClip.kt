package com.sabreware.aide.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.ui.platform.LocalPlatformAffordances
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

/**
 * Compact inline player for a recorded voice clip (a WAV in the durable attachments dir). Play/pause
 * drives one host [AudioClipPlayer][com.sabreware.aide.ui.platform.AudioClipPlayer]; the label shows the
 * live position while playing and the total length otherwise. When [onRemove] is non-null an X is shown —
 * used for the still-draft clip in the composer, before it's sent. This is how a recorded clip stays
 * accessible (and replayable) in the chat after the turn is sent.
 *
 * Draws nothing on a host that provides no player. This is not a degraded player — it is an inline player,
 * and without one there is nothing to render; a host with no audio output also has no capturer, so it never
 * produces the clip in the first place.
 */
@Composable
fun AudioClipChip(
    path: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val players = LocalPlatformAffordances.current.audioClipPlayers ?: return

    var isPlaying by remember(path) { mutableStateOf(false) }
    var durationMs by remember(path) { mutableIntStateOf(0) }
    var positionMs by remember(path) { mutableIntStateOf(0) }
    val player = remember(players) { players.create() }

    // (Re)bind the player to this path. bind() resets any prior source (instance reused);
    // teardown is left to the release effect below so bind() can't run after release().
    DisposableEffect(path) {
        isPlaying = false
        positionMs = 0
        durationMs = 0
        player.bind(
            path,
            onPrepared = { durationMs = it },
            onCompletion = {
                isPlaying = false
                positionMs = 0
            },
        )
        onDispose { }
    }
    // Release the native resources exactly once, when the chip leaves composition.
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player.positionMs()
            delay(200)
        }
    }

    // The remove button is a 44dp touch target (accessibility minimum) around the same 14dp glyph; the
    // pill's end/vertical padding collapses to zero beside it — the button's own inset supplies the
    // spacing — so the pill barely grows.
    val removable = onRemove != null
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50))
            .padding(
                start = 4.dp,
                end = if (removable) 0.dp else 14.dp,
                top = if (removable) 0.dp else 4.dp,
                bottom = if (removable) 0.dp else 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                if (isPlaying) {
                    player.pause()
                    isPlaying = false
                } else {
                    player.play()
                    isPlaying = true
                }
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                painterResource(if (isPlaying) Res.drawable.ic_lc_pause else Res.drawable.ic_lc_play),
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatClipTime(if (isPlaying) positionMs else durationMs),
            // bodyMedium = the sibling FileChip's label style, so the two chips read as the same object.
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
                Icon(
                    painterResource(Res.drawable.ic_lc_x),
                    contentDescription = "Remove audio clip",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** mm:ss for a clip length / position in milliseconds. */
internal fun formatClipTime(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}
