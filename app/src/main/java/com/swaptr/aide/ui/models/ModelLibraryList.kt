package com.swaptr.aide.ui.models

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swaptr.aide.R
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.catalog.ProviderId
import com.swaptr.aide.data.download.DownloadStatus
import com.swaptr.aide.domain.model.ModelSummary
import com.swaptr.aide.ui.common.AppMenuAction
import com.swaptr.aide.ui.common.AppMenuTrailingOverflow

data class ModelLibraryActions(
    val onDownload: (ModelSpec) -> Unit,
    val onPause: (ModelSpec) -> Unit,
    val onResume: (ModelSpec) -> Unit,
    val onCancel: (ModelSpec) -> Unit,
    val onDelete: (ModelSpec) -> Unit,
    val onLoad: (ModelSpec) -> Unit,
    val onUnload: (ModelSpec) -> Unit,
)

@Composable
fun ModelLibraryRow(
    summary: ModelSummary,
    isLoading: Boolean,
    actions: ModelLibraryActions,
    onRowClick: ((ModelSummary) -> Unit)? = null,
    compact: Boolean = false,
) {
    val handler = onRowClick.takeIf { summary.isDownloaded }
    ModelListRow(
        summary = summary,
        isLoading = isLoading,
        onClick = handler?.let { { it(summary) } },
        trailing = {
            AppMenuTrailingOverflow(actions = modelActionsFor(summary, isLoading, actions))
        },
        compact = compact,
    )
}

@Composable
fun ModelLibraryList(
    rows: List<ModelSummary>,
    loadingModelId: String?,
    actions: ModelLibraryActions,
    modifier: Modifier = Modifier,
    onRowClick: ((ModelSummary) -> Unit)? = null,
    showDividers: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    header: (@Composable () -> Unit)? = null,
    showProviderHeaders: Boolean = true,
    compactRows: Boolean = false,
) {
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        if (header != null) item(key = "library-header") { header() }
        if (showProviderHeaders) {
            val grouped = rows.groupBy { it.spec.provider }
            for ((provider, providerRows) in grouped) {
                item(key = "header-$provider") {
                    Text(
                        text = providerLabel(provider),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(providerRows, key = { it.spec.id }) { summary ->
                    ModelLibraryRow(
                        summary = summary,
                        isLoading = loadingModelId == summary.spec.id,
                        actions = actions,
                        onRowClick = onRowClick,
                        compact = compactRows,
                    )
                    if (showDividers) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        } else {
            items(rows, key = { it.spec.id }) { summary ->
                ModelLibraryRow(
                    summary = summary,
                    isLoading = loadingModelId == summary.spec.id,
                    actions = actions,
                    onRowClick = onRowClick,
                    compact = compactRows,
                )
                if (showDividers) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

private fun providerLabel(provider: ProviderId): String = when (provider) {
    ProviderId.LOCAL -> "On-device"
    ProviderId.OLLAMA -> "Ollama"
}

private fun modelActionsFor(
    summary: ModelSummary,
    isLoading: Boolean,
    actions: ModelLibraryActions,
): List<AppMenuAction> = buildList {
    when (summary.downloadStatus) {
        is DownloadStatus.Idle, is DownloadStatus.Cancelled, is DownloadStatus.Failed -> {
            add(
                AppMenuAction(
                    label = "Download",
                    iconRes = R.drawable.ic_lc_cloud_download,
                    onClick = { actions.onDownload(summary.spec) },
                ),
            )
        }
        is DownloadStatus.Queued, is DownloadStatus.InProgress -> {
            add(
                AppMenuAction(
                    label = "Pause",
                    iconRes = R.drawable.ic_lc_pause,
                    onClick = { actions.onPause(summary.spec) },
                ),
            )
            add(
                AppMenuAction(
                    label = "Cancel",
                    iconRes = R.drawable.ic_lc_square,
                    onClick = { actions.onCancel(summary.spec) },
                ),
            )
        }
        is DownloadStatus.Finalizing -> {
            // LLM downloads don't have a postProcess step, so this branch is defensive —
            // surface Cancel only if it does ever appear.
            add(
                AppMenuAction(
                    label = "Cancel",
                    iconRes = R.drawable.ic_lc_square,
                    onClick = { actions.onCancel(summary.spec) },
                ),
            )
        }
        is DownloadStatus.Paused -> {
            add(
                AppMenuAction(
                    label = "Resume",
                    onClick = { actions.onResume(summary.spec) },
                ),
            )
            add(
                AppMenuAction(
                    label = "Cancel",
                    onClick = { actions.onCancel(summary.spec) },
                ),
            )
        }
        is DownloadStatus.Completed -> {
            if (summary.isInUse) {
                add(
                    AppMenuAction(
                        label = "Unload",
                        iconRes = R.drawable.ic_lc_square,
                        onClick = { actions.onUnload(summary.spec) },
                    ),
                )
            } else if (!isLoading) {
                add(
                    AppMenuAction(
                        label = "Use",
                        iconRes = R.drawable.ic_lc_play,
                        onClick = { actions.onLoad(summary.spec) },
                    ),
                )
            }
            if (summary.requiresDownload) {
                add(
                    AppMenuAction(
                        label = "Delete",
                        iconRes = R.drawable.ic_lc_trash,
                        destructive = true,
                        onClick = { actions.onDelete(summary.spec) },
                    ),
                )
            }
        }
    }
}
