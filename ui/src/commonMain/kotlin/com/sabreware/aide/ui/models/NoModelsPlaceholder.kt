package com.sabreware.aide.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.PlaceholderAction
import com.sabreware.aide.core.designsystem.resources.*

/** The shared "no models in use yet" empty state — used by the in-use list fallback and the model picker. */
@Composable
fun NoModelsPlaceholder(onAddModel: () -> Unit, modifier: Modifier = Modifier) {
    Placeholder(
        modifier = modifier,
        iconRes = Res.drawable.ic_lc_brain_circuit,
        title = "No models in use yet",
        subtitle = "Add a model to start chatting.",
        actions = listOf(
            PlaceholderAction(
                label = "Add model",
                iconRes = Res.drawable.ic_lc_plus,
                onClick = onAddModel,
            ),
        ),
    )
}
