package com.swaptr.aide.ui.models.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swaptr.aide.ui.common.AppSheet

@Composable
fun OllamaPullDialog(
    state: PullState,
    onPull: (String) -> Unit,
    onCancelPull: () -> Unit,
    onResetState: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }

    AppSheet(
        onDismiss = {
            if (state is PullState.InProgress) onCancelPull() else onResetState()
            onDismiss()
        },
        title = "Pull model",
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    ) { controller ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Type an Ollama tag (e.g. `llama3.2:3b`). Browse the catalogue at " +
                    "ollama.com/library. The server pulls and stores the model locally.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Model tag") },
                singleLine = true,
                enabled = state !is PullState.InProgress,
                modifier = Modifier.fillMaxWidth(),
            )

            when (state) {
                PullState.Idle -> Unit
                is PullState.InProgress -> ProgressBlock(state)
                is PullState.Failed -> Text(
                    "Failed: ${state.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is PullState.Done -> Text(
                    "Pulled ${state.name}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    is PullState.InProgress -> {
                        TextButton(onClick = { onCancelPull() }) { Text("Cancel pull") }
                    }
                    is PullState.Done -> {
                        TextButton(
                            onClick = {
                                onResetState()
                                controller.close()
                            },
                        ) { Text("Done") }
                    }
                    else -> {
                        TextButton(onClick = { controller.close() }) { Text("Close") }
                        Spacer(Modifier.size(8.dp))
                        TextButton(
                            enabled = name.isNotBlank(),
                            onClick = { onPull(name) },
                        ) { Text("Pull") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBlock(state: PullState.InProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            state.status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val pct = state.percent
        if (pct != null) {
            LinearProgressIndicator(
                progress = { pct.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${(pct * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
