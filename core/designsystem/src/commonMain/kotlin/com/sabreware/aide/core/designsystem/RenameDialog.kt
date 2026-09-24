package com.sabreware.aide.core.designsystem

import androidx.compose.runtime.Composable

@Composable
fun RenameChatDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    TextInputDialog(
        title = "Rename conversation",
        initial = initialTitle,
        confirmLabel = "Rename",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        validate = { it.trim().isNotEmpty() && it.trim() != initialTitle.trim() },
    )
}
