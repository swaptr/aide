package com.sabreware.aide.core.designsystem.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Standard end-aligned action row for sheet/dialog forms: an optional [onCancel] text button and a
 * confirm text button. Keeps the Cancel/confirm layout identical across forms. (Full-page forms use
 * a full-width primary Button instead — that's a deliberate page-vs-sheet difference.)
 */
@Composable
fun FormActions(
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    cancelLabel: String = "Cancel",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text(cancelLabel) }
            Spacer(Modifier.size(8.dp))
        }
        TextButton(enabled = confirmEnabled, onClick = onConfirm) { Text(confirmLabel) }
    }
}
