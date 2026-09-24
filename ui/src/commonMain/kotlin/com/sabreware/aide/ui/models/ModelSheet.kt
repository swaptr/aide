package com.sabreware.aide.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AidePill
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppLinearProgress
import com.sabreware.aide.core.designsystem.browse.ActionRail
import com.sabreware.aide.core.designsystem.browse.ActionRunner
import com.sabreware.aide.core.designsystem.browse.SheetDismiss
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ImageModelSpec
import com.sabreware.aide.core.domain.model.ModelMetadata
import com.sabreware.aide.core.domain.speech.BuiltInSpeechModel
import com.sabreware.aide.core.domain.speech.CloudSpeechModelSpec
import com.sabreware.aide.ui.labels.LabelEditor

/**
 * Which model's sheet is open, and which chat model's sampler — per surface, saved with it. A tap on ANY model
 * in ANY list opens [ModelSheetHost]'s sheet over the page: one component, never a navigation, whatever the
 * model is (on-device or cloud, chat, voice or image).
 */
@Stable
class ModelSheets internal constructor(openId: String?) {
    var openId: String? by mutableStateOf(openId)
        internal set
    internal var sampler: ChatModelSpec? by mutableStateOf(null)

    fun open(item: LibraryItem) { openId = item.id }
    fun close() { openId = null }
    fun openSampler(spec: ChatModelSpec) { sampler = spec }
}

@Composable
fun rememberModelSheets(): ModelSheets = rememberSaveable(
    saver = androidx.compose.runtime.saveable.Saver(save = { it.openId.orEmpty() }, restore = { ModelSheets(it.ifEmpty { null }) }),
) { ModelSheets(null) }

/**
 * Draws the open model sheet (and the sampler it can open). [library] is the page's live list, so the sheet
 * tracks the model as it changes — a download's progress, a rename, a pin — while it is open.
 */
@Composable
internal fun ModelSheetHost(
    sheets: ModelSheets,
    library: List<LibraryItem>,
    runner: ActionRunner<LibraryItem>,
    editor: LabelEditor,
    vm: ModelsViewModel,
) {
    val item = sheets.openId?.let { id -> library.firstOrNull { it.id == id } }
    if (item != null) {
        AppDialog(
            onDismiss = sheets::close,
            title = item.name,
            subtitle = item.originalName ?: item.source.name,
            trailingActions = listOf(
                pinHeaderAction(item.pinned) { editor.viewModel.setPinned(listOf(item.subject), !item.pinned) },
            ),
        ) { controller ->
            ModelDetails(item, runner, editor, vm, dismiss = controller::close)
        }
    }
    sheets.sampler?.let { spec ->
        val overrides by vm.samplerOverrides.collectAsStateWithLifecycle()
        SamplerSheet(
            spec = spec,
            current = overrides[spec.id],
            onSave = { vm.saveSamplerOverride(spec.id, it) },
            onDismiss = { sheets.sampler = null },
        )
    }
}

/**
 * Everything about one model, the SAME body for every kind — and for any host that shows a model. Top to
 * bottom: the actions rail (what can be done now), a download's progress, the user's tags, then the model's
 * facts. What differs by kind is only which facts there are: a chat model's capabilities and limits (fetched
 * from its provider where it has them), a voice's engine and language, an image model's sizes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelDetails(
    item: LibraryItem,
    runner: ActionRunner<LibraryItem>,
    editor: LabelEditor,
    vm: ModelsViewModel,
    dismiss: SheetDismiss? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Pin is in the header; Select belongs to a list, not to one model.
        // Inside the sheet, an action that leaves it (Connection) closes the sheet first, then navigates.
        ActionRail(runner, item, except = setOf("pin", "select"), dismiss = dismiss)
        val status = item.downloadStatus
        if (status is DownloadStatus.InProgress) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppLinearProgress(progress = { status.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(downloadProgressLabel(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            status?.let(::downloadLine)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        if (item.tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.tags.forEach { tag ->
                    AidePill(
                        onClick = { editor.editTags(listOf(item.subject), item.name) },
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                    ) { Text("#$tag", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        when (val payload = item.payload) {
            is LibraryItem.Payload.Chat -> {
                val spec = payload.summary.spec as? ChatModelSpec
                if (spec != null) {
                    // Provider-served facts (Ollama, models.dev, the app's own catalog), revalidated per open; the
                    // first frame paints the last answer or the spec's own facts, never empty rows.
                    val metadata by produceState<ModelMetadata?>(vm.metadataSnapshot(spec), spec.id) { value = vm.modelMetadata(spec) }
                    ModelDetailContent(spec = spec, metadata = metadata)
                }
            }
            is LibraryItem.Payload.Voice -> Facts(
                listOfNotNull(
                    "Engine" to payload.summary.spec.family.displayName,
                    "Language" to payload.summary.spec.locale,
                    payload.summary.spec.sizeBytes?.let { "Size" to humanBytes(it) },
                    "Runs" to "On this device",
                    "License" to payload.summary.spec.licenseName,
                ),
            )
            is LibraryItem.Payload.Ready -> when (val spec = payload.spec) {
                is CloudSpeechModelSpec -> Facts(
                    listOfNotNull(spec.blurb?.let { "About" to it }, "Served by" to item.source.name, "Runs" to item.kind.label),
                )
                is BuiltInSpeechModel -> Facts(listOf("About" to spec.blurb, "Runs" to "On this device"))
                is ImageModelSpec -> Facts(
                    listOfNotNull(
                        spec.description?.let { "About" to it },
                        "Served by" to item.source.name,
                        spec.capabilities.sizes.takeIf { it.isNotEmpty() }?.let { "Sizes" to it.joinToString(", ") },
                        "Formats" to spec.capabilities.outputFormats.joinToString(", ") { it.uppercase() },
                    ),
                )
                else -> Facts(listOf("Served by" to item.source.name))
            }
        }
    }
}

@Composable
private fun Facts(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (label, value) -> DetailRow(label, value) }
    }
}
