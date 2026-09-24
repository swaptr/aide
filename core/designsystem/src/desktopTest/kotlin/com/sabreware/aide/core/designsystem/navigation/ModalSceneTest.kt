package com.sabreware.aide.core.designsystem.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import com.sabreware.aide.core.designsystem.LocalModalPresentation
import com.sabreware.aide.core.designsystem.ModalPresentation
import com.sabreware.aide.core.designsystem.PageScaffold
import com.sabreware.aide.core.designsystem.setModalContent
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the one navigation model for modals: a run of [InModal] pages is ONE container over the page beneath,
 * pages push and pop inside it with the app's motion, back pops exactly one page (or closes the container on
 * its first page), and each page's chevron comes from its own depth. Run as a sheet and as a dialog: the
 * container is the only difference.
 */
@OptIn(ExperimentalTestApi::class)
class ModalSceneTest {

    @Serializable private data object Home : NavKey
    @Serializable private data object Pushed : NavKey
    @Serializable private data object First : NavKey
    @Serializable private data object Second : NavKey

    private val flow = "test-flow"

    @Composable
    private fun Display(stack: SnapshotStateList<NavKey>, presentation: ModalPresentation) {
        val app = object : Navigator {
            override val canGoBack get() = stack.size > 1
            override fun navigate(route: Any) { stack.add(route as NavKey) }
            override fun goBack() = stack.removeLastOrNull() != null
        }
        dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
        CompositionLocalProvider(LocalModalPresentation provides presentation, LocalNavigator provides app) {
            NavDisplay(
                backStack = stack,
                sceneStrategies = listOf(ModalSceneStrategy(stack), SinglePaneSceneStrategy()),
                entryProvider = modalAware(
                    entryProvider {
                        entry<Home> { Text("home page") }
                        entry<Pushed> { Text("pushed page") }
                        entry<First> {
                            PageScaffold(title = "First") {
                                Column(it) {
                                    val nav = navigator()
                                    TextButton(onClick = { nav.navigate(Second) }) { Text("open second") }
                                    TextButton(onClick = { nav.goBack() }) { Text("leave first") }
                                }
                            }
                        }
                        entry<Second> {
                            PageScaffold(title = "Second") { Column(it) { Text("second body") } }
                        }
                    },
                ),
            )
        }
    }

    // The system back every host feeds (Android back / gesture, desktop Esc) arrives through the navigation-event
    // dispatcher that both NavDisplay and Compose's BackHandler listen to; the test drives that dispatcher.
    private var dispatcher: NavigationEventDispatcher? = null

    private fun ComposeUiTest.pressBack() {
        runOnIdle {
            val input = DirectNavigationEventInput()
            checkNotNull(dispatcher).addInput(input)
            input.backStarted(NavigationEvent())
            input.backCompleted()
        }
        waitForIdle()
    }

    private fun bothContainers(test: ComposeUiTest.(ModalPresentation) -> Unit) {
        for (presentation in ModalPresentation.entries) {
            runDesktopComposeUiTest(width = 800, height = 800) { test(presentation) }
        }
    }

    @Test
    fun pushingInsideAFlowShowsThePageInTheSameContainer() = bothContainers { presentation ->
        val stack = mutableStateListOf<NavKey>(Home, InModal(flow, First))
        setModalContent { Display(stack, presentation) }
        onNodeWithText("First").assertIsDisplayed()
        onNodeWithText("home page").assertIsDisplayed()

        onNodeWithText("open second").performClick()
        waitForIdle()
        onNodeWithText("second body").assertIsDisplayed()
        assertEquals(listOf(Home, InModal(flow, First), InModal(flow, Second)), stack.toList())
    }

    @Test
    fun eachPageDrawsItsChevronFromItsOwnDepth() = bothContainers { presentation ->
        val stack = mutableStateListOf<NavKey>(Home, InModal(flow, First))
        setModalContent { Display(stack, presentation) }
        onNodeWithContentDescription("Back").assertDoesNotExist()
        onNodeWithText("open second").performClick()
        waitForIdle()
        onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun backPopsOnePageThenClosesTheContainer() = bothContainers { presentation ->
        val stack = mutableStateListOf<NavKey>(Home, Pushed, InModal(flow, First), InModal(flow, Second))
        setModalContent { Display(stack, presentation) }

        pressBack()
        waitForIdle()
        assertEquals(listOf(Home, Pushed, InModal(flow, First)), stack.toList(), "back pops exactly one page")
        onNodeWithText("First").assertIsDisplayed()

        pressBack()
        waitForIdle()
        assertEquals(listOf(Home, Pushed), stack.toList(), "back on the first page closes the whole flow")
        onNodeWithText("pushed page").assertIsDisplayed()
    }

    @Test
    fun goBackOnTheFirstPageClosesTheFlow() = bothContainers { presentation ->
        val stack = mutableStateListOf<NavKey>(Home, InModal(flow, First))
        setModalContent { Display(stack, presentation) }
        onNodeWithText("leave first").performClick()
        waitForIdle()
        assertEquals(listOf<NavKey>(Home), stack.toList())
        onNodeWithText("First").assertDoesNotExist()
    }

    // A phone rotating across the Adaptive bound turns the sheet into a dialog: same stack, same open page.
    @Test
    fun switchingSheetAndDialogKeepsTheFlowWhereItWas() = runDesktopComposeUiTest(width = 800, height = 800) {
        val stack = mutableStateListOf<NavKey>(Home, InModal(flow, First), InModal(flow, Second))
        var presentation by mutableStateOf(ModalPresentation.Sheet)
        setModalContent { Display(stack, presentation) }
        onNodeWithText("second body").assertIsDisplayed()

        presentation = ModalPresentation.Dialog
        waitForIdle()
        onNodeWithText("second body").assertIsDisplayed()
        onNodeWithContentDescription("Back").assertIsDisplayed()
        assertEquals(listOf(Home, InModal(flow, First), InModal(flow, Second)), stack.toList())
    }
}
