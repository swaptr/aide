package com.swaptr.aide.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swaptr.aide.R
import com.swaptr.aide.ui.common.AidePill

@Composable
fun ToolCallChip(
    toolName: String,
    isRunning: Boolean,
    hasError: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelTone = if (hasError) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f)
    val iconTone = if (hasError) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
    val chevronTone = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)

    AidePill(
        onClick = if (isRunning) null else onClick,
        modifier = modifier,
        contentColor = labelTone,
        leading = {
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = iconTone,
                    )
                } else {
                    Icon(
                        painter = painterResource(toolIconRes(toolName)),
                        contentDescription = null,
                        tint = iconTone,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        trailing = if (isRunning) null else ({
            Icon(
                painter = painterResource(R.drawable.ic_lc_chevron_right),
                contentDescription = "Open details",
                tint = chevronTone,
                modifier = Modifier.size(16.dp),
            )
        }),
    ) {
        Text(
            text = toolDisplayLabel(toolName),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = labelTone,
        )
    }
}

internal fun toolDisplayLabel(toolName: String): String = when (toolName) {
    "CurrentTime" -> "Check current time"
    "Calculator" -> "Run calculation"
    "WebSearch" -> "Search the web"
    "WebFetch" -> "Read webpage"
    "ListFiles" -> "List files"
    "FindFiles" -> "Find files"
    "FileInfo" -> "File info"
    "ReadFile" -> "Read file"
    "MakeDir" -> "Make folder"
    "MoveFile" -> "Move file"
    "CopyFile" -> "Copy file"
    "DeleteFile" -> "Delete file"
    else -> "Run $toolName"
}

@DrawableRes
internal fun toolIconRes(toolName: String): Int = when (toolName) {
    "CurrentTime" -> R.drawable.ic_lc_clock
    "Calculator" -> R.drawable.ic_lc_calculator
    "WebSearch" -> R.drawable.ic_lc_search
    "WebFetch" -> R.drawable.ic_lc_globe
    "ListFiles", "FindFiles", "FileInfo", "ReadFile",
    "MakeDir", "MoveFile", "CopyFile", "DeleteFile" -> R.drawable.ic_lc_folder
    else -> R.drawable.ic_lc_wrench
}
