package com.swaptr.aide.ui.models

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swaptr.aide.data.catalog.ModelSpec
import com.swaptr.aide.data.catalog.ProviderTier
import com.swaptr.aide.data.download.DownloadController
import com.swaptr.aide.data.model.ModelRegistryRepository
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.speech.SpeechAssetKind
import com.swaptr.aide.data.speech.SpeechAssetRegistry
import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.domain.usecase.CancelDownloadUseCase
import com.swaptr.aide.domain.usecase.DeleteModelUseCase
import com.swaptr.aide.domain.usecase.DownloadModelUseCase
import com.swaptr.aide.domain.usecase.LoadModelUseCase
import com.swaptr.aide.domain.usecase.ObserveModelsUseCase
import com.swaptr.aide.domain.usecase.PauseDownloadUseCase
import com.swaptr.aide.domain.usecase.UnloadModelUseCase
import com.swaptr.aide.permission.RuntimePermissionGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelsViewModel @Inject constructor(
    application: Application,
    observeModels: ObserveModelsUseCase,
    private val downloadModel: DownloadModelUseCase,
    private val pauseDownload: PauseDownloadUseCase,
    private val cancelDownload: CancelDownloadUseCase,
    private val deleteModel: DeleteModelUseCase,
    private val loadModel: LoadModelUseCase,
    private val unloadModel: UnloadModelUseCase,
    private val downloadController: DownloadController,
    private val prefs: UserPreferencesRepository,
    speechAssets: SpeechAssetRegistry,
    registry: ModelRegistryRepository,
    private val permissionGate: RuntimePermissionGate,
) : AndroidViewModel(application) {

    // Fire-and-forget; download FGS posts its notification either way.
    fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (permissionGate.isGranted(android.Manifest.permission.POST_NOTIFICATIONS)) return
        viewModelScope.launch {
            permissionGate.request(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val _uiState = MutableStateFlow(
        ModelsUiState(deviceTotalRamGb = totalRamGb(application)),
    )
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    val lastUsedByTier: StateFlow<Map<ProviderTier, String>> = registry.lastUsedByTier

    init {
        observeModels()
            .onEach { rows -> _uiState.update { it.copy(rows = rows) } }
            .launchIn(viewModelScope)
        speechAssets.observe()
            .onEach { voice -> _uiState.update { it.copy(voiceRows = voice) } }
            .launchIn(viewModelScope)
    }

    fun startOrResumeDownload(spec: ModelSpec) = downloadModel(spec)
    fun pauseDownload(spec: ModelSpec) = pauseDownload.invoke(spec)
    fun cancelDownload(spec: ModelSpec) = cancelDownload.invoke(spec)

    fun delete(spec: ModelSpec) {
        viewModelScope.launch { deleteModel(spec) }
    }

    fun load(spec: ModelSpec) {
        if (_uiState.value.loadingModelId != null) return
        _uiState.update { it.copy(loadingModelId = spec.id, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching { loadModel(spec) }
            result.onFailure { t ->
                android.util.Log.e("Aide", "load(${spec.id}) failed", t)
                _uiState.update {
                    it.copy(
                        loadingModelId = null,
                        errorMessage = "Load failed: ${t.message ?: t::class.java.simpleName}",
                    )
                }
            }.onSuccess {
                _uiState.update { it.copy(loadingModelId = null) }
            }
        }
    }

    fun unload(spec: ModelSpec) {
        viewModelScope.launch { unloadModel(spec) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun startOrResumeVoice(spec: SpeechAssetSpec) {
        downloadController.enqueueSpeech(spec)
    }

    fun pauseVoice(spec: SpeechAssetSpec) {
        downloadController.pauseSpeech(spec.id)
    }

    fun cancelVoice(spec: SpeechAssetSpec) {
        downloadController.cancelSpeech(spec)
    }

    fun deleteVoice(spec: SpeechAssetSpec) {
        downloadController.cancelSpeech(spec)
    }

    // Engines re-read active id per call so the next mic tap loads the new bundle.
    fun setActiveVoice(spec: SpeechAssetSpec) {
        viewModelScope.launch {
            when (spec.kind) {
                SpeechAssetKind.STT -> prefs.setActiveSttModelId(spec.id)
                SpeechAssetKind.TTS -> prefs.setActiveTtsModelId(spec.id)
                SpeechAssetKind.VAD -> Unit
            }
        }
    }

    private fun totalRamGb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024L * 1024L * 1024L)).toInt()
    }
}
