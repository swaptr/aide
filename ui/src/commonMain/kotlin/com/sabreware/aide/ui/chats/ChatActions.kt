package com.sabreware.aide.ui.chats

import com.sabreware.aide.core.designsystem.AppMenuSheetHeader
import com.sabreware.aide.core.designsystem.browse.ActionScope
import com.sabreware.aide.core.designsystem.browse.CollectionAction
import com.sabreware.aide.core.designsystem.browse.Confirmation
import com.sabreware.aide.core.designsystem.browse.toggleAction
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.ui.labels.LabelEditor
import com.sabreware.aide.ui.labels.labelActions

/**
 * The ONE action list for a chat — Rename, Pin, Archive, Tags, Select, Delete — used by the drawer, the
 * [ChatsScreen] (row long-press and selection) and the chat page's own menu, so every surface offers the same
 * actions in the same words. Run through `rememberActionRunner`; Delete confirms itself.
 *
 * Each effect is the host's: archiving or deleting the OPEN chat has to move to a replacement, which only the
 * host knows how to do. [onSelect] is null where the host has no selection mode, and then Select is left out.
 * Pin is the chat's own pin (`Chat.isStarred`), not a label pin.
 */
fun chatActions(
    editor: LabelEditor,
    labels: () -> Labels,
    onRename: (Chat) -> Unit,
    setPinned: (List<Chat>, Boolean) -> Unit,
    setArchived: (List<Chat>, Boolean) -> Unit,
    onDelete: (List<Chat>) -> Unit,
    onSelect: ((Chat) -> Unit)? = null,
): List<CollectionAction<Chat>> = listOf(
    CollectionAction<Chat>(id = "rename", label = "Rename", iconRes = Res.drawable.ic_lc_pencil, scope = ActionScope.One) {
        onRename(it.single())
    },
    CollectionAction(
        id = "pin",
        label = { targets -> if (targets.isNotEmpty() && targets.all(Chat::isStarred)) "Unpin" else "Pin" },
        iconRes = { targets ->
            if (targets.isNotEmpty() && targets.all(Chat::isStarred)) Res.drawable.ic_lc_pin_off else Res.drawable.ic_lc_pin
        },
    ) { targets -> setPinned(targets, !(targets.isNotEmpty() && targets.all(Chat::isStarred))) },
    toggleAction(
        id = "archive",
        on = "Archive",
        off = "Unarchive",
        iconRes = Res.drawable.ic_lc_archive,
        isOn = Chat::isArchived,
        set = setArchived,
    ),
) + labelActions<Chat>(
    editor = editor,
    labels = labels,
    subject = { LabelSubject.chat(it.id) },
    name = ::chatName,
    original = { it.title },
).filter { it.id == "tags" } + listOfNotNull(
    onSelect?.let { select ->
        CollectionAction<Chat>(id = "select", label = "Select", iconRes = Res.drawable.ic_lc_list_checks, scope = ActionScope.One) {
            select(it.single())
        }
    },
    CollectionAction(
        id = "delete",
        label = "Delete",
        iconRes = Res.drawable.ic_lc_trash,
        destructive = true,
        confirm = { targets ->
            Confirmation(
                title = if (targets.size == 1) "Delete chat?" else "Delete ${targets.size} chats?",
                message = if (targets.size == 1) "\"${chatName(targets.single())}\" and its messages will be removed."
                else "The selected chats and their messages will be removed.",
            )
        },
        perform = onDelete,
    ),
)

/** How a chat is named on screen: its title, or "New chat" before it has one. */
fun chatName(chat: Chat): String = chat.title.ifBlank { "New chat" }

/** Display name for a chat's origin [Surface]. Shared by the row subtitle and the filter sheet. */
fun surfaceLabel(surface: Surface): String = when (surface) {
    Surface.CHAT -> "Chat"
    Surface.IME -> "Keyboard"
    Surface.VOICE -> "Voice"
}

/** The header for a chat's action sheet: the chat's name, like every sheet header. */
fun chatSheetHeader(chat: Chat): AppMenuSheetHeader = AppMenuSheetHeader(title = chatName(chat))
