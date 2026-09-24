package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import com.sabreware.aide.core.designsystem.navigation.InModal
import com.sabreware.aide.core.designsystem.navigation.ModalSceneStrategy
import com.sabreware.aide.core.designsystem.navigation.modalAware
import kotlinx.serialization.Serializable
import kotlin.test.Test

/**
 * A page's auto-focus never fires in the frames a modal is changing hands: a leaf sheet leaving as a flow opens in
 * its place (the chat's "+" sheet opening Connectors) must not raise the page's keyboard under the flow.
 */
@OptIn(ExperimentalTestApi::class)
class AutoFocusTest {
    @Serializable private data object Page : NavKey

    @Serializable private data object FlowPage : NavKey

    @Composable
    private fun Field() {
        val focus = remember { FocusRequester() }
        AutoFocus(focus, showKeyboard = false)
        BasicTextField("", {}, Modifier.testTag(FieldTag).focusRequester(focus))
    }

    @Test
    fun uncoveredPageTakesFocus() = runComposeUiTest {
        setModalContent { Field() }
        waitForIdle()
        onNodeWithTag(FieldTag).assertIsFocused()
    }

    @Test
    fun sheetHandingOffToFlowNeverFocusesThePageBeneath() = runComposeUiTest {
        val stack = mutableStateListOf<NavKey>(Page)
        var leafOpen by mutableStateOf(true)
        setModalContent {
            NavDisplay(
                backStack = stack,
                sceneStrategies = listOf(remember { ModalSceneStrategy(stack) }, SinglePaneSceneStrategy()),
                entryProvider = modalAware(
                    entryProvider {
                        entry<Page> { Field() }
                        entry<FlowPage> { }
                    },
                ),
            )
            if (leafOpen) AppDialog(onDismiss = { leafOpen = false }) { }
        }
        waitForIdle()
        onNodeWithTag(FieldTag).assertIsNotFocused()

        mainClock.autoAdvance = false
        // One snapshot, as a sheet's close { openFlow() } does: the leaf leaves and the flow arrives together.
        runOnIdle {
            leafOpen = false
            stack.add(InModal("flow", FlowPage))
        }
        repeat(FocusSettleFrames * 4) {
            mainClock.advanceTimeByFrame()
            onNodeWithTag(FieldTag, useUnmergedTree = true).assertIsNotFocused()
        }
    }

    private companion object {
        const val FieldTag = "field"
    }
}
