package com.sabreware.aide.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.theme.LocalAppSans
import com.sabreware.aide.core.designsystem.theme.LocalFontScale
import com.sabreware.aide.core.designsystem.theme.scaleFont

/**
 * Long-press menu for a user turn, as a bottom sheet: copy the raw text, open it in a selectable
 * sheet, or edit-and-resend. Each row animates the sheet out first ([AppDialogController.close]) and
 * then runs its action, so the next sheet (select-text) never stacks on top of this one.
 */
@Composable
internal fun UserMessageActionSheet(
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(onDismiss = onDismiss, title = "Message") { sheet ->
        AppMenu(
            modifier = Modifier.padding(horizontal = 12.dp),
            groupPadding = PaddingValues(0.dp),
            items = listOf(
                AppMenuEntry(
                    title = "Copy message",
                    leadingIconRes = Res.drawable.ic_lc_copy,
                    onClick = { sheet.close { onCopy() } },
                ),
                AppMenuEntry(
                    title = "Select text",
                    leadingIconRes = Res.drawable.ic_lc_text_select,
                    onClick = { sheet.close { onSelectText() } },
                ),
                AppMenuEntry(
                    title = "Edit",
                    leadingIconRes = Res.drawable.ic_lc_pencil,
                    onClick = { sheet.close { onEdit() } },
                ),
            ),
        )
    }
}

/**
 * Read-only sheet that puts a message's raw text inside a [SelectionContainer] so the user can drag to
 * select any span and copy it (double-tap selects a word; the system copy toolbar appears). Scrolls for
 * long messages.
 */
@Composable
internal fun SelectMessageTextSheet(
    text: String,
    onDismiss: () -> Unit,
) {
    AppDialog(onDismiss = onDismiss, title = "Select text") { _ ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = LocalAppSans.current)
                        .scaleFont(LocalFontScale.current),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
