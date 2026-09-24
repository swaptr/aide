package com.sabreware.aide.ui.settings

import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.fakes.FakeProviderDirectory
import com.sabreware.aide.core.domain.model.ModelFallback
import com.sabreware.aide.core.domain.model.ModelFallbackPrefs
import com.sabreware.aide.core.domain.prefs.ChatFontStyle
import com.sabreware.aide.core.domain.prefs.ThemeMode
import com.sabreware.aide.core.domain.search.SearchPrefs
import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import com.sabreware.aide.core.domain.search.WebSearchProvider
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResolver
import com.sabreware.aide.core.domain.search.WebSearchResult
import com.sabreware.aide.core.domain.speech.SpeechPrefs
import com.sabreware.aide.core.domain.speech.SpeechProviderRegistry
import com.sabreware.aide.ui.settings.fonts.FontKeys
import com.sabreware.aide.ui.settings.speech.SpeechSettingsViewModel
import com.sabreware.aide.ui.settings.websearch.WebSearchSettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * The first frame of a settings surface is painted from the prefs snapshot, not the keys' defaults.
 *
 * Every assertion here reads `.value` straight after construction with NO collector and NO dispatch — the
 * state a screen composes with on its first frame. A seed of defaults passed every functional test while a
 * user with Dark theme or a disabled toggle watched the control animate from its default on each open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFirstFrameTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `settings open on the stored values, not the defaults`() {
        val prefs = FakePreferenceStore(
            AppearanceKeys.Theme to ThemeMode.Dark,
            FontKeys.Scale to 1.2f,
            FontKeys.ChatStyle to ChatFontStyle.Sans,
            ModelFallbackPrefs.Policy to ModelFallback.AnyModel,
        )

        val state = SettingsViewModel(prefs).uiState.value

        assertEquals(ThemeMode.Dark, state.themeMode)
        assertEquals(1.2f, state.fontScale)
        assertEquals(ChatFontStyle.Sans, state.chatFontStyle)
        assertEquals(ModelFallback.AnyModel, state.modelFallback)
    }

    @Test
    fun `voice settings open on the stored toggle`() {
        val prefs = FakePreferenceStore(SpeechPrefs.MainChatDictationEnabled to false)

        val state = SpeechSettingsViewModel(prefs, SpeechProviderRegistry(emptyList()), FakeProviderDirectory()).state.value

        assertFalse(state.mainChatDictationEnabled)
    }

    @Test
    fun `web search checks the stored override and holds its rows as loading, not empty`() {
        val prefs = FakePreferenceStore(SearchPrefs.ProviderOverride to WebSearchProviderId.BRAVE)

        val state = WebSearchSettingsViewModel(FakeResolver, prefs, NoKeys).uiState.value

        assertEquals(WebSearchProviderId.BRAVE, state.override, "Auto must not be checked for one frame")
        // Rows wait on the encrypted key store; an empty list would render as "no providers" and then grow.
        assertIs<UiState.Loading>(state.rows)
        assertEquals(FakeResolver.allProviders().size, state.providerCount)
    }

    private object FakeResolver : WebSearchResolver {
        private val providers = listOf(StubProvider(WebSearchProviderId.DUCKDUCKGO), StubProvider(WebSearchProviderId.BRAVE))
        override suspend fun resolve(): WebSearchProvider = providers.first()
        override fun activeProviderNameFlow(): Flow<String> = emptyFlow()
        override fun allProviders(): List<WebSearchProvider> = providers
    }

    private class StubProvider(override val id: WebSearchProviderId) : WebSearchProvider {
        override val displayName: String = id.name
        override suspend fun isAvailable(): Boolean = true
        override suspend fun search(query: String, max: Int): List<WebSearchResult> = emptyList()
    }

    // Never answers: what the encrypted store looks like on the first frame.
    private object NoKeys : WebSearchCredentialsRepository {
        override fun apiKeyFlow(provider: WebSearchProviderId): Flow<String?> = emptyFlow()
        override val configured: StateFlow<Set<WebSearchProviderId>?> = MutableStateFlow(null)
        override suspend fun setApiKey(provider: WebSearchProviderId, value: String) = Unit
        override suspend fun clear(provider: WebSearchProviderId) = Unit
    }
}
