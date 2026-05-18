package com.swaptr.aide.ui.models

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swaptr.aide.R
import com.swaptr.aide.data.catalog.ProviderTier
import com.swaptr.aide.data.download.DownloadStatus
import com.swaptr.aide.domain.model.ModelSummary
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ModelListRow(
    summary: ModelSummary,
    isLoading: Boolean,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
    compact: Boolean = false,
) {
    val inProgress = summary.downloadStatus as? DownloadStatus.InProgress
    val completed = summary.downloadStatus as? DownloadStatus.Completed
    val entry = when {
        compact -> AppMenuEntry(
            title = summary.spec.displayName,
            subtitleContent = { CompactSubtitle(summary, isLoading) },
            onClick = onClick,
            trailing = trailing,
        )
        inProgress != null && summary.spec.requiresDownload && !isLoading -> AppMenuEntry(
            title = summary.spec.displayName,
            subtitleContent = { DownloadingSubtitle(summary, inProgress) },
            onClick = onClick,
            trailing = trailing,
        )
        completed != null && summary.spec.requiresDownload && !isLoading -> AppMenuEntry(
            title = summary.spec.displayName,
            subtitleContent = { CompletedSubtitle(summary) },
            onClick = onClick,
            trailing = trailing,
        )
        else -> AppMenuEntry(
            title = summary.spec.displayName,
            subtitle = modelSubtitle(summary, isLoading),
            onClick = onClick,
            trailing = trailing,
        )
    }
    AppMenuList(items = listOf(entry))
}

@Composable
private fun CompactSubtitle(summary: ModelSummary, isLoading: Boolean) {
    val isLocal = ProviderTier.of(summary.spec) == ProviderTier.LOCAL
    val type = typeLabel(summary)
    val status = when {
        isLoading -> "Loading…"
        summary.isLoadedInEngine -> if (isLocal) "Loaded in RAM" else "Active"
        summary.downloadStatus is DownloadStatus.Completed -> "Ready"
        else -> when (val s = summary.downloadStatus) {
            is DownloadStatus.InProgress -> "Downloading ${(s.progress * 100).roundToInt()}%"
            is DownloadStatus.Paused -> "Paused"
            is DownloadStatus.Queued -> "Queued"
            is DownloadStatus.Failed -> "Failed"
            else -> "Ready"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (summary.isLoadedInEngine) {
            Icon(
                painter = painterResource(R.drawable.ic_lc_circle_check),
                contentDescription = "Currently selected",
                tint = Color(0xFF22C55E),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = "$status · $type",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompletedSubtitle(summary: ModelSummary) {
    val spec = summary.spec
    // Build the leading run dynamically so a remote model with no `params` / `sizeBytes`
    // doesn't render an orphan separator like "Default · ".
    val lead = listOfNotNull(
        "Default".takeIf { summary.isDefault },
        spec.params.takeIf { it.isNotBlank() },
        spec.sizeBytes?.let { humanBytes(it) },
    ).joinToString(" · ")
    val leadWithTrailingSep = if (lead.isEmpty()) "" else "$lead · "
    val statusLabel = if (summary.isLoadedInEngine) "Loaded in RAM" else "Ready"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = leadWithTrailingSep,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painter = painterResource(R.drawable.ic_lc_circle_check),
            contentDescription = null,
            tint = Color(0xFF22C55E),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DownloadingSubtitle(summary: ModelSummary, s: DownloadStatus.InProgress) {
    val prefix = if (summary.isDefault) "Default · " else ""
    val pct = (s.progress.coerceIn(0f, 1f) * 100).roundToInt()
    val remaining = if (s.totalBytes > 0) {
        " · ${humanBytes((s.totalBytes - s.downloadedBytes).coerceAtLeast(0))} left"
    } else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_lc_download),
            contentDescription = null,
            tint = Color(0xFF22C55E),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$prefix$pct% · ${humanBytesPerSec(s.bytesPerSec)}$remaining",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun modelSubtitle(summary: ModelSummary, isLoading: Boolean): String {
    val spec = summary.spec
    // Drop empty segments so remote tags don't render orphan "Default · " separators.
    fun join(vararg tail: String?): String = (
        listOfNotNull(
            "Default".takeIf { summary.isDefault },
            spec.params.takeIf { it.isNotBlank() },
            spec.sizeBytes?.let { humanBytes(it) },
        ) + tail.filterNotNull().filter { it.isNotBlank() }
    ).joinToString(" · ")

    if (isLoading) return join("Loading…")
    if (!spec.requiresDownload) {
        return if (summary.isLoadedInEngine) join("Active", typeLabel(summary)) else join(typeLabel(summary))
    }
    return when (val s = summary.downloadStatus) {
        is DownloadStatus.Idle, is DownloadStatus.Cancelled -> join()
        is DownloadStatus.Queued -> join("Queued")
        is DownloadStatus.InProgress -> {
            val pct = (s.progress.coerceIn(0f, 1f) * 100).roundToInt()
            val remaining = if (s.totalBytes > 0) {
                "${humanBytes((s.totalBytes - s.downloadedBytes).coerceAtLeast(0))} left"
            } else null
            join("Downloading $pct%", humanBytesPerSec(s.bytesPerSec), remaining)
        }
        is DownloadStatus.Finalizing -> join(s.stage.label)
        is DownloadStatus.Paused -> {
            val pct = if (s.totalBytes > 0) {
                ((s.downloadedBytes.toDouble() / s.totalBytes) * 100).roundToInt()
            } else 0
            join("Paused $pct%", "${humanBytes(s.downloadedBytes)} / ${humanBytes(s.totalBytes)}")
        }
        is DownloadStatus.Completed ->
            if (summary.isLoadedInEngine) join("Loaded in RAM", typeLabel(summary)) else join("Ready")
        is DownloadStatus.Failed -> join("Failed: ${s.message}")
    }
}

private fun typeLabel(summary: ModelSummary): String =
    when (ProviderTier.of(summary.spec)) {
        ProviderTier.LOCAL -> summary.loadedAccelerator?.label ?: "On-device"
        ProviderTier.OLLAMA_SELF -> "Ollama (self-hosted)"
        ProviderTier.OLLAMA_CLOUD -> "Ollama (cloud)"
    }

internal fun humanBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var b = bytes.toDouble() / 1024.0
    var i = 0
    while (b >= 1024.0 && i < units.lastIndex) { b /= 1024.0; i++ }
    return String.format(Locale.US, "%.1f %s", b, units[i])
}

internal fun humanBytesPerSec(bps: Long): String =
    if (bps <= 0) "—" else "${humanBytes(bps)}/s"
