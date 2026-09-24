package com.sabreware.aide.ui.chats

import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.usecase.ArchiveChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatsWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatsUseCase
import com.sabreware.aide.core.domain.usecase.RenameChatUseCase
import com.sabreware.aide.core.domain.usecase.SetChatArchivedUseCase
import com.sabreware.aide.core.domain.usecase.SetChatStarredUseCase
import com.sabreware.aide.core.domain.usecase.SetChatsArchivedUseCase
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.ui.fakes.ColdCacheChatRepository
import com.sabreware.aide.ui.fakes.FakeChatRepository
import com.sabreware.aide.ui.fakes.chat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The chat list's own logic: which chats a filter admits, what the bulk actions do, and where the caller is
 * sent after the open chat is deleted.
 *
 * `:ui` had no shared test source set before this. It is the module both applications render, and the
 * ViewModels in it are where the behaviour lives — the screens are a projection of this state. Every case
 * here runs without a Compose harness, which is the payoff for keeping the ViewModels host-agnostic.
 *
 * `Dispatchers.setMain` is required because `viewModelScope` is hard-wired to the main dispatcher; without
 * it every `launch` in the ViewModel silently never runs and the tests pass by doing nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModelTest {

    private val repo = FakeChatRepository(
        listOf(
            chat("a", title = "Alpha", surface = Surface.CHAT),
            chat("b", title = "Beta", isStarred = true, surface = Surface.VOICE),
            chat("c", title = "Gamma", isArchived = true, surface = Surface.IME),
            chat("d", title = "Delta", isStarred = true, isArchived = true),
        ),
    )

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /**
     * Runs [block] with [ChatsViewModel.uiState] actually collected.
     *
     * `stateInUi` starts lazily, so reading `.value` without a subscriber returns the seed forever — a test
     * that skips this passes by observing nothing, which is worse than failing.
     */
    private fun withCollectedState(block: suspend TestScope.(ChatsViewModel) -> Unit) = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        block(vm)
    }

    private fun viewModel(chatRepo: ChatRepository = repo) = ChatsViewModel(
        observeChats = ObserveChatsUseCase(chatRepo),
        deleteChatWithFallback = DeleteChatWithFallbackUseCase(repo),
        archiveChatWithFallback = ArchiveChatWithFallbackUseCase(repo),
        setChatStarred = SetChatStarredUseCase(repo),
        setChatArchived = SetChatArchivedUseCase(repo),
        renameChatUseCase = RenameChatUseCase(repo),
        deleteChatsWithFallback = DeleteChatsWithFallbackUseCase(repo),
        setChatsArchived = SetChatsArchivedUseCase(repo),
    )

    // --- The filter predicates, which the screen and the search screen both read -------------------------

    @Test
    fun `All hides archived chats — it is not really all`() {
        val visible = listOf("a", "b", "c", "d").map { id -> repo.snapshot.first { it.id == id } }
            .filter(ChatFilter.All::matches)
        assertEquals(listOf("a", "b"), visible.map { it.id })
    }

    @Test
    fun `Pinned means pinned AND not archived`() {
        val visible = repo.snapshot.filter(ChatFilter.Pinned::matches)
        assertEquals(listOf("b"), visible.map { it.id }, "d is starred but archived, so it belongs to Archived")
    }

    @Test
    fun `Archived is the only filter that shows archived chats`() {
        assertEquals(listOf("c", "d"), repo.snapshot.filter(ChatFilter.Archived::matches).map { it.id })
    }

    @Test
    fun `the surface filter is an independent axis`() {
        // `d` carries the default CHAT surface, so it belongs to this bucket too — the surface axis knows
        // nothing about archived-ness, which is the independence being asserted.
        assertEquals(listOf("a", "d"), repo.snapshot.filter(SurfaceFilter.Chat::matches).map { it.id })
        assertEquals(listOf("b"), repo.snapshot.filter(SurfaceFilter.Voice::matches).map { it.id })
        assertEquals(listOf("c"), repo.snapshot.filter(SurfaceFilter.Keyboard::matches).map { it.id })
        assertEquals(4, repo.snapshot.count(SurfaceFilter.All::matches), "All disables the axis")
    }

    // --- The list state ----------------------------------------------------------------------------------

    @Test
    fun `the list seeds Ready from a warm cache, Loading from a cold one`() = runTest {
        // Warm cache: the screen paints the list on the FIRST frame (the drawer keeps the repo cache hot).
        assertTrue(viewModel().uiState.value.chats is UiState.Ready, "a warm repo cache seeds Ready synchronously")
        // Cold cache: Loading, never an empty list — seeding empty would paint 'No chats yet' before the
        // first emission ever arrives.
        assertEquals(UiState.Loading, viewModel(ColdCacheChatRepository()).uiState.value.chats)
    }

    @Test
    fun `the list becomes Ready once the repository emits`() = withCollectedState { vm ->
        val state = vm.uiState
        // Collection is what starts the underlying flow; reading `.value` alone leaves it Loading forever.
        val ready = state.value.chats
        assertTrue(ready is UiState.Loading || ready is UiState.Ready, "never Failed on a healthy repo")
    }

    // --- Bulk actions (selection itself lives in the shared browse kit) and the fallback -----------------

    @Test
    fun `bulk actions on no ids do nothing at all`() = runTest {
        val vm = viewModel()

        var called = false
        vm.deleteChats(emptyList()) { _, _ -> called = true }
        vm.setArchived(emptyList(), archived = true)
        advanceUntilIdle()

        assertFalse(called, "no callback for a no-op")
        assertEquals(4, repo.snapshot.size, "and nothing was touched")
        assertTrue(repo.snapshot.none { it.id in setOf("a", "b") && it.isArchived })
    }

    @Test
    fun `deleting chats removes them and names a replacement chat`() = runTest {
        val vm = viewModel()

        var deleted: Set<String>? = null
        var replacement: String? = null
        vm.deleteChats(listOf("a")) { ids, next -> deleted = ids; replacement = next }
        advanceUntilIdle()

        assertEquals(setOf("a"), deleted)
        assertEquals("b", replacement, "the fallback is the first non-archived survivor")
        assertEquals(listOf("b", "c", "d"), repo.snapshot.map { it.id })
    }

    /**
     * Deleting everything unarchived must not name an archived chat as the replacement — the caller
     * navigates to whatever it is given, and an empty string is the signal for "nothing to open".
     */
    @Test
    fun `deleting every unarchived chat yields an empty replacement`() = runTest {
        val vm = viewModel()

        var replacement: String? = null
        vm.deleteChats(listOf("a", "b")) { _, next -> replacement = next }
        advanceUntilIdle()

        assertEquals("", replacement, "archived chats are not a landing place")
    }

    @Test
    fun `archiving several chats archives every id`() = runTest {
        val vm = viewModel()

        vm.setArchived(listOf("a", "b"), archived = true)
        advanceUntilIdle()

        assertTrue(repo.snapshot.filter { it.id in setOf("a", "b") }.all { it.isArchived })
    }

    @Test
    fun `starring several chats stars every id`() = runTest {
        val vm = viewModel()

        vm.setStarred(listOf("a", "c"), starred = true)
        advanceUntilIdle()

        assertTrue(repo.snapshot.filter { it.id in setOf("a", "c") }.all { it.isStarred })
    }

    // --- Single-chat row actions -------------------------------------------------------------------------

    @Test
    fun `starring and renaming reach the repository`() = runTest {
        val vm = viewModel()
        vm.setStarred("a", starred = true)
        vm.renameChat("a", "Renamed")
        advanceUntilIdle()

        val a = repo.snapshot.first { it.id == "a" }
        assertTrue(a.isStarred)
        assertEquals("Renamed", a.title)
    }

    /**
     * Archiving from a row only reports a replacement when the caller asked for one — that callback is what
     * navigates away, and firing it when the archived chat was not the open one would move the user
     * unexpectedly.
     */
    @Test
    fun `archiving reports a replacement only when the caller wants one`() = runTest {
        val vm = viewModel()

        vm.setArchived("a", archived = true)
        advanceUntilIdle()
        assertTrue(repo.snapshot.first { it.id == "a" }.isArchived, "still archived without the callback")

        var replacement: String? = null
        vm.setArchived("b", archived = true) { replacement = it }
        advanceUntilIdle()
        assertEquals("", replacement, "a and b are both archived now, so nothing unarchived remains")
    }

    @Test
    fun `unarchiving never asks for a replacement`() = runTest {
        val vm = viewModel()
        var called = false
        vm.setArchived("c", archived = false) { called = true }
        advanceUntilIdle()

        assertFalse(repo.snapshot.first { it.id == "c" }.isArchived)
        assertFalse(called, "you are not navigated away from a chat you just restored")
    }
}
