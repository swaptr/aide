package com.sabreware.aide.ui.settings.websearch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppTextField
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.state.isLoading
import com.sabreware.aide.core.designsystem.state.valueOrNull
import org.koin.compose.viewmodel.koinViewModel

/**
 * Web-search provider picker + per-provider API-key entry, opened from the Web row in Tools settings.
 * Selecting a provider applies it and closes; keyed providers (Ollama Cloud / Brave / Tavily) get a
 * dedicated key field backed by WebSearchCredentialsRepository — independent of any chat provider, so
 * Ollama web search/fetch works regardless of which chat endpoint (if any) is configured.
 */
@Composable
fun WebSearchProviderSheet(
    onDismiss: () -> Unit,
    viewModel: WebSearchSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AppDialog(
        onDismiss = onDismiss,
        title = "Web search",
    ) { controller ->
        val rows = state.rows.valueOrNull.orEmpty()
        Column(modifier = Modifier.fillMaxWidth()) {
            AppMenu(
                items = buildList {
                    add(
                        AppMenuEntry(
                            title = "Auto",
                            subtitle = "Use the best available provider. Falls back to DuckDuckGo when no key is configured.",
                            selected = state.override == null,
                            onClick = { viewModel.setOverride(null); controller.close() },
                        ),
                    )
                    rows.forEach { row ->
                        add(
                            AppMenuEntry(
                                title = row.displayName,
                                subtitle = row.note,
                                selected = state.override == row.id,
                                enabled = row.available,
                                onClick = { viewModel.setOverride(row.id); controller.close() },
                            ),
                        )
                    }
                },
            )
            // One skeleton row per provider still to land, so the sheet opens at its final height rather
            // than growing under the user mid-slide once the key store answers.
            if (state.rows.isLoading) {
                val shimmer = rememberSkeletonShimmer()
                repeat(state.providerCount) { SkeletonListRow(shimmer, lines = 2, index = it) }
            }

            val keyed = rows.filter { it.requiresKey }
            if (keyed.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "API keys",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    keyed.forEach { row ->
                        KeyField(
                            label = "${row.displayName} API key",
                            hasKey = row.hasKey,
                            onSave = { viewModel.setKey(row.id, it) },
                            onClear = { viewModel.setKey(row.id, "") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyField(
    label: String,
    hasKey: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var text by rememberSaveable(label) { mutableStateOf("") }
    AppTextField(
        value = text,
        onValueChange = { text = it },
        label = label,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(enabled = text.isNotBlank(), onClick = { onSave(text); text = "" }) {
            Text(if (hasKey) "Update" else "Save")
        }
        if (hasKey) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}
