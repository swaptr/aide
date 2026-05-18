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
fun LocalTabContent(
    llmRows: List<ModelSummary>,
    voiceRows: List<SpeechAssetSummary>,
    loadingModelId: String?,
    modelActions: ModelLibraryActions,
    voiceActions: SpeechLibraryActions,
    modifier: Modifier = Modifier,
) {
    val stt = voiceRows.filter { it.spec.kind == SpeechAssetKind.STT }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.spec.displayName })
    val tts = voiceRows.filter { it.spec.kind == SpeechAssetKind.TTS }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.spec.displayName })
    val vad = voiceRows.filter { it.spec.kind == SpeechAssetKind.VAD }

    val llmSorted = llmRows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.spec.displayName })

    if (llmSorted.isEmpty() && voiceRows.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No on-device models in the catalog.",
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
        if (llmSorted.isNotEmpty()) {
            item(key = "section-llm") { SectionHeader("Language models") }
            items(llmSorted, key = { "llm-${it.spec.id}" }) { summary ->
                ModelLibraryRow(
                    summary = summary,
                    isLoading = loadingModelId == summary.spec.id,
                    actions = modelActions,
                    onRowClick = null,
                    compact = false,
                )
            }
        }
        if (stt.isNotEmpty()) {
            item(key = "section-stt") { SectionHeader("Speech recognition (STT)") }
            items(stt, key = { "voice-${it.spec.id}" }) { summary ->
                SpeechLibraryRow(summary = summary, actions = voiceActions)
            }
        }
        if (tts.isNotEmpty()) {
            item(key = "section-tts") { SectionHeader("Text-to-speech voices") }
            items(tts, key = { "voice-${it.spec.id}" }) { summary ->
                SpeechLibraryRow(summary = summary, actions = voiceActions)
            }
        }
        if (vad.isNotEmpty()) {
            item(key = "section-vad") { SectionHeader("Voice activity detection") }
            items(vad, key = { "voice-${it.spec.id}" }) { summary ->
                SpeechLibraryRow(summary = summary, actions = voiceActions)
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
