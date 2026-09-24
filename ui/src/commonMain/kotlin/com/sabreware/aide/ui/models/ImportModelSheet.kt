package com.sabreware.aide.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppLinearProgress
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuToggle
import com.sabreware.aide.core.designsystem.AppTextField
import com.sabreware.aide.core.designsystem.form.FormActions
import com.sabreware.aide.core.domain.model.ModelImportConfig

/**
 * Configure + import a BYO on-device model file. Sampler fields blank = the engine default; capability
 * switches declare what the file supports (image/audio/thinking) so chat gates them correctly. The copy
 * runs in the ViewModel; [importProgress] (0..1) drives the bar and locks the form while copying.
 */
@Composable
fun ImportModelSheet(
    defaultName: String,
    importProgress: Float?,
    onImport: (ModelImportConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var topK by rememberSaveable { mutableStateOf("") }
    var topP by rememberSaveable { mutableStateOf("") }
    var temperature by rememberSaveable { mutableStateOf("") }
    var maxTokens by rememberSaveable { mutableStateOf("") }
    var visionIn by rememberSaveable { mutableStateOf(false) }
    var audioIn by rememberSaveable { mutableStateOf(false) }
    var thinking by rememberSaveable { mutableStateOf(false) }
    var preferGpu by rememberSaveable { mutableStateOf(true) }
    val busy = importProgress != null

    AppDialog(onDismiss = onDismiss, title = "Import model") {
        // Tall form — the dialog surface scrolls it, so every field (and the keyboard) stays reachable.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppTextField(value = name, onValueChange = { name = it }, label = "Name", singleLine = true)
            AppTextField(value = topK, onValueChange = { topK = it }, label = "Top-K", placeholder = "40", singleLine = true)
            AppTextField(value = topP, onValueChange = { topP = it }, label = "Top-P", placeholder = "0.95", singleLine = true)
            AppTextField(
                value = temperature,
                onValueChange = { temperature = it },
                label = "Temperature",
                placeholder = "1.0",
                singleLine = true,
            )
            AppTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it },
                label = "Max tokens",
                placeholder = "1024",
                singleLine = true,
            )
            // The form column already pads its sides, so the toggle group adds none of its own.
            AppMenu(
                groupPadding = PaddingValues(0.dp),
                items = listOf(
                    AppMenuEntry(
                        title = "Vision (image) input",
                        toggle = AppMenuToggle(checked = visionIn, onCheckedChange = { visionIn = it }),
                    ),
                    AppMenuEntry(
                        title = "Audio input",
                        toggle = AppMenuToggle(checked = audioIn, onCheckedChange = { audioIn = it }),
                    ),
                    AppMenuEntry(
                        title = "Thinking",
                        toggle = AppMenuToggle(checked = thinking, onCheckedChange = { thinking = it }),
                    ),
                    AppMenuEntry(
                        title = "Prefer GPU",
                        toggle = AppMenuToggle(checked = preferGpu, onCheckedChange = { preferGpu = it }),
                    ),
                ),
            )

            if (importProgress != null) {
                AppLinearProgress(progress = { importProgress }, modifier = Modifier.fillMaxWidth())
            }

            FormActions(
                confirmLabel = if (busy) "Importing…" else "Import",
                confirmEnabled = !busy && name.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = {
                    onImport(
                        ModelImportConfig(
                            displayName = name.trim(),
                            topK = topK.trim().toIntOrNull(),
                            topP = topP.trim().toFloatOrNull(),
                            temperature = temperature.trim().toFloatOrNull(),
                            maxTokens = maxTokens.trim().toIntOrNull(),
                            visionIn = visionIn,
                            audioIn = audioIn,
                            thinking = thinking,
                            preferGpu = preferGpu,
                        ),
                    )
                },
            )
        }
    }
}
