package com.swaptr.aide.ui.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.domain.model.ModelSummary
import com.swaptr.aide.ui.common.SwipeableTabbedContent
import com.swaptr.aide.ui.models.providers.ProviderSection

private const val TAB_IN_USE = 0
private const val TAB_ON_DEVICE = 1
private const val TAB_CLOUD = 2
private val TAB_LABELS = listOf("In Use", "Local", "Cloud")

@Composable
fun ModelsContent(
    modifier: Modifier = Modifier,
    viewModel: ModelsViewModel = hiltViewModel(),
    onRowClick: ((ModelSummary) -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ModelSpec?>(null) }
    var pendingVoiceDelete by remember { mutableStateOf<SpeechAssetSpec?>(null) }
    var selectedTab by remember { mutableIntStateOf(TAB_IN_USE) }

    val actions = ModelLibraryActions(
        onDownload = viewModel::startOrResumeDownload,
        onPause = viewModel::pauseDownload,
        onResume = viewModel::startOrResumeDownload,
        onCancel = viewModel::cancelDownload,
        onDelete = { pendingDelete = it },
        onLoad = viewModel::load,
        onUnload = viewModel::unload,
    )

    val voiceActions = SpeechLibraryActions(
        onDownload = viewModel::startOrResumeVoice,
        onPause = viewModel::pauseVoice,
        onResume = viewModel::startOrResumeVoice,
        onCancel = viewModel::cancelVoice,
        onDelete = { pendingVoiceDelete = it },
        onSetActive = viewModel::setActiveVoice,
    )

    val inUseRows = state.rows
        .filter { it.isInUse }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.spec.displayName })
    val onDeviceRows = state.rows
        .filter { it.spec.provider == ProviderId.LOCAL }
    val cloudRows = state.rows
        .filter { it.spec.provider != ProviderId.LOCAL }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.spec.displayName })

    Box(modifier = modifier.fillMaxSize()) {
        SwipeableTabbedContent(
            tabs = TAB_LABELS,
            selectedIndex = selectedTab,
            onSelectIndex = { selectedTab = it },
            modifier = Modifier.fillMaxSize(),
            containerColor = containerColor,
        ) { page ->
            when (page) {
                TAB_IN_USE -> InUseTabContent(
                    llmRows = inUseRows,
                    voiceRows = state.voiceRows,
                    loadingModelId = state.loadingModelId,
                    modelActions = actions,
                    voiceActions = voiceActions,
                    onRowClick = onRowClick,
                )
                TAB_ON_DEVICE -> LocalTabContent(
                    llmRows = onDeviceRows,
                    voiceRows = state.voiceRows,
                    loadingModelId = state.loadingModelId,
                    modelActions = actions,
                    voiceActions = voiceActions,
                )
                TAB_CLOUD -> ModelsPage(
                    rows = cloudRows,
                    loadingModelId = state.loadingModelId,
                    actions = actions,
                    emptyMessage = "",
                    header = { ProviderSection() },
                    showProviderHeaders = true,
                    onRowClick = onRowClick,
                )
            }
        }
    }

    pendingDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${spec.displayName}?") },
            text = {
                val size = spec.sizeBytes?.let { humanBytes(it) } ?: "on-device"
                Text("Removes the $size weights. You can re-download anytime.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(spec)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    pendingVoiceDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { pendingVoiceDelete = null },
            title = { Text("Delete ${spec.displayName}?") },
            text = {
                val size = spec.sizeBytes?.let { humanBytes(it) } ?: "on-device"
                Text("Removes the $size voice bundle. You can re-download anytime.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = spec
                    pendingVoiceDelete = null
                    viewModel.deleteVoice(s)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingVoiceDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelsPage(
    rows: List<ModelSummary>,
    loadingModelId: String?,
    actions: ModelLibraryActions,
    emptyMessage: String,
    showProviderHeaders: Boolean,
    onRowClick: ((ModelSummary) -> Unit)?,
    header: (@Composable () -> Unit)? = null,
    compactRows: Boolean = false,
) {
    if (rows.isEmpty() && header == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    ModelLibraryList(
        rows = rows,
        loadingModelId = loadingModelId,
        actions = actions,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        header = header,
        showProviderHeaders = showProviderHeaders,
        onRowClick = onRowClick,
        compactRows = compactRows,
    )
}
