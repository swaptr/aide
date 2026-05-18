package com.swaptr.aide.ui.models.providers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaptr.aide.data.catalog.ProviderTier
import com.swaptr.aide.data.provider.ConnectionTestResult
import com.swaptr.aide.ui.common.AppMenuAction
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.AppMenuTrailingOverflow
import java.util.concurrent.TimeUnit

@Composable
fun ProviderSection(
    modifier: Modifier = Modifier,
    viewModel: ProviderSettingsViewModel = hiltViewModel(),
) {
    val config by viewModel.ollamaConfig.collectAsStateWithLifecycle()
    val testResult by viewModel.lastTestResult.collectAsStateWithLifecycle()
    val testing by viewModel.testInFlight.collectAsStateWithLifecycle()
    val refreshing by viewModel.ollamaRefreshing.collectAsStateWithLifecycle()
    val fetchedAt by viewModel.ollamaTagsFetchedAt.collectAsStateWithLifecycle()
    val lastUsedByTier by viewModel.lastUsedByTier.collectAsStateWithLifecycle()
    val pullState by viewModel.pullState.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }

    val connected = config != null
    val isSelfHosted = connected && config!!.isCloud.not()
    val statusLine = when {
        config == null -> "Not connected"
        config!!.isCloud -> "Connected · Cloud (ollama.com)"
        else -> "Connected · ${config!!.baseUrl}"
    }
    val lastUsedTag = config?.let { cfg ->
        val tier = if (cfg.isCloud) ProviderTier.OLLAMA_CLOUD else ProviderTier.OLLAMA_SELF
        lastUsedByTier[tier]?.let(::stripOllamaPrefix)
    }
    val supporting: String = when {
        testing -> "$statusLine · Testing…"
        refreshing -> "$statusLine · Refreshing models…"
        testResult is ConnectionTestResult.Ok ->
            "$statusLine · ${(testResult as ConnectionTestResult.Ok).modelCount} models"
        testResult is ConnectionTestResult.Failed ->
            "$statusLine · Test failed: ${(testResult as ConnectionTestResult.Failed).message}"
        lastUsedTag != null && fetchedAt != null ->
            "$statusLine · Last used: $lastUsedTag · Updated ${relativeTime(fetchedAt!!)}"
        lastUsedTag != null -> "$statusLine · Last used: $lastUsedTag"
        fetchedAt != null -> "$statusLine · Updated ${relativeTime(fetchedAt!!)}"
        else -> statusLine
    }
    val actions = buildList {
        add(
            AppMenuAction(
                label = if (connected) "Edit" else "Connect",
                onClick = { editing = true },
            ),
        )
        if (connected) {
            add(
                AppMenuAction(
                    label = if (refreshing) "Refreshing…" else "Refresh models",
                    enabled = !refreshing,
                    onClick = { viewModel.refreshOllamaTags() },
                ),
            )
            add(
                AppMenuAction(
                    label = if (testing) "Testing…" else "Test connection",
                    enabled = !testing,
                    onClick = { viewModel.testOllama() },
                ),
            )
            if (isSelfHosted) {
                add(
                    AppMenuAction(
                        label = "Pull model…",
                        onClick = { pulling = true },
                    ),
                )
            }
            add(
                AppMenuAction(
                    label = "Disconnect",
                    destructive = true,
                    onClick = { viewModel.clearOllama() },
                ),
            )
        }
    }

    AppMenuList(
        modifier = modifier,
        items = listOf(
            AppMenuEntry(
                title = "Ollama",
                subtitle = supporting,
                onClick = { editing = true },
                trailing = { AppMenuTrailingOverflow(actions = actions) },
            ),
        ),
    )

    if (editing) {
        OllamaEditDialog(
            initial = config,
            onDismiss = { editing = false },
            onSave = {
                viewModel.saveOllama(it)
                editing = false
            },
        )
    }

    if (pulling) {
        OllamaPullDialog(
            state = pullState,
            onPull = viewModel::pullModel,
            onCancelPull = viewModel::cancelPull,
            onResetState = viewModel::resetPullState,
            onDismiss = { pulling = false },
        )
    }
}

private fun stripOllamaPrefix(id: String): String = id
    .removePrefix("ollama-cloud:")
    .removePrefix("ollama:")

private fun relativeTime(epochMs: Long): String {
    val delta = (System.currentTimeMillis() - epochMs).coerceAtLeast(0)
    val mins = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        mins < 1L -> "just now"
        mins < 60L -> "${mins}m ago"
        hours < 24L -> "${hours}h ago"
        else -> "${days}d ago"
    }
}
