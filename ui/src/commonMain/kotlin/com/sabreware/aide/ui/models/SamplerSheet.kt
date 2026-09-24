package com.sabreware.aide.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.sabreware.aide.core.designsystem.AppMenuSectionTitle
import com.sabreware.aide.core.designsystem.AppTextField
import com.sabreware.aide.core.designsystem.form.FormActions
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.SamplerOverride

/**
 * Edits a model's per-field sampler override. Blank = inherit the model default (shown as placeholder);
 * Reset (or saving an all-blank form) clears the override. The resolved sampler (base ← model default ←
 * override) is applied at send for both local and remote providers.
 */
@Composable
fun SamplerSheet(
    spec: ChatModelSpec,
    current: SamplerOverride?,
    onSave: (SamplerOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    val dc = spec.defaultConfig
    var topK by rememberSaveable(spec.id) { mutableStateOf(current?.topK?.toString().orEmpty()) }
    var topP by rememberSaveable(spec.id) { mutableStateOf(current?.topP?.toString().orEmpty()) }
    var temperature by rememberSaveable(spec.id) { mutableStateOf(current?.temperature?.toString().orEmpty()) }
    var maxTokens by rememberSaveable(spec.id) { mutableStateOf(current?.maxTokens?.toString().orEmpty()) }

    AppDialog(
        onDismiss = onDismiss,
        title = "Tuning",
    ) { controller ->
        // The dialog surface scrolls it, so the fields and actions stay reachable with the keyboard up.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The two a person actually tunes first, in plain words; the rest under Advanced. Blank keeps the
            // model's own default (shown as the placeholder).
            AppTextField(
                value = temperature,
                onValueChange = { temperature = it },
                label = "Creativity",
                placeholder = (dc?.temperature ?: 1.0f).toString(),
                helperText = "Lower is focused and predictable, higher is more varied. Usually 0 to 2.",
                singleLine = true,
            )
            AppTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it },
                label = "Longest reply",
                placeholder = (dc?.maxTokens ?: 1024).toString(),
                helperText = "In tokens, roughly three quarters of a word each.",
                singleLine = true,
            )
            AppMenuSectionTitle("Advanced", modifier = Modifier.padding(top = 4.dp))
            AppTextField(
                value = topK,
                onValueChange = { topK = it },
                label = "Top-K",
                placeholder = (dc?.topK ?: 40).toString(),
                helperText = "Picks each word from only this many likely choices.",
                singleLine = true,
            )
            AppTextField(
                value = topP,
                onValueChange = { topP = it },
                label = "Top-P",
                placeholder = (dc?.topP ?: 0.95f).toString(),
                helperText = "Picks from the likeliest words that add up to this share, 0 to 1.",
                singleLine = true,
            )
            FormActions(
                confirmLabel = "Save",
                cancelLabel = "Reset",
                onCancel = {
                    onSave(SamplerOverride())
                    controller.close()
                },
                onConfirm = {
                    onSave(
                        SamplerOverride(
                            topK = topK.trim().toIntOrNull(),
                            topP = topP.trim().toFloatOrNull(),
                            temperature = temperature.trim().toFloatOrNull(),
                            maxTokens = maxTokens.trim().toIntOrNull(),
                        ),
                    )
                    controller.close()
                },
            )
        }
    }
}
