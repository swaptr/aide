package com.sabreware.aide.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.StreamingMarkdown
import com.sabreware.aide.core.designsystem.theme.aideMarkdownBodyStyle

/**
 * The thinking sheet ("Thought for Ns"). Renders the trace as live markdown through the shared
 * [StreamingMarkdown] — the same renderer + styling chat replies use — so headings/bold/lists/code show
 * formatted (not as raw "**…**") and update in real time while the model is still thinking.
 *
 * The trace scrolls inside the sheet. Nothing here sizes the sheet: [AppDialog] opens at its first detent
 * and lays the content out to exactly that height, so a trace streaming in scrolls internally instead of
 * growing the sheet on every token.
 */
@Composable
fun ThinkingDetailSheet(
    text: String,
    durationMs: Long,
    isStreaming: Boolean,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = if (isStreaming) "Thinking…"
        else "Thought for ${formatThinkingDuration(durationMs)}",
    ) { _ ->
        StreamingMarkdown(
            text = text,
            colors = colors,
            typography = typography,
            textStyle = aideMarkdownBodyStyle(),
            textColor = MaterialTheme.colorScheme.onSurface,
            placeholder = if (isStreaming) "Thinking…" else "No thinking to show",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
    }
}
