package com.sabreware.aide.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import com.sabreware.aide.core.designsystem.navigation.InModal
import com.sabreware.aide.core.designsystem.navigation.ModalSceneStrategy
import com.sabreware.aide.core.designsystem.navigation.modalAware
import kotlinx.serialization.Serializable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/** [ComposeUiTest.setContent] under a [ModalHost], as every app root is (AideTheme installs one). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setModalContent(content: @Composable () -> Unit) = setContent { ModalHost(content) }

@Serializable private data object TestBase : NavKey

@Serializable private data object TestFlowPage : NavKey

/** [page] as the first page of a modal flow over an empty page — how every flow page is hosted in the app. */
@Composable
fun ModalFlowHost(page: @Composable () -> Unit) {
    val stack = remember { mutableStateListOf<NavKey>(TestBase, InModal("test", TestFlowPage)) }
    NavDisplay(
        backStack = stack,
        sceneStrategies = listOf(remember { ModalSceneStrategy(stack) }, SinglePaneSceneStrategy()),
        entryProvider = modalAware(
            entryProvider {
                entry<TestBase> { }
                entry<TestFlowPage> { page() }
            },
        ),
    )
}
