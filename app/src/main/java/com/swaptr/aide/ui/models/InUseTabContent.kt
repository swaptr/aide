package com.swaptr.aide.ui.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swaptr.aide.data.speech.SpeechAssetKind
import com.swaptr.aide.domain.model.ModelSummary
import com.swaptr.aide.domain.speech.SpeechAssetSummary

@Composable
fun InUseTabContent(
    llmRows: List<ModelSummary>,
    voiceRows: List<SpeechAssetSummary>,
    loadingModelId: String?,
    modelActions: ModelLibraryActions,
    voiceActions: SpeechLibraryActions,
    onRowClick: ((ModelSummary) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val activeStt = voiceRows.firstOrNull {
        it.spec.kind == SpeechAssetKind.STT && it.isActive && it.isDownloaded
    }
    val activeTts = voiceRows.firstOrNull {
        it.spec.kind == SpeechAssetKind.TTS && it.isActive && it.isDownloaded
    }

    if (llmRows.isEmpty() && activeStt == null && activeTts == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No models in use. Tap Use on a model in Local or Cloud.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (llmRows.isNotEmpty()) {
            item(key = "section-in-use-llm") { SectionHeaderText("Language models") }
            items(llmRows, key = { "in-use-llm-${it.spec.id}" }) { summary ->
                ModelLibraryRow(
                    summary = summary,
                    isLoading = loadingModelId == summary.spec.id,
                    actions = modelActions,
                    onRowClick = onRowClick,
                    compact = true,
                )
            }
        }
        if (activeStt != null) {
            item(key = "section-in-use-stt") { SectionHeaderText("Speech recognition") }
            item(key = "in-use-stt-${activeStt.spec.id}") {
                SpeechLibraryRow(summary = activeStt, actions = voiceActions)
            }
        }
        if (activeTts != null) {
            item(key = "section-in-use-tts") { SectionHeaderText("Voice") }
            item(key = "in-use-tts-${activeTts.spec.id}") {
                SpeechLibraryRow(summary = activeTts, actions = voiceActions)
            }
        }
    }
}

@Composable
private fun SectionHeaderText(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
