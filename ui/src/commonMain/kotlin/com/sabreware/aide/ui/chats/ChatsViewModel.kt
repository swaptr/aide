package com.sabreware.aide.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.usecase.ArchiveChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatsWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatsUseCase
import com.sabreware.aide.core.domain.usecase.RenameChatUseCase
import com.sabreware.aide.core.domain.usecase.SetChatArchivedUseCase
import com.sabreware.aide.core.domain.usecase.SetChatStarredUseCase
import com.sabreware.aide.core.domain.usecase.SetChatsArchivedUseCase
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.state.stateInUi
import com.sabreware.aide.core.designsystem.state.stateInUiSeeded
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which slice of chats the list shows. [matches] is the single source of truth for the predicate. */
enum class ChatFilter(val label: String) {
    All("All"),
    Pinned("Pinned"),
    Archived("Archived"),
    ;

    fun matches(chat: Chat): Boolean = when (this) {
        All -> !chat.isArchived
        Pinned -> chat.isStarred && !chat.isArchived
        Archived -> chat.isArchived
    }
}

/**
 * Which entry-point [Surface] a chat came from. An independent axis from [ChatFilter] — the list shows
 * chats matching both. [All] disables the surface filter. [matches] is the single source of truth.
 */
enum class SurfaceFilter(val label: String) {
    All("All"),
    Chat("Chat"),
    Voice("Voice"),
    Keyboard("Keyboard"),
    ;

    fun matches(chat: Chat): Boolean = when (this) {
        All -> true
        Chat -> chat.surface == Surface.CHAT
        Voice -> chat.surface == Surface.VOICE
        Keyboard -> chat.surface == Surface.IME
    }
}

/** Everything [ChatsScreen] renders. */
data class ChatsUiState(
    /**
     * [UiState], not a bare list: seeding an empty list painted "No chats yet" before the first database
     * emission. `Ready(emptyList())` is the empty state.
     */
    val chats: UiState<List<Chat>> = UiState.Loading,
)

/**
 * Backs [ChatsScreen]. The chat-list slice of
 * [com.sabreware.aide.ui.app.AppViewModel] plus this screen's own view state — the active filters and
 * multi-select — folded into one [ChatsUiState]. Single-chat actions back the row context menu; the
 * bulk actions back selection mode.
 */
class ChatsViewModel(
    observeChats: ObserveChatsUseCase,
    private val deleteChatWithFallback: DeleteChatWithFallbackUseCase,
    private val archiveChatWithFallback: ArchiveChatWithFallbackUseCase,
    private val setChatStarred: SetChatStarredUseCase,
    private val setChatArchived: SetChatArchivedUseCase,
    private val renameChatUseCase: RenameChatUseCase,
    private val deleteChatsWithFallback: DeleteChatsWithFallbackUseCase,
    private val setChatsArchived: SetChatsArchivedUseCase,
) : ViewModel() {

    // Bumping restarts the chats query — the list's pull-to-refresh re-fetches from the database.
    private val refreshTrigger = MutableStateFlow(0)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // Seeded from the repo's app-wide cache — the drawer keeps it hot at the root, so this screen opens
    // with the list on the first frame; pull-to-refresh re-subscribes on demand.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val chats: StateFlow<UiState<List<Chat>>> = refreshTrigger
        .flatMapLatest { observeChats().filterNotNull() }
        .stateInUiSeeded(viewModelScope, observeChats().value)

    fun refreshChats() {
        viewModelScope.launch {
            _refreshing.value = true
            refreshTrigger.update { it + 1 }
            // Floor so the indicator reads as a refresh even when the re-query returns instantly.
            delay(500)
            _refreshing.value = false
        }
    }

    val uiState: StateFlow<ChatsUiState> = chats
        .map { ChatsUiState(it) }
        .stateInUi(viewModelScope, ChatsUiState(chats = chats.value)) { error ->
            ChatsUiState(chats = UiState.Failed(error.message ?: "Could not load chats."))
        }

    // --- Actions on one chat or a selection (the list's filtering and selection live in the shared browse kit) ---

    /** Deletes [ids]; [onFallback] gets the deleted ids and the chat to fall back to if the open one went. */
    fun deleteChats(ids: List<String>, onFallback: (deleted: Set<String>, replacement: String) -> Unit) {
        if (ids.isEmpty()) return
        viewModelScope.launch { onFallback(ids.toSet(), deleteChatsWithFallback(ids)) }
    }

    fun setArchived(ids: List<String>, archived: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch { setChatsArchived(ids, archived) }
    }

    fun setStarred(ids: List<String>, starred: Boolean) {
        viewModelScope.launch { ids.forEach { setChatStarred(it, starred) } }
    }

    // --- Single-chat actions (row context menu) ---

    fun deleteChat(chatId: String, onReplacement: (String) -> Unit) {
        viewModelScope.launch { onReplacement(deleteChatWithFallback(chatId)) }
    }

    fun setStarred(chatId: String, starred: Boolean) {
        viewModelScope.launch { setChatStarred(chatId, starred) }
    }

    fun setArchived(chatId: String, archived: Boolean, onReplacement: ((String) -> Unit)? = null) {
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
