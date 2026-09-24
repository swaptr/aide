package com.sabreware.aide.ui.settings.websearch

import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.state.stateInUi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.peek
import com.sabreware.aide.core.domain.search.SearchPrefs
import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResolver
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

data class WebSearchProviderRow(
    val id: WebSearchProviderId,
    val displayName: String,
    val requiresKey: Boolean,
    val hasKey: Boolean,
    val available: Boolean,
    val note: String?,
)

data class WebSearchSettingsUiState(
    val override: WebSearchProviderId?,
    /** Loading until the encrypted key store has answered which providers have a key. */
    val rows: UiState<List<WebSearchProviderRow>>,
    /** How many rows will land — known up front, so the loading state holds the sheet at its final height. */
    val providerCount: Int,
)

class WebSearchSettingsViewModel(
    private val resolver: WebSearchResolver,
    private val prefs: PreferenceStore,
    private val credentials: WebSearchCredentialsRepository,
) : ViewModel() {

    private val providerCount = resolver.allProviders().size

    // Which providers have a key comes from the credentials repo's app-wide cache — shared with the web
    // tool, so an open after the session's first read paints its rows on the first frame.
    val uiState: StateFlow<WebSearchSettingsUiState> =
        combine(prefs.flow(SearchPrefs.ProviderOverride), credentials.configured.filterNotNull(), ::state)
            .stateInUi(
                viewModelScope,
                state(prefs.peek(SearchPrefs.ProviderOverride), credentials.configured.value),
            ) { state(null, emptySet()) }

    /** [withKey] null = the key store has not answered: rows stay Loading, sized to [providerCount]. */
    private fun state(override: WebSearchProviderId?, withKey: Set<WebSearchProviderId>?) = WebSearchSettingsUiState(
        override = override,
        rows = withKey?.let { UiState.Ready(rows(it)) } ?: UiState.Loading,
        providerCount = providerCount,
    )

    private fun rows(withKey: Set<WebSearchProviderId>) = resolver.allProviders().map { p ->
        val requiresKey = p.id != WebSearchProviderId.DUCKDUCKGO
        val hasKey = p.id in withKey
        WebSearchProviderRow(
            id = p.id,
            displayName = p.displayName,
            requiresKey = requiresKey,
            hasKey = hasKey,
            available = !requiresKey || hasKey,
            note = noteFor(p.id, hasKey),
        )
    }

    fun setOverride(id: WebSearchProviderId?) {
        viewModelScope.launch { prefs.set(SearchPrefs.ProviderOverride, id) }
    }

    /** Persist (or clear, when blank) a provider's API key in the dedicated encrypted credential store. */
    fun setKey(id: WebSearchProviderId, value: String) {
        viewModelScope.launch { credentials.setApiKey(id, value.trim()) }
    }

    private fun noteFor(id: WebSearchProviderId, hasKey: Boolean): String? = when (id) {
        WebSearchProviderId.DUCKDUCKGO -> "No API key required. Used as a fallback."
        WebSearchProviderId.OLLAMA ->
            if (hasKey) "Ollama Cloud key set." else "Add your Ollama Cloud API key (ollama.com) below to enable."
        else -> if (hasKey) "Key set." else "Add an API key below to enable."
    }
}
