package com.swaptr.aide.ui.models

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
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
import com.swaptr.aide.data.download.DownloadStatus
import kotlin.math.roundToInt

@Composable
fun DownloadingSubtitleRow(progress: DownloadStatus.InProgress) {
    val pct = (progress.progress.coerceIn(0f, 1f) * 100).roundToInt()
    val remaining = if (progress.totalBytes > 0) {
        " · ${humanBytes((progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0))} left"
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
            text = "$pct% · ${humanBytesPerSec(progress.bytesPerSec)}$remaining",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ReadySubtitleRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_lc_circle_check),
            contentDescription = null,
            tint = Color(0xFF22C55E),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun FinalizingSubtitleRow(stage: DownloadStatus.Finalizing.Stage) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = Color(0xFF22C55E),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stage.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun pausedSubtitle(s: DownloadStatus.Paused): String {
    val pct = if (s.totalBytes > 0) {
        ((s.downloadedBytes.toDouble() / s.totalBytes) * 100).roundToInt()
    } else 0
    return "Paused $pct% · ${humanBytes(s.downloadedBytes)} / ${humanBytes(s.totalBytes)}"
}
