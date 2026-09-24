package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.form.FormActions

/**
 * Confirmation dialog: a message plus Cancel / confirm actions. [onConfirm] runs on confirm, [onCancel] runs
 * only when dismissed without confirming (scrim, back, Cancel), and [onDismiss] always runs after the dialog
 * closes (state cleanup). Renders as a bottom sheet on compact, a centered dialog on wide.
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    dismissLabel: String = "Cancel",
    confirmEnabled: Boolean = true,
    confirmColor: Color = Color.Unspecified,
    onCancel: (() -> Unit)? = null,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var confirmed by remember { mutableStateOf(false) }
    AppDialog(
        onDismiss = {
            if (!confirmed) onCancel?.invoke()
            onDismiss()
        },
        title = title,
    ) { controller ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            extraContent?.invoke(this)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { controller.close() }) { Text(dismissLabel) }
                Spacer(Modifier.size(8.dp))
                TextButton(
                    enabled = confirmEnabled,
                    onClick = {
                        confirmed = true
                        controller.close { onConfirm() }
                    },
                    colors = if (confirmColor.isSpecified) {
                        ButtonDefaults.textButtonColors(contentColor = confirmColor)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) { Text(confirmLabel) }
            }
        }
    }
}

/**
 * A [ConfirmDialog] specialised for destructive deletes — bakes in the "Delete" confirm label and the error
 * color so every "Delete X?" prompt looks identical. Callers pass only the item-specific [title]/[message].
 */
@Composable
fun DeleteConfirmDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    confirmLabel: String = "Delete",
) {
    ConfirmDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        confirmColor = MaterialTheme.colorScheme.error,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Single-line text-input dialog (rename / name a group, etc.). Autofocuses the field and selects the
 * existing text so the user can type over it.
 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String? = null,
    validate: (String) -> Boolean = { it.trim().isNotEmpty() },
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val trimmed = value.text.trim()

    AppDialog(onDismiss = onDismiss, title = title) { controller ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = label?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            FormActions(
                confirmLabel = confirmLabel,
                confirmEnabled = validate(value.text),
                onCancel = { controller.close() },
                onConfirm = { controller.close { onConfirm(trimmed) } },
            )
        }
    }
}
