package com.swaptr.aide.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaptr.aide.data.chat.ChatEntity
import com.swaptr.aide.domain.usecase.ArchiveChatWithFallbackUseCase
import com.swaptr.aide.domain.usecase.DeleteChatWithFallbackUseCase
import com.swaptr.aide.domain.usecase.ObserveChatsUseCase
import com.swaptr.aide.domain.usecase.RenameChatUseCase
import com.swaptr.aide.domain.usecase.ResolveInitialChatUseCase
import com.swaptr.aide.domain.usecase.SetChatArchivedUseCase
import com.swaptr.aide.domain.usecase.SetChatStarredUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    observeChats: ObserveChatsUseCase,
    private val resolveInitialChat: ResolveInitialChatUseCase,
    private val deleteChatWithFallback: DeleteChatWithFallbackUseCase,
    private val archiveChatWithFallback: ArchiveChatWithFallbackUseCase,
    private val setChatStarred: SetChatStarredUseCase,
    private val setChatArchived: SetChatArchivedUseCase,
    private val renameChatUseCase: RenameChatUseCase,
) : ViewModel() {

    val chats: StateFlow<List<ChatEntity>> = observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Empty string = draft chat (no DB row); chat materialises on first send.
    private val _initialChatId = MutableStateFlow<String?>(null)
    val initialChatId: StateFlow<String?> = _initialChatId.asStateFlow()

    init {
        viewModelScope.launch { _initialChatId.value = resolveInitialChat() }
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
