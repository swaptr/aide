package com.sabreware.aide.ui.models

import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelDescriptor
import com.sabreware.aide.core.domain.model.ModelSummary
import com.sabreware.aide.core.domain.speech.BuiltInSpeechModel
import com.sabreware.aide.core.domain.speech.SpeechAssetSummary

data class ModelsUiState(
    /**
     * [UiState], not bare lists. Seeding empty lists made the Models home paint "No models in use yet"
     * before the registry's first emission; `Ready(emptyList())` is the empty state, `Loading` is not.
     */
    val rows: UiState<List<ModelSummary>> = UiState.Loading,
    val voiceRows: UiState<List<SpeechAssetSummary>> = UiState.Loading,
    /**
     * The cloud speech and image models the add-model page offers on its cloud tabs — static catalogs, so plain
     * lists: there is no first emission to wait for and nothing to download.
     */
    val cloudRows: List<ModelDescriptor> = emptyList(),
    /** Speech models built into the host (its recognizer and voice) — chosen like any other, never downloaded. */
    val builtInRows: List<BuiltInSpeechModel> = emptyList(),
    /** The active pick per non-chat modality (asr / tts / image), cloud or on-device id alike. */
    val activeByModality: Map<Modality, String?> = emptyMap(),
    val deviceTotalRamGb: Int = 0,
    val loadingModelId: String? = null,
    val errorMessage: String? = null,
)
