package com.swaptr.aide.ui.models.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaptr.aide.data.catalog.ProviderTier
import com.swaptr.aide.data.model.ModelRegistryRepository
import com.swaptr.aide.data.provider.ConnectionTestResult
import com.swaptr.aide.data.provider.OllamaConfig
import com.swaptr.aide.data.provider.ProviderConfigRepository
import com.swaptr.aide.domain.llm.ollama.OllamaManagement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Credentials persist via ProviderConfigRepository (Tink prefs); management is process-scoped state only.
@HiltViewModel
class ProviderSettingsViewModel @Inject constructor(
    private val configRepo: ProviderConfigRepository,
    private val registryRepo: ModelRegistryRepository,
    private val ollamaManagement: OllamaManagement,
) : ViewModel() {

    val ollamaConfig: StateFlow<OllamaConfig?> = configRepo.ollamaConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ollamaRefreshing: StateFlow<Boolean> = registryRepo.ollamaRefreshing
    val ollamaTagsFetchedAt: StateFlow<Long?> = registryRepo.ollamaTagsFetchedAt
    val lastUsedByTier: StateFlow<Map<ProviderTier, String>> =
        registryRepo.lastUsedByTier

    private val _testInFlight = MutableStateFlow(false)
    val testInFlight: StateFlow<Boolean> = _testInFlight.asStateFlow()

    private val _lastTestResult = MutableStateFlow<ConnectionTestResult?>(null)
    val lastTestResult: StateFlow<ConnectionTestResult?> = _lastTestResult.asStateFlow()

    private val _pullState = MutableStateFlow<PullState>(PullState.Idle)
    val pullState: StateFlow<PullState> = _pullState.asStateFlow()
    private var pullJob: Job? = null

    fun saveOllama(config: OllamaConfig) {
        viewModelScope.launch {
            configRepo.setOllamaConfig(config)
            registryRepo.refreshOllamaTags()
        }
    }

    fun clearOllama() {
        viewModelScope.launch {
            configRepo.clearOllamaConfig()
            _lastTestResult.value = null
        }
    }

    fun testOllama() {
        viewModelScope.launch {
            _testInFlight.value = true
            _lastTestResult.value = ollamaManagement.testConnection()
            _testInFlight.value = false
        }
    }

    fun refreshOllamaTags() {
        registryRepo.refreshOllamaTags()
    }

    // Management surface refreshes catalog on success — no extra refresh call here.
    fun pullModel(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        pullJob?.cancel()
        _pullState.value = PullState.InProgress(status = "queued", percent = null)
        pullJob = viewModelScope.launch {
            ollamaManagement.pullModel(trimmed)
                .catch { _pullState.value = PullState.Failed(it.message ?: "pull failed") }
                .collect { progress ->
                    when {
                        progress.error != null -> _pullState.value = PullState.Failed(progress.error)
                        progress.terminal && progress.status == "success" ->
                            _pullState.value = PullState.Done(trimmed)
                        else -> _pullState.value = PullState.InProgress(
                            status = progress.status,
                            percent = progress.percent,
                        )
                    }
                }
        }
    }

    fun cancelPull() {
        pullJob?.cancel()
        pullJob = null
        _pullState.value = PullState.Idle
    }

    fun resetPullState() {
        _pullState.value = PullState.Idle
    }
}

sealed interface PullState {
    data object Idle : PullState
    data class InProgress(val status: String, val percent: Float?) : PullState
    data class Failed(val message: String) : PullState
    data class Done(val name: String) : PullState
}
