package com.sabreware.aide.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.common.media.PendingFileAttachment
import com.sabreware.aide.core.designsystem.resources.Res
import com.sabreware.aide.core.designsystem.resources.ic_lc_file_text
import com.sabreware.aide.core.designsystem.resources.ic_lc_x
import org.jetbrains.compose.resources.painterResource

/** A file attachment as a compact pill: file icon + name (+ optional remove). Used for the composer's
 *  staged file and for sent turns' document chips, so the two read as the same object. */
@Composable
fun FileChip(
    name: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    // The remove button is a 44dp touch target (accessibility minimum) around the same 14dp glyph; the
    // pill's end/vertical padding collapses to zero beside it — the button's own inset supplies the
    // spacing — so the pill barely grows.
    val removable = onRemove != null
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
            .padding(
                start = 12.dp,
                end = if (removable) 0.dp else 12.dp,
                top = if (removable) 0.dp else 6.dp,
                bottom = if (removable) 0.dp else 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_lc_file_text),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
                Icon(
                    painter = painterResource(Res.drawable.ic_lc_x),
                    contentDescription = "Remove file",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** The composer's staged-file pill. */
@Composable
internal fun PendingFileChip(
    file: PendingFileAttachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FileChip(name = file.name, modifier = modifier, onRemove = onRemove)
}
