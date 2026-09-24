package com.sabreware.aide.ui.chats

import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.AppMenuAction
import com.sabreware.aide.core.designsystem.relativeTimeLabel
import com.sabreware.aide.core.domain.chat.Chat

/**
 * One chat in a list: title + relative-time subtitle, shared by [ChatsScreen] and the drawer.
 * In selection mode it shows a trailing checkbox; the checkbox is display-only and the whole row toggles
 * selection (via [onClick]) so a tap isn't handled twice.
 */
@Composable
fun ChatRow(
    chat: Chat,
    onClick: () -> Unit,
    tags: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    contextActions: List<AppMenuAction>? = null,
) {
    AppListItem(
        headline = chat.title.ifBlank { "New chat" },
        // Origin surface · relative time, e.g. "Voice · 23 minutes ago".
        supportingText = (listOf(surfaceLabel(chat.surface), relativeTimeLabel(chat.updatedAt)) + tags.map { "#$it" }).joinToString(" · "),
        trailing = if (selectionMode) {
            { Checkbox(checked = selected, onCheckedChange = null) }
        } else {
            null
        },
        selected = selected,
        onClick = onClick,
        contextActions = contextActions,
        contextHeader = contextActions?.let { chatSheetHeader(chat) },
        modifier = modifier,
    )
}
