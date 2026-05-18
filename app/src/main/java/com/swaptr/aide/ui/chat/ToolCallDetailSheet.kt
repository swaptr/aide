package com.swaptr.aide.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.swaptr.aide.R
import com.swaptr.aide.ui.common.AppSheet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val PrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
}

@Composable
fun ToolCallDetailSheet(
    toolName: String,
    argsJson: String,
    resultJson: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    AppSheet(
        onDismiss = onDismiss,
        title = "${toolDisplayLabel(toolName)}: $toolName",
    ) { _ ->
        JsonSection(
            label = "Input",
            json = argsJson,
            error = false,
        )

        if (error != null) {
            JsonSection(
                label = "Error",
                json = resultJson ?: error,
                error = true,
            )
        } else if (resultJson != null) {
            JsonSection(
                label = "Output",
                json = resultJson,
                error = false,
            )
        }
    }
}

@Composable
private fun JsonSection(label: String, json: String, error: Boolean) {
    val pretty = remember(json) { prettyPrintOrRaw(json) }
    // Sticking with deprecated LocalClipboardManager — suspend replacement needs CoroutineScope.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val tone = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = tone,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(pretty)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lc_copy),
                    contentDescription = "Copy",
                    tint = tone,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = pretty,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

private fun prettyPrintOrRaw(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        val element: JsonElement = PrettyJson.parseToJsonElement(raw)
        PrettyJson.encodeToString(JsonElement.serializer(), element)
    }.getOrDefault(raw)
}

@Suppress("unused")
private val UnusedPadding = PaddingValues(0.dp)
