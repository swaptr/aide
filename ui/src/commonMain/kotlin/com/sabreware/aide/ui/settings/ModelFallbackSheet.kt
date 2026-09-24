package com.sabreware.aide.ui.settings

import androidx.compose.runtime.Composable
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.domain.model.ModelFallback

/** Human label for the reroute policy — shared by the Settings row and the sheet. */
internal fun modelFallbackLabel(policy: ModelFallback): String = when (policy) {
    ModelFallback.Never -> "Off"
    ModelFallback.SameKind -> "Same kind"
    ModelFallback.AnyModel -> "Any model"
}

/**
 * What happens when the model you chose is unavailable (weights deleted, provider offline or removed).
 * Rerouting answers with another model while yours is gone; your choice is kept, used again the moment it
 * is back, and the chat says which model is answering for as long as it lasts.
 */
@Composable
fun ModelFallbackSheet(
    selected: ModelFallback,
    onSelect: (ModelFallback) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = "Model fallback",
    ) { _ ->
        AppMenu(
            items = listOf(
                AppMenuEntry(
                    title = modelFallbackLabel(ModelFallback.Never),
                    subtitle = "Ask me. Most private.",
                    selected = selected == ModelFallback.Never,
                    onClick = { onSelect(ModelFallback.Never) },
                ),
                AppMenuEntry(
                    title = modelFallbackLabel(ModelFallback.SameKind),
                    subtitle = "Swaps in a model that runs where yours does.",
                    selected = selected == ModelFallback.SameKind,
                    onClick = { onSelect(ModelFallback.SameKind) },
                ),
                AppMenuEntry(
                    title = modelFallbackLabel(ModelFallback.AnyModel),
                    subtitle = "Any available model. Same kind first.",
                    selected = selected == ModelFallback.AnyModel,
                    onClick = { onSelect(ModelFallback.AnyModel) },
                ),
            ),
        )
    }
}
