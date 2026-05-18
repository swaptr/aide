package com.swaptr.aide.ui.models

import com.swaptr.aide.domain.model.ModelSummary
import com.swaptr.aide.domain.speech.SpeechAssetSummary

data class ModelsUiState(
    val rows: List<ModelSummary> = emptyList(),
    val voiceRows: List<SpeechAssetSummary> = emptyList(),
    val deviceTotalRamGb: Int = 0,
    val loadingModelId: String? = null,
    val errorMessage: String? = null,
)
