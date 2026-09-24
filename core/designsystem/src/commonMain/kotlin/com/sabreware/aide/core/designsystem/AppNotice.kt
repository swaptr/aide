package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Severity of an [AppNotice]. One scale for the whole app:
 * [Info] → secondary container (neutral notice), [Warning] → tertiary container (matches the
 * WriteConfirmGate WARN accent), [Error] → error container.
 */
enum class NoticeSeverity { Info, Warning, Error }

private data class NoticeStyle(val container: Color, val content: Color, val icon: DrawableResource)

@Composable
private fun NoticeSeverity.style(): NoticeStyle = when (this) {
    NoticeSeverity.Info -> NoticeStyle(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
        Res.drawable.ic_lc_info,
    )
    NoticeSeverity.Warning -> NoticeStyle(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
        Res.drawable.ic_lc_circle_alert,
    )
    NoticeSeverity.Error -> NoticeStyle(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
        Res.drawable.ic_lc_circle_alert,
    )
}

/**
 * THE inline status surface: a contained, severity-colored card with a leading icon, for operation
 * errors, warnings, and informational notices alike — every consumer gets the same shape, padding, and
 * per-severity colors. Renders nothing when [text] is null, so callers drop it in unconditionally.
 *
 * Per-field validation belongs on the field (`AppTextField` errorText), full-pane failures in
 * `StatePane`, and app-wide notices in the global `AppBanner` — this is for a notice INSIDE a page,
 * form, or sheet.
 */
@Composable
fun AppNotice(
    text: String?,
    modifier: Modifier = Modifier,
    severity: NoticeSeverity = NoticeSeverity.Error,
) {
    if (text == null) return
    val style = severity.style()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(style.container)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(style.icon),
            contentDescription = null,
            tint = style.content,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            color = style.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * An [AppNotice] that clears itself after [timeoutMs] — for transient action failures (toggle/remove/
 * load) that should surface inline then fade, mimicking a snackbar. [onDismiss] clears the backing state.
 * Drop it where the notice should sit; renders nothing while [text] is null.
 */
@Composable
fun AutoDismissNotice(
    text: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    severity: NoticeSeverity = NoticeSeverity.Error,
    timeoutMs: Long = 5000,
) {
    LaunchedEffect(text) {
        if (text != null) {
            delay(timeoutMs)
            onDismiss()
        }
    }
    AppNotice(text, modifier = modifier, severity = severity)
}
