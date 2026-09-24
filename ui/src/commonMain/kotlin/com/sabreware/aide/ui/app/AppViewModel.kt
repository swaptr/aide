package com.sabreware.aide.ui.app

import com.sabreware.aide.core.common.prefs.peek
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.state.stateInUiSeeded
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.usecase.ArchiveChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatsUseCase
import com.sabreware.aide.core.domain.usecase.RenameChatUseCase
import com.sabreware.aide.core.domain.usecase.SetChatArchivedUseCase
import com.sabreware.aide.core.domain.usecase.SetChatStarredUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel(
    observeChats: ObserveChatsUseCase,
    private val prefs: PreferenceStore,
    private val deleteChatWithFallback: DeleteChatWithFallbackUseCase,
    private val archiveChatWithFallback: ArchiveChatWithFallbackUseCase,
    private val setChatStarred: SetChatStarredUseCase,
    private val setChatArchived: SetChatArchivedUseCase,
    private val renameChatUseCase: RenameChatUseCase,
) : ViewModel() {

    // Bumping restarts the chats query — the drawer's pull-to-refresh re-fetches from the database.
    private val chatsRefreshTrigger = MutableStateFlow(0)

    private val _chatsRefreshing = MutableStateFlow(false)
    val chatsRefreshing: StateFlow<Boolean> = _chatsRefreshing.asStateFlow()

    // Loading until the first query lands; Ready(emptyList()) = no chats. Collected at the app root
    // (AppNav), so the list is resident for the whole session: the drawer sheet renders it while parked
    // offscreen and opening the drawer is pure translation — nothing queries or composes mid-slide.
    // The repo's cached Room flow keeps it live; pull-to-refresh re-subscribes on demand, and the seed
    // paints the first frame on a recreation whenever the session's cache is already warm.
    @OptIn(ExperimentalCoroutinesApi::class)
    val chats: StateFlow<UiState<List<Chat>>> = chatsRefreshTrigger
        .flatMapLatest { observeChats().filterNotNull() }
        .stateInUiSeeded(viewModelScope, observeChats().value)

    fun refreshChats() {
        viewModelScope.launch {
            _chatsRefreshing.value = true
            chatsRefreshTrigger.update { it + 1 }
            // Floor so the indicator reads as a refresh even when the re-query returns instantly.
            delay(500)
            _chatsRefreshing.value = false
        }
    }

    // From the prefs snapshot (every host holds its first frame on it): a default seed followed by a read
    // animated a saved-open sidebar in on every launch. The read covers only a host that could not wait.
    private val _sidebarOpen = MutableStateFlow(prefs.peek(ShellKeys.SidebarOpen))
    val sidebarOpen: StateFlow<Boolean> = _sidebarOpen.asStateFlow()

    init {
        if (!prefs.isLoaded) viewModelScope.launch { _sidebarOpen.value = prefs.get(ShellKeys.SidebarOpen) }
    }

    fun setSidebarOpen(open: Boolean) {
        _sidebarOpen.value = open
        viewModelScope.launch { prefs.set(ShellKeys.SidebarOpen, open) }
    }

    fun deleteChat(chatId: String, onReplacement: (String) -> Unit) {
        viewModelScope.launch {
            val target = deleteChatWithFallback(chatId)
            onReplacement(target)
        }
    }

    fun setStarred(chatId: String, starred: Boolean) {
        viewModelScope.launch { setChatStarred(chatId, starred) }
    }

    fun setArchived(
        chatId: String,
        archived: Boolean,
        onReplacement: ((String) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            if (archived && onReplacement != null) {
                onReplacement(archiveChatWithFallback(chatId))
            } else {
                setChatArchived(chatId, archived)
            }
        }
    }

    fun renameChat(chatId: String, newTitle: String) {
        viewModelScope.launch { renameChatUseCase(chatId, newTitle) }
    }
}
