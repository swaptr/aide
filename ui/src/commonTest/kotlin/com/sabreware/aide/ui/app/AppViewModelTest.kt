package com.sabreware.aide.ui.app

import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.usecase.ArchiveChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatsUseCase
import com.sabreware.aide.core.domain.usecase.RenameChatUseCase
import com.sabreware.aide.core.domain.usecase.SetChatArchivedUseCase
import com.sabreware.aide.core.domain.usecase.SetChatStarredUseCase
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The shell's own state: the session-long chat list the drawer renders, and the sidebar's persistence.
 *
 * The chat list matters more than it looks. It is deliberately collected once at the app root so the drawer
 * — which Material keeps composed offscreen — already holds current data and opening it is pure translation.
 * A regression that makes it start empty rather than Loading shows "No chats yet" for one frame in a panel
 * that is mid-slide, which is exactly the class of bug nobody reproduces on demand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val repo = FakeChatRepository(
        listOf(chat("a", title = "Alpha"), chat("b", title = "Beta", isArchived = true)),
    )
    private val prefs = FakePreferenceStore()

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(chatRepo: ChatRepository = repo) = AppViewModel(
        observeChats = ObserveChatsUseCase(chatRepo),
        prefs = prefs,
        deleteChatWithFallback = DeleteChatWithFallbackUseCase(repo),
        archiveChatWithFallback = ArchiveChatWithFallbackUseCase(repo),
        setChatStarred = SetChatStarredUseCase(repo),
        setChatArchived = SetChatArchivedUseCase(repo),
        renameChatUseCase = RenameChatUseCase(repo),
    )

    /** `stateInUi` is lazy — without a live subscriber `.value` stays on the seed and asserts nothing. */
    private fun withCollectedChats(block: suspend TestScope.(AppViewModel) -> Unit) = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.chats.collect { } }
        advanceUntilIdle()
        block(vm)
    }

    @Test
    fun `the chat list seeds Ready from a warm cache, Loading from a cold one`() = runTest {
        // Warm cache (the repo StateFlow already holds a list): the drawer paints it on the FIRST frame —
        // no skeleton, no crossfade — while the shared upstream refreshes behind it.
        val warm = viewModel().chats.value
        assertTrue(warm is UiState.Ready, "a warm repo cache seeds Ready synchronously")
        assertEquals(listOf("a", "b"), warm.value.map { it.id })
        // Cold cache (first query of the session still in flight): Loading, never an empty list —
        // Ready(emptyList()) is the empty state, and the drawer draws them differently.
        assertEquals(UiState.Loading, viewModel(ColdCacheChatRepository()).chats.value)
    }

    @Test
    fun `the chat list becomes Ready with every chat, archived included`() = withCollectedChats { vm ->
        val state = vm.chats.value
        assertTrue(state is UiState.Ready, "a healthy repository resolves")
        assertEquals(
            listOf("a", "b"),
            state.value.map { it.id },
            "filtering is the list screen's job — the shell holds the whole set",
        )
    }

    // --- Sidebar persistence -----------------------------------------------------------------------------

    @Test
    fun `the sidebar starts from the stored preference`() = runTest {
        prefs.set(ShellKeys.SidebarOpen, true)
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.sidebarOpen.value, "the shell restores the pane the user left open")
    }

    @Test
    fun `setting the sidebar updates immediately and persists`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setSidebarOpen(true)
        assertTrue(vm.sidebarOpen.value, "the flag flips synchronously — the pane must not wait on a write")

        advanceUntilIdle()
        assertTrue(prefs.get(ShellKeys.SidebarOpen), "and the write does happen")
    }

    @Test
    fun `the sidebar falls back to its default when nothing is stored`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(ShellKeys.SidebarOpen.default, vm.sidebarOpen.value)
    }

    // --- Chat actions shared with the list screen --------------------------------------------------------

    @Test
    fun `deleting the open chat names where to go next`() = runTest {
        val vm = viewModel()
        var replacement: String? = null
        vm.deleteChat("a") { replacement = it }
        advanceUntilIdle()

        assertEquals("", replacement, "b is archived, so there is nowhere unarchived to land")
        assertEquals(listOf("b"), repo.snapshot.map { it.id })
    }

    @Test
    fun `archiving reports a replacement only when the caller asked`() = runTest {
        val vm = viewModel()

        vm.setArchived("a", archived = true)
        advanceUntilIdle()
        assertTrue(repo.snapshot.first { it.id == "a" }.isArchived)

        var called = false
        vm.setArchived("a", archived = false) { called = true }
        advanceUntilIdle()
        assertFalse(called, "restoring a chat never navigates you away from it")
        assertFalse(repo.snapshot.first { it.id == "a" }.isArchived)
    }

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
     * The indicator is raised inside a `launch`, so it appears on the next dispatch rather than on the
     * gesture itself — invisible in practice, but the reason this test advances before asserting instead
     * of reading straight after the call.
     *
     * The 500 ms floor is the point: without it a re-query that returns instantly would flash the spinner
     * for a single frame, which reads as a glitch rather than a refresh.
     */
    @Test
    fun `refreshing raises the indicator and holds it for the floor delay`() = withCollectedChats { vm ->
        vm.refreshChats()
        advanceTimeBy(1)
        assertTrue(vm.chatsRefreshing.value, "raised once the launch runs")

        advanceTimeBy(400)
        assertTrue(vm.chatsRefreshing.value, "still held part-way through the floor")

        advanceUntilIdle()
        assertFalse(vm.chatsRefreshing.value, "and cleared once the floor elapses")
    }
}
