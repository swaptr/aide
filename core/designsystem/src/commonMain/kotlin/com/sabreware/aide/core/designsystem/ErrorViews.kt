package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.error.UserError
import org.jetbrains.compose.resources.painterResource

/**
 * The one inline look for a failure: an icon, ONE short line saying what went wrong, and a "Details" link to
 * the rest. Never the raw message — that belongs in [ErrorDetailsSheet], where it can be read in full and
 * copied. [onRetry], when given, puts the obvious next step right beside the problem.
 */
@Composable
fun ErrorLine(error: UserError, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.errorContainer.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(Res.drawable.ic_lc_circle_alert), contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
        Text(
            error.title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f).marquee(),
        )
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
        TextButton(onClick = { open = true }) { Text("Details") }
    }
    if (open) ErrorDetailsSheet(error, onDismiss = { open = false })
}

/** A failure in full: what happened, what to try, and the technical message — selectable and copyable. */
@Composable
fun ErrorDetailsSheet(error: UserError, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AppDialog(
        onDismiss = onDismiss,
        title = error.kind.label,
        trailingActions = listOf(
            HeaderAction(Res.drawable.ic_lc_copy, "Copy details", onClick = { clipboard.setText(AnnotatedString(error.detail)) }),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(error.title, style = MaterialTheme.typography.titleMedium)
            error.hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            SelectionContainer {
                Text(
                    error.detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }
        }
    }
}
