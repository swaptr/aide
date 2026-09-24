package com.sabreware.aide.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.resources.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.painterResource

private val PrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
}

/**
 * What a tool call did, in plain words first — whether it worked and what it was asked — with the raw
 * input and output folded under Details for anyone who wants them.
 */
@Composable
fun ToolCallDetailSheet(
    toolName: String,
    argsJson: String,
    resultJson: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val inputs = remember(argsJson) { plainInputs(argsJson) }
    AppDialog(
        onDismiss = onDismiss,
        title = toolDisplayLabel(toolName),
    ) { _ ->
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppMenu(
                items = listOf(
                    AppMenuEntry(
                        key = "status",
                        title = if (error != null) "Didn't work" else "Done",
                        subtitle = error,
                        leadingIconRes = if (error != null) Res.drawable.ic_lc_circle_alert else Res.drawable.ic_lc_circle_check,
                    ),
                ) + inputs.map { (name, value) -> AppMenuEntry(key = "in-$name", title = name, subtitle = value) } +
                    AppMenuEntry(
                        key = "details",
                        title = "Details",
                        leadingIconRes = if (detailsOpen) Res.drawable.ic_lc_chevron_down else Res.drawable.ic_lc_chevron_right,
                        onClick = { detailsOpen = !detailsOpen },
                    ),
            )
            if (detailsOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    JsonSection(label = "Input", json = argsJson, error = false)
                    if (error != null) {
                        JsonSection(label = "Error", json = resultJson ?: error, error = true)
                    } else if (resultJson != null) {
                        JsonSection(label = "Output", json = resultJson, error = false)
                    }
                }
            }
        }
    }
}

/**
 * The call's simple inputs as (name, value) pairs — "query" to "weather in Pune" — for the summary. Anything
 * nested stays in Details; a value too long for one row is cut.
 */
private fun plainInputs(argsJson: String): List<Pair<String, String>> {
    val args = runCatching { PrettyJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: return emptyList()
    return args.entries.mapNotNull { (key, value) ->
        val text = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        humanKey(key) to if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) + "…" else text
    }
}

/** "maxResults" / "max_results" read as "Max results". */
private fun humanKey(key: String): String {
    val spaced = key.replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().trim()
    return spaced.replaceFirstChar { it.uppercaseChar() }
}

private const val MAX_INPUT_CHARS = 200

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
                    painter = painterResource(Res.drawable.ic_lc_copy),
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
