package com.swaptr.aide.ui.models

import androidx.compose.runtime.Composable
import com.swaptr.aide.R
import com.swaptr.aide.data.download.DownloadStatus
import com.swaptr.aide.data.speech.SpeechAssetKind
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.domain.speech.SpeechAssetSummary
import com.swaptr.aide.ui.common.AppMenuAction
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.AppMenuTrailingOverflow

data class SpeechLibraryActions(
    val onDownload: (SpeechAssetSpec) -> Unit,
    val onPause: (SpeechAssetSpec) -> Unit,
    val onResume: (SpeechAssetSpec) -> Unit,
    val onCancel: (SpeechAssetSpec) -> Unit,
    val onDelete: (SpeechAssetSpec) -> Unit,
    val onSetActive: (SpeechAssetSpec) -> Unit,
)

@Composable
fun SpeechLibraryRow(
    summary: SpeechAssetSummary,
    actions: SpeechLibraryActions,
) {
    val spec = summary.spec
    val sizeLabel = spec.sizeBytes?.let { humanBytes(it) } ?: "—"
    val idleSubtitle = "${spec.family.displayName} · $sizeLabel · ${spec.locale}"

    val entry = when (val s = summary.downloadStatus) {
        is DownloadStatus.InProgress -> AppMenuEntry(
            title = spec.displayName,
            subtitleContent = { DownloadingSubtitleRow(s) },
            trailing = { AppMenuTrailingOverflow(actions = speechActionsFor(summary, actions)) },
        )
        is DownloadStatus.Finalizing -> AppMenuEntry(
            title = spec.displayName,
            subtitleContent = { FinalizingSubtitleRow(s.stage) },
            trailing = { AppMenuTrailingOverflow(actions = speechActionsFor(summary, actions)) },
        )
        is DownloadStatus.Completed -> {
            val readyLabel = when {
                summary.isActive -> "${spec.family.displayName} · Active"
                else -> "${spec.family.displayName} · $sizeLabel · Ready"
            }
            AppMenuEntry(
                title = spec.displayName,
                subtitleContent = { ReadySubtitleRow(readyLabel) },
                trailing = { AppMenuTrailingOverflow(actions = speechActionsFor(summary, actions)) },
            )
        }
        is DownloadStatus.Paused -> AppMenuEntry(
            title = spec.displayName,
            subtitle = pausedSubtitle(s),
            trailing = { AppMenuTrailingOverflow(actions = speechActionsFor(summary, actions)) },
        )
        is DownloadStatus.Queued -> AppMenuEntry(
            title = spec.displayName,
            subtitle = "Queued · $idleSubtitle",
            trailing = { AppMenuTrailingOverflow(actions = speechActionsFor(summary, actions)) },
        )
        is DownloadStatus.Failed -> AppMenuEntry(
            title = spec.displayName,
            subtitle = "Failed: ${s.message}",
            trailing = { AppMenuTrailingOverflow(actions = speechActionsFor(summary, actions)) },
        )
        is DownloadStatus.Idle, is DownloadStatus.Cancelled -> AppMenuEntry(
            title = spec.displayName,
            subtitle = idleSubtitle,
            trailing = { AppMenuTrailingOverflow(actions = speechActionsFor(summary, actions)) },
        )
    }
    AppMenuList(items = listOf(entry))
}

private fun speechActionsFor(
    summary: SpeechAssetSummary,
    actions: SpeechLibraryActions,
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
            // Extract has no Range-resume, so no Pause. Cancel wipes the partial
            // extract + the cached archive; the user can re-download from scratch.
            add(
                AppMenuAction(
                    label = "Cancel",
                    iconRes = R.drawable.ic_lc_square,
                    onClick = { actions.onCancel(summary.spec) },
                ),
            )
        }
        is DownloadStatus.Paused -> {
            add(AppMenuAction(label = "Resume", onClick = { actions.onResume(summary.spec) }))
            add(AppMenuAction(label = "Cancel", onClick = { actions.onCancel(summary.spec) }))
        }
        is DownloadStatus.Completed -> {
            if (!summary.isActive && summary.spec.kind != SpeechAssetKind.VAD) {
                add(
                    AppMenuAction(
                        label = "Use",
                        iconRes = R.drawable.ic_lc_play,
                        onClick = { actions.onSetActive(summary.spec) },
                    ),
                )
            }
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
