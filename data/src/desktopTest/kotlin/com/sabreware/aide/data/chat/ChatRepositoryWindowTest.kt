package com.sabreware.aide.data.chat

import androidx.room.Room
import com.sabreware.aide.core.domain.chat.AideMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The chat store over a real (in-memory) Room database: lazy row creation under a client-minted id, and the
 * keyset window a long chat is read through. The window's SQL is the whole point, so it runs against SQLite,
 * not a fake.
 */
class ChatRepositoryWindowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = buildDatabase(
        Room.inMemoryDatabaseBuilder<AideDatabase>().setQueryCoroutineContext(Dispatchers.IO),
    )
    private val repo = ChatRepositoryImpl(db.chatDao(), scope)

    @AfterTest
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    @Test
    fun `createChat writes the row under the given id once and never replaces it`() = runBlocking {
        repo.createChat(id = "c1", title = "First")
        repo.appendMessage("c1", AideMessage.user("hello"))

        val again = repo.createChat(id = "c1", title = "Second")

        assertEquals("First", again.title, "an existing row is returned, not overwritten")
        assertTrue(repo.hasMessages("c1"), "a replace would have cascade-deleted the messages")
        assertEquals(listOf("c1"), repo.chatsSnapshot().map { it.id })
    }

    @Test
    fun `the live window is the newest rows, oldest first, and says whether older ones exist`() = runBlocking {
        val ids = seed("long", count = 250)

        val tail = repo.observeMessageWindow("long", upToId = null, limit = 60).first()

        assertEquals(ids.takeLast(60), tail.messages.map { it.id })
        assertEquals("turn 250", tail.messages.last().message.textContent)
        assertTrue(tail.hasOlder)
    }

    @Test
    fun `a window below an id pages toward the start and finds its end`() = runBlocking {
        val ids = seed("long", count = 250)

        val middle = repo.observeMessageWindow("long", upToId = ids[149], limit = 100).first()
        assertEquals(ids.subList(50, 150), middle.messages.map { it.id })
        assertTrue(middle.hasOlder)

        val start = repo.observeMessageWindow("long", upToId = ids[49], limit = 100).first()
        assertEquals(ids.take(50), start.messages.map { it.id })
        assertFalse(start.hasOlder)

        assertEquals(ids.subList(150, 190), repo.messageIdsAfter("long", afterId = ids[149], limit = 40))
    }

    @Test
    fun `a window reads only its own chat`() = runBlocking {
        seed("a", count = 5)
        val b = seed("b", count = 3)

        val window = repo.observeMessageWindow("b", upToId = null, limit = 60).first()

        assertEquals(b, window.messages.map { it.id })
        assertFalse(window.hasOlder)
    }

    private suspend fun seed(chatId: String, count: Int): List<Long> {
        repo.createChat(id = chatId)
        return (1..count).map { repo.appendMessage(chatId, AideMessage.user("turn $it")) }
    }
}
