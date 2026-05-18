package com.swaptr.aide.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.swaptr.aide.R
import com.swaptr.aide.ui.common.AppSheet

@Composable
fun ThinkingDetailSheet(
    text: String,
    durationMs: Long,
    isStreaming: Boolean,
    onDismiss: () -> Unit,
) {
    AppSheet(
        onDismiss = onDismiss,
        title = if (isStreaming) "Thinking…"
        else "Reasoned for ${formatThinkingDuration(durationMs)}",
    ) { _ ->
        ReasoningBlock(text = text, isStreaming = isStreaming)
    }
}

@Composable
private fun ReasoningBlock(text: String, isStreaming: Boolean) {
    // LocalClipboardManager is marked deprecated in favour of LocalClipboard (suspend-
    // based) but the synchronous path is enough here and matches ToolCallDetailSheet.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val tone = MaterialTheme.colorScheme.onSurfaceVariant
    val display = when {
        text.isNotBlank() -> text
        isStreaming -> "Generating…"
        else -> "(empty trace)"
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Trace",
                style = MaterialTheme.typography.labelLarge,
                color = tone,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(display)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lc_copy),
                    contentDescription = "Copy reasoning",
                    tint = tone,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            val annotated = if (isStreaming) {
                val caretAlpha by rememberInfiniteTransition(label = "thinking-caret")
                    .animateFloat(
                        initialValue = 1f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 600, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "thinking-caret-alpha",
                    )
                buildAnnotatedString {
                    append(display)
                    withStyle(SpanStyle(color = onSurface.copy(alpha = caretAlpha))) {
                        append("▍")
                    }
                }
            } else {
                AnnotatedString(display)
            }
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = onSurface,
            )
        }
    }
}
