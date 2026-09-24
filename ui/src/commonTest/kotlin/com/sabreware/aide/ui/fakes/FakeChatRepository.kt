package com.sabreware.aide.ui.fakes

import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.MessageStats
import com.sabreware.aide.core.domain.chat.StoredMessage
import com.sabreware.aide.core.domain.llm.Surface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ChatRepository] for the ViewModel tests.
 *
 * Every chat-list use case wraps this one port, so faking it drives all eight of them at once — which is
 * why the ViewModels are testable without a Compose harness or a database.
 *
 * Only the chat-level surface is implemented; the message half throws, because a test that reaches it has
 * wandered outside what these tests are about and should say so loudly rather than pass on empty lists.
 */
class FakeChatRepository(initial: List<Chat> = emptyList()) : ChatRepository {

    private val store = MutableStateFlow(initial)

    /** What the store holds right now — the assertion surface for the mutating cases. */
    val snapshot: List<Chat> get() = store.value

    // A non-null StateFlow satisfies the nullable port type (StateFlow is covariant): the fake is always
    // "resolved", which is exactly the warm-cache case the seeded ViewModels exercise.
    override val chats: StateFlow<List<Chat>?> = store
    override suspend fun chatsSnapshot(): List<Chat> = store.value
    override suspend fun getChat(id: String): Chat? = store.value.firstOrNull { it.id == id }
    override fun observeChat(chatId: String): Flow<Chat?> = store.map { list -> list.firstOrNull { it.id == chatId } }

    override suspend fun deleteChat(chatId: String) {
        store.value = store.value.filterNot { it.id == chatId }
    }

    override suspend fun deleteChats(chatIds: List<String>) {
        store.value = store.value.filterNot { it.id in chatIds }
    }

    override suspend fun setStarred(chatId: String, starred: Boolean) =
        mutate(chatId) { it.copy(isStarred = starred) }

    override suspend fun setArchived(chatId: String, archived: Boolean) =
        mutate(chatId) { it.copy(isArchived = archived) }

    override suspend fun setArchivedForChats(chatIds: List<String>, archived: Boolean) {
        store.value = store.value.map { if (it.id in chatIds) it.copy(isArchived = archived) else it }
    }

    override suspend fun setTitle(chatId: String, title: String) = mutate(chatId) { it.copy(title = title) }

    override suspend fun createChat(title: String, surface: Surface): Chat =
        chat(id = "chat-${store.value.size + 1}", title = title, surface = surface)
            .also { store.value = store.value + it }

    override suspend fun touch(chatId: String) = Unit

    private fun mutate(chatId: String, block: (Chat) -> Chat) {
        store.value = store.value.map { if (it.id == chatId) block(it) else it }
    }

    // --- Message surface: out of scope for the chat-list ViewModels -----------------------------------
    private fun outOfScope(member: String): Nothing =
        throw UnsupportedOperationException("FakeChatRepository.$member is not part of the chat-list tests")

    override fun observeMessages(chatId: String): Flow<List<StoredMessage>> = outOfScope("observeMessages")
    override suspend fun messagesSnapshot(chatId: String): List<StoredMessage> = outOfScope("messagesSnapshot")
    override suspend fun appendMessage(chatId: String, role: String, text: String): Long = outOfScope("appendMessage")
    override suspend fun appendMessage(chatId: String, message: AideMessage): Long = outOfScope("appendMessage")
    override suspend fun deleteMessagesFrom(chatId: String, fromId: Long) = outOfScope("deleteMessagesFrom")
    override suspend fun updateMessageText(id: Long, text: String) = outOfScope("updateMessageText")
    override suspend fun updateMessage(id: Long, message: AideMessage) = outOfScope("updateMessage")
    override suspend fun updateMessageStats(id: Long, stats: MessageStats) = outOfScope("updateMessageStats")
}

/**
 * A [FakeChatRepository] whose cache has NOT resolved yet (null, and never fills) — pins the cold-open
 * contract: the ViewModels must seed Loading, never an empty list, when the session's first query is
 * still in flight.
 */
class ColdCacheChatRepository : ChatRepository by FakeChatRepository() {
    override val chats: StateFlow<List<Chat>?> = MutableStateFlow(null)
}

/**
 * A [Chat] with the timestamps filled in, since no test in this file cares about them and every one would
 * otherwise repeat them. Ordering-sensitive tests pass their own.
 */
fun chat(
    id: String,
    title: String = id,
    isStarred: Boolean = false,
    isArchived: Boolean = false,
    surface: Surface = Surface.CHAT,
    createdAt: Long = 0L,
    updatedAt: Long = 0L,
): Chat = Chat(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isStarred = isStarred,
    isArchived = isArchived,
    surface = surface,
)
