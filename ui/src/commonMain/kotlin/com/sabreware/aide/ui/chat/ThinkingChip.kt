package com.sabreware.aide.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AidePill
import com.sabreware.aide.core.designsystem.resources.*
import org.jetbrains.compose.resources.painterResource

@Composable
fun ThinkingChip(
    isStreaming: Boolean,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelTone = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f)
    val iconTone = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
    val chevronTone = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)

    AidePill(
        onClick = onClick,
        modifier = modifier,
        contentColor = labelTone,
        leading = {
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = iconTone,
                    )
                } else {
                    Icon(
                        painter = painterResource(Res.drawable.ic_lc_sparkles),
                        contentDescription = null,
                        tint = iconTone,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        trailing = {
            Icon(
                painter = painterResource(Res.drawable.ic_lc_chevron_right),
                contentDescription = "Show thinking",
                tint = chevronTone,
                modifier = Modifier.size(16.dp),
            )
        },
    ) {
        Text(
            text = if (isStreaming) "Thinking…" else "Thought for ${formatThinkingDuration(durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = labelTone,
        )
    }
}

internal fun formatThinkingDuration(durationMs: Long): String {
    val totalSeconds = ((durationMs + 999L) / 1000L).coerceAtLeast(1L)
    if (totalSeconds < 60L) return "${totalSeconds}s"
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (seconds == 0L) "${minutes}m" else "${minutes}m ${seconds}s"
}
