package com.sabreware.aide.core.designsystem.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.LocalModalPresentation
import com.sabreware.aide.core.designsystem.ModalPresentation
import com.sabreware.aide.core.designsystem.resources.Res
import com.sabreware.aide.core.designsystem.resources.ic_lc_plug
import com.sabreware.aide.core.designsystem.resources.ic_lc_x
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [CollectionAction.leavesSheet]: an action that navigates away must not move the page behind a sheet
 * while the sheet is still on it. Tapped in an [ActionRail] inside an [AppDialog], it runs only once the sheet
 * has animated out — in the same dismissal as the sheet's `onDismiss` — never at the tap. An action that stays
 * (the default) runs at the tap and leaves the sheet open; a confirmation stays over the sheet and the sheet
 * closes only after it is accepted.
 */
@OptIn(ExperimentalTestApi::class)
class LeavingActionTest {

    private val leaving = CollectionAction<String>(id = "go", label = "Go", iconRes = Res.drawable.ic_lc_plug, leavesSheet = true) {
        events += "action"
    }
    private val staying = CollectionAction<String>(id = "stay", label = "Stay", iconRes = Res.drawable.ic_lc_x) {
        events += "stay"
    }
    private val confirmedLeaving = CollectionAction<String>(
        id = "confirm-go",
        label = "Confirm go",
        iconRes = Res.drawable.ic_lc_plug,
        leavesSheet = true,
        confirm = { Confirmation(title = "Really?", confirmLabel = "Yes", destructive = false) },
    ) { events += "action" }

    private val events = mutableListOf<String>()

    private fun ComposeUiTest.showSheet(presentation: ModalPresentation) {
        mainClock.autoAdvance = false
        var open by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalModalPresentation provides presentation) {
                val runner = rememberActionRunner(listOf(leaving, staying, confirmedLeaving))
                if (open) {
                    AppDialog(onDismiss = { events += "dismiss"; open = false }, title = "Model") { controller ->
                        ActionRail(runner, "item", dismiss = controller::close)
                    }
                }
            }
        }
        mainClock.advanceTimeBy(1_000) // entrance settles
    }

    private fun ComposeUiTest.exitCompletes() = mainClock.advanceTimeBy(2_000)

    private fun leavingClosesFirst(presentation: ModalPresentation) = runDesktopComposeUiTest {
        showSheet(presentation)
        onNodeWithText("Go").performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(50) // mid-exit: the sheet is still on the page
        assertEquals(emptyList(), events, "a leaving action must wait for the sheet's exit")
        exitCompletes()
        assertEquals(listOf("action", "dismiss"), events)
        onNodeWithText("Go").assertDoesNotExist()
    }

    @Test fun leavingActionRunsAfterTheSheetExits() = leavingClosesFirst(ModalPresentation.Sheet)

    @Test fun leavingActionRunsAfterTheDialogExits() = leavingClosesFirst(ModalPresentation.Dialog)

    @Test fun stayingActionRunsAtOnceAndKeepsTheSheet() = runDesktopComposeUiTest {
        showSheet(ModalPresentation.Sheet)
        onNodeWithText("Stay").performClick()
        mainClock.advanceTimeByFrame()
        assertEquals(listOf("stay"), events)
        exitCompletes()
        assertEquals(listOf("stay"), events)
        onNodeWithText("Stay").assertExists()
    }

    @Test fun confirmationStaysOverTheSheetThenTheSheetCloses() = runDesktopComposeUiTest {
        showSheet(ModalPresentation.Sheet)
        onNodeWithText("Confirm go").performClick()
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Really?").assertExists()
        onNodeWithText("Go").assertExists() // the sheet is still up under the confirmation
        assertEquals(emptyList(), events)
        onNodeWithText("Yes").performClick()
        exitCompletes()
        exitCompletes()
        assertEquals(listOf("action", "dismiss"), events)
        onNodeWithText("Go").assertDoesNotExist()
    }
}
