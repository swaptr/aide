package com.swaptr.aide.ui.settings.websearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.provider.ProviderConfigRepository
import com.swaptr.aide.domain.search.WebSearchProvider
import com.swaptr.aide.domain.search.WebSearchProviderId
import com.swaptr.aide.domain.search.WebSearchResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// note subtitle lets us point at chat-side credential settings without duplicating the key field.
data class WebSearchProviderRow(
    val id: WebSearchProviderId,
    val displayName: String,
    val available: Boolean,
    val note: String?,
)

data class WebSearchSettingsUiState(
    val override: WebSearchProviderId?,
    val activeResolved: WebSearchProviderId?,
    val rows: List<WebSearchProviderRow>,
)

@HiltViewModel
class WebSearchSettingsViewModel @Inject constructor(
    private val resolver: WebSearchResolver,
    private val prefs: UserPreferencesRepository,
    private val providerConfig: ProviderConfigRepository,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    val uiState: StateFlow<WebSearchSettingsUiState> = combine(
        prefs.webSearchProviderOverrideFlow,
        providerConfig.ollamaConfigFlow.map { it?.authToken?.isNotBlank() == true },
        refreshTick,
    ) { override, ollamaHasKey, _ ->
        val providers: List<WebSearchProvider> = resolver.allProviders()
        val rows = providers.map { p ->
            WebSearchProviderRow(
                id = p.id,
                displayName = p.displayName,
                available = isAvailable(p, ollamaHasKey),
                note = noteFor(p.id, ollamaHasKey),
            )
        }
        val active = resolver.resolve().id
        WebSearchSettingsUiState(
            override = override,
            activeResolved = active,
            rows = rows,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WebSearchSettingsUiState(override = null, activeResolved = null, rows = emptyList()),
    )

    fun setOverride(id: WebSearchProviderId?) {
        viewModelScope.launch { prefs.setWebSearchProviderOverride(id) }
    }

    // Special-cases avoid spawning a flow per provider; cheap & deterministic from data in scope.
    private fun isAvailable(provider: WebSearchProvider, ollamaHasKey: Boolean): Boolean =
        when (provider.id) {
            WebSearchProviderId.DUCKDUCKGO -> true
            WebSearchProviderId.OLLAMA -> ollamaHasKey
            else -> false
        }

    private fun noteFor(id: WebSearchProviderId, ollamaHasKey: Boolean): String? = when (id) {
        WebSearchProviderId.DUCKDUCKGO -> "No API key required. Used as a fallback."
        WebSearchProviderId.OLLAMA ->
            if (ollamaHasKey) "Uses your Ollama Cloud API key (set in Models)."
            else "Add an Ollama Cloud API key in Models to enable."
        else -> null
    }
}
