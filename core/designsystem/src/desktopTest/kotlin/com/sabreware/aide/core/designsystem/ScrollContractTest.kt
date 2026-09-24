package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.navigation.LocalNavigator
import com.sabreware.aide.core.designsystem.navigation.Navigator
import com.sabreware.aide.core.designsystem.theme.AppSpacing
import kotlin.test.Test

/**
 * Pins the scrolling contract ([ScrollOwner]): in a window too short for the content, every row of every
 * modal and page is reachable, the header stays pinned, and a self-scrolling body does not nest a second
 * scroller. The window is phone-landscape short (400 tall) — the case that used to clip.
 *
 * `performScrollTo` fails outright when the node has no scrollable ancestor, so each test proves a scroll
 * exists, not just that the row happens to fit.
 */
@OptIn(ExperimentalTestApi::class)
class ScrollContractTest {

    private val rowCount = 40

    @Composable
    private fun Rows() {
        repeat(rowCount) { Text("row $it", Modifier.fillMaxWidth().height(48.dp)) }
    }

    private fun SemanticsNodeInteractionsProvider.assertLastRowReachableAndHeaderPinned(header: String) {
        onNodeWithText("row ${rowCount - 1}").performScrollTo().assertIsDisplayed()
        onNodeWithText(header).assertIsDisplayed()
    }

    @Test
    fun leafDialogScrollsItsOverflowUnderAPinnedHeader() = runDesktopComposeUiTest(width = 800, height = 400) {
        setModalContent {
            CompositionLocalProvider(LocalModalPresentation provides ModalPresentation.Dialog) {
                AppDialog(onDismiss = {}, title = "Leaf") { Rows() }
            }
        }
        assertLastRowReachableAndHeaderPinned("Leaf")
    }

    @Test
    fun leafSheetExpandsThenScrollsToItsLastRow() = runDesktopComposeUiTest(width = 800, height = 400) {
        setModalContent {
            CompositionLocalProvider(LocalModalPresentation provides ModalPresentation.Sheet) {
                AppDialog(onDismiss = {}, title = "Sheet") { Rows() }
            }
        }
        waitForIdle()
        // A drag up first expands the sheet from its peek, then scrolls the body — the nested-scroll handoff.
        repeat(12) { onNodeWithText("Sheet").performTouchInput { swipeUp() } }
        waitForIdle()
        assertLastRowReachableAndHeaderPinned("Sheet")
    }

    @Test
    fun flowPageScrollsByDefault() = runDesktopComposeUiTest(width = 800, height = 400) {
        setModalContent {
            CompositionLocalProvider(LocalModalPresentation provides ModalPresentation.Dialog) {
                val stack = rememberNavDialogBackStack("home")
                AppDialog(backStack = stack, onDismiss = {}, size = AppDialogSize.Expandable) {
                    page<String> { _, _ -> PageScaffold(title = "Page") { Column(it) { Rows() } } }
                }
            }
        }
        assertLastRowReachableAndHeaderPinned("Page")
    }

    @Test
    fun selfScrollingFlowPageIsBoundedNotNested() = runDesktopComposeUiTest(width = 800, height = 400) {
        setModalContent {
            CompositionLocalProvider(LocalModalPresentation provides ModalPresentation.Dialog) {
                val stack = rememberNavDialogBackStack("home")
                AppDialog(backStack = stack, onDismiss = {}, size = AppDialogSize.Expandable) {
                    page<String> { _, _ ->
                        PageScaffold(title = "List", scroll = ScrollOwner.Content) { contentModifier ->
                            LazyColumn(contentModifier.fillMaxSize().testTag("list")) {
                                items((0 until 100).toList()) { Text("item $it", Modifier.height(48.dp)) }
                            }
                        }
                    }
                }
            }
        }
        onNodeWithTag("list").performScrollToNode(hasText("item 99"))
        onNodeWithText("item 99").assertIsDisplayed()
        onNodeWithText("List").assertIsDisplayed()
    }

    @Test
    fun screenPageScrollsByDefault() = runDesktopComposeUiTest(width = 800, height = 400) {
        setModalContent {
            CompositionLocalProvider(LocalNavigator provides RootNavigator) {
                PageScaffold(title = "Screen") { Column(it) { Rows() } }
            }
        }
        assertLastRowReachableAndHeaderPinned("Screen")
    }

    @Test
    fun placeholderScrollsWhenBoundedAndDefersToAScrollingParent() = runDesktopComposeUiTest(width = 800, height = 400) {
        setModalContent {
            Column {
                PlaceholderLayout(Modifier.fillMaxWidth().height(200.dp)) { Rows() }
                // Unbounded: inside a lazy list it must lay out at natural height, not nest a scroller (throws).
                LazyColumn(Modifier.height(150.dp)) { item { Placeholder(subtitle = "nested placeholder") } }
            }
        }
        onNodeWithText("row ${rowCount - 1}").performScrollTo().assertIsDisplayed()
        onNodeWithText("nested placeholder").assertIsDisplayed()
    }

    @Test
    fun placeholderSitsAtTheDefaultInsetAndHeroSitsAQuarterDown() = runDesktopComposeUiTest(width = 800, height = 400) {
        setModalContent {
            Column {
                PlaceholderLayout(Modifier.fillMaxWidth().height(200.dp)) { Text("default") }
                CompositionLocalProvider(LocalPlaceholderStyle provides PlaceholderStyle.Hero) {
                    PlaceholderLayout(Modifier.fillMaxWidth().height(200.dp)) { Text("hero") }
                }
            }
        }
        // Default: the shared inset. Hero: a quarter of its 200dp pane (50dp) beats twice the inset (48dp).
        onNodeWithText("default").assertTopPositionInRootIsEqualTo(AppSpacing.xl)
        onNodeWithText("hero").assertTopPositionInRootIsEqualTo(200.dp + 50.dp)
    }

    private object RootNavigator : Navigator {
        override val canGoBack = false
        override fun navigate(route: Any) = Unit
        override fun goBack() = false
    }
}
