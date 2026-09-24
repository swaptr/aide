package com.sabreware.aide.ui.settings.speech

import com.sabreware.aide.core.domain.connection.ProviderDirectory
import com.sabreware.aide.core.domain.connection.ProviderInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.selectState
import com.sabreware.aide.core.domain.speech.speechProvider
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.speech.SpeechPrefs
import com.sabreware.aide.core.domain.speech.SpeechProviderRegistry
import com.sabreware.aide.core.domain.speech.setSpeechProviderPreference
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SpeechSettingsViewModel(
    private val prefs: PreferenceStore,
    speechProviders: SpeechProviderRegistry,
    private val directory: ProviderDirectory,
) : ViewModel() {

    /**
     * Every speech engine the picker offers: the on-device ones this application contributed, then each
     * connection that speaks — live, so a connection added or renamed elsewhere shows here at once.
     */
    val providers: StateFlow<List<ProviderInfo>> =
        combine(speechProviders.flow, directory.connections) { providers, _ ->
            providers.orEmpty().map { directory.infoOf(it.id) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), speechProviders.all.map { directory.infoOf(it.id) })

    /** How [id] is named — the picked engine's title, even before the list above has emitted. */
    fun infoOf(id: ProviderId): ProviderInfo = directory.infoOf(id)

    data class UiState(
        val providerPreference: ProviderId? = null,
        val mainChatDictationEnabled: Boolean = true,
    )

    val state: StateFlow<UiState> = prefs.selectState(viewModelScope) { p ->
        UiState(
            providerPreference = p.speechProvider,
            mainChatDictationEnabled = p[SpeechPrefs.MainChatDictationEnabled],
        )
    }

    fun setProviderPreference(id: ProviderId?) {
        viewModelScope.launch { prefs.setSpeechProviderPreference(id) }
    }

    fun setMainChatDictationEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.set(SpeechPrefs.MainChatDictationEnabled, enabled) }
    }
}
