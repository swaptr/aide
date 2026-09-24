package com.sabreware.aide.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AidePill
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.MetadataSource
import com.sabreware.aide.core.domain.model.ModelDetail
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ModelMetadata
import kotlin.math.roundToInt

/**
 * The model detail body — capability chips + display facts + a "Use this model" action, driven by the
 * provider-served [ModelMetadata] (Ollama `/api/show` / models.dev / on-device allowlist). Chips render
 * immediately from the spec's capabilities and upgrade in place when [metadata] resolves, so there's no
 * empty flash. The model name is supplied by the hosting [com.sabreware.aide.core.designsystem.PageScaffold] title, so
 * it isn't repeated here. [footer] renders inside the scroll below the action (e.g. a management menu).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelDetailContent(
    spec: ChatModelSpec,
    metadata: ModelMetadata?,
    modifier: Modifier = Modifier,
    /** The page's actions, drawn FIRST — actions on top, the model's facts under them. */
    actions: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val caps = metadata?.capabilities ?: spec.capabilities
    val detail = metadata?.detail
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        actions()
        subtitle(spec, detail)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            capabilityChips(caps).forEach { CapabilityChip(it) }
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            detailRows(detail).forEach { (label, value) -> DetailRow(label, value) }
        }


        footer()
    }
}

@Composable
private fun CapabilityChip(label: String) {
    AidePill(
        onClick = null,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

// Family and size in plain words ("deepseek4 · 304B parameters"). The precision a file was packed at (FP8, Q4)
// is a detail for experts and stays out of the headline.
private fun subtitle(spec: ChatModelSpec, detail: ModelDetail?): String? = listOfNotNull(
    detail?.family ?: spec.family.takeIf { it.isNotBlank() },
    (detail?.parameterSize ?: spec.params.takeIf { it.isNotBlank() })?.let(::parameterLabel),
).joinToString(" · ").takeIf { it.isNotBlank() }

/** "304180418494" or "304B" → "304B parameters"; anything unparseable is shown as given. */
private fun parameterLabel(raw: String): String {
    val count = raw.trim().toLongOrNull() ?: return if (raw.trim().last().isLetter()) "${raw.trim()} parameters" else raw
    val (value, unit) = when {
        count >= 1_000_000_000_000 -> count / 1e12 to "T"
        count >= 1_000_000_000 -> count / 1e9 to "B"
        count >= 1_000_000 -> count / 1e6 to "M"
        else -> return "$count parameters"
    }
    val shown = if (value >= 10) value.roundToInt().toString() else oneDecimal(value)
    return "$shown$unit parameters"
}

// The same capability names, in the same order, as the filter and the automatic tags (ModelTraits).
private fun capabilityChips(caps: ChatCapabilities): List<String> = capabilityTags(caps).ifEmpty { listOf("Text") }

private fun detailRows(detail: ModelDetail?): List<Pair<String, String>> = buildList {
    if (detail == null) return@buildList
    detail.contextTokens?.let { add("Reads up to" to "${formatTokens(it)} tokens") }
    detail.maxOutputTokens?.let { add("Longest reply" to "${formatTokens(it)} tokens") }
    detail.inputModalities.takeIf { it.isNotEmpty() }
        ?.let { mods -> add("Understands" to mods.joinToString(", ") { it.name.lowercase() }.replaceFirstChar(Char::uppercase)) }
    detail.cost?.let { c ->
        val parts = listOfNotNull(
            c.inputPerMTok?.let { "${usd2(it)} in" },
            c.outputPerMTok?.let { "${usd2(it)} out" },
        )
        if (parts.isNotEmpty()) add("Price per million tokens" to parts.joinToString(" · "))
    }
    detail.knowledgeCutoff?.let { add("Knows events up to" to it) }
    detail.releaseDate?.let { add("Released" to it) }
    detail.license?.let { add("License" to it) }
    detail.sizeBytes?.let { add("Size" to humanBytes(it)) }
    sourceLabel(detail.source)?.let { add("Details from" to it) }
}

private fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> {
        val m = n / 1_000_000.0
        if (m == m.toLong().toDouble()) "${m.toLong()}M" else "${oneDecimal(m)}M"
    }
    n >= 1_000 -> "${n / 1_000}K"
    else -> n.toString()
}

// commonMain-safe number formatting (String.format(Locale) is JVM-only).
// usd2(1.5) → "$1.50"; usd2(2.0) → "$2.00".
private fun usd2(v: Double): String {
    val r = (v * 100).roundToInt()
    val whole = r / 100
    val frac = ((r % 100) + 100) % 100
    return "$$whole.${frac.toString().padStart(2, '0')}"
}

// oneDecimal(1.5) → "1.5".
private fun oneDecimal(v: Double): String {
    val r = (v * 10).roundToInt()
    return "${r / 10}.${((r % 10) + 10) % 10}"
}

private fun sourceLabel(source: MetadataSource): String? = when (source) {
    MetadataSource.OLLAMA -> "Ollama"
    MetadataSource.MODELS_DEV -> "models.dev"
    MetadataSource.ALLOWLIST -> "App catalog"
    MetadataSource.NEUTRAL -> null
}
